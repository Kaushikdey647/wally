# Implementation Details

This document explains the internal implementation of Wally's SPSC ring buffer, covering memory layout, algorithms, and design decisions. The reasoning behind each decision is recorded in the [ADRs](adr/0001-ffm-over-unsafe.md).

## Memory Layout

### Overall Structure

The ring buffer consists of two regions allocated in a single contiguous block:

```
Offset    Size      Description
─────────────────────────────────────────────────────
0         256B      Cursor metadata (head + tail with padding)
256       N bytes   Ring buffer data (capacity)
```

Total allocation: `256 + capacity` bytes, aligned to 64-byte boundary.

### Cursor Metadata Layout

```
Byte      Content                              Purpose
───────────────────────────────────────────────────────────────
0-55      Padding (unused)                     Cache line padding
56-63     Head cursor (long)                   Consumer's read position
64-183    Padding (unused)                     Prevent false sharing
184-191   Tail cursor (long)                   Producer's write position
192-255   Padding (unused)                     Cache line padding
```

**Rationale**: 128-byte separation prevents false sharing even with Adjacent Cache Line Prefetch (Intel) which speculatively loads adjacent cache lines.

**Virtual cursors**: Both head and tail are monotonically increasing 64-bit integers representing byte offsets in a virtual infinite buffer. The actual ring index is computed via masking.

### Ring Buffer Data Layout

Frames are stored contiguously with wrapping at capacity boundary:

```
Frame structure:
┌──────────┬──────────┬────────────────────────────┐
│ Length   │ Padding  │ Payload (8-byte aligned)   │
│ 4 bytes  │ 4 bytes  │ length bytes, padded to 8  │
└──────────┴──────────┴────────────────────────────┘
◄────── 8 bytes ─────►◄──────── aligned ──────────►
```

**Length field**: Contains actual payload size (not padded size). Allows reader to deliver exact message length to handler.

**Padding**: 4 bytes after length ensures payload starts at 8-byte aligned offset. Required for `ValueLayout` operations.

**Payload padding**: Payload is padded to 8-byte boundary so next frame starts aligned.

## Index Calculation

Ring indices are computed from virtual cursors via masking:

```java
int index = (int) (cursor & mask);
```

Where `mask = capacity - 1` (capacity must be power of 2).

**Example** (capacity = 256, mask = 0xFF):
```
cursor = 0     → index = 0
cursor = 100   → index = 100
cursor = 256   → index = 0    (wrap)
cursor = 300   → index = 44
```

**Benefits**:
- Fast modulo via single AND operation
- No branch mispredictions
- Natural sequence numbers from cursor values
- Avoids wrapping arithmetic complexity

## Write Algorithm

### Phase 1: Claim Space

```java
boolean tryClaim(int length) {
    int frameSize = HEADER_SIZE + align(length, 8);

    long tail = this.tail;                 // writer owns tail: plain field, no atomic load
    int tailIndex = (int) (tail & mask);

    if (tailIndex + frameSize > capacity) {
        // The gap [tail, nextLap) must be free before the sentinel is written,
        // otherwise the sentinel could overwrite an unread frame's length.
        long nextLap = (tail & ~mask) + capacity;
        if (!hasSpace(nextLap)) return false;          // nothing written
        data.set(JAVA_INT, tailIndex, 0);              // sentinel
        this.tail = tail = nextLap;
        tailIndex = 0;
        setLongRelease(memory, tailOffset, tail);      // publish the skip on its own
    }

    long newTail = tail + frameSize;
    if (!hasSpace(newTail)) return false;              // nothing written
    data.set(JAVA_INT, tailIndex, length);             // invisible until commit
    claimedTail = tail;
    claimedNextTail = newTail;
    return true;
}

boolean hasSpace(long newTail) {
    if (newTail - cachedHead <= capacity) return true;
    cachedHead = getLongAcquire(memory, headOffset);   // refresh only when the cached value says full
    return newTail - cachedHead <= capacity;
}
```

**Key points**:
- **Space check before any write**: The writer never writes to ring memory it has not proven free. An earlier version wrote the sentinel first; with mixed frame sizes and a full ring that zeroed the header of an unread frame, and the reader silently skipped the rest of the lap. `RingBufferWrapTest` reproduces that case.
- **Skip published separately**: A frame that must wrap needs the reader to have consumed the gap at the end of the lap. Publishing the sentinel as its own tail update lets the reader move past it, so a frame larger than both the gap and `tailIndex` still gets placed once the reader catches up.
- **Cached head**: The writer only performs an acquire load of `head` when the cached value says the ring is full.
- **Uncommitted state**: The length is written but the tail is not advanced, so the reader cannot see the frame.

### Phase 2: Write Payload

```java
long payloadOffset = writer.offset();  // index + HEADER_SIZE, relative to writer.buffer()
writer.buffer().set(ValueLayout.JAVA_LONG, payloadOffset, value);
```

**Zero-copy**: `buffer()` is a slice of the data region (it excludes the cursors), created once at construction.

### Phase 3: Commit

```java
void commit() {
    tail = claimedNextTail;
    setLongRelease(memory, tailOffset, tail);  // publish
}
```

**Memory ordering**: `setLongRelease` ensures all prior writes (length field, payload) are visible before the new tail value. This is the synchronization point.

## Read Algorithm

```java
int read(MessageHandler handler, int limit) {
    int count = 0;
    long currentHead = head;
    long tail = cachedTail;
    try {
        while (count < limit) {
            if (currentHead >= tail) {
                tail = getLongAcquire(memory, tailOffset);  // refresh only when the cached tail is exhausted
                cachedTail = tail;
                if (currentHead >= tail) break;
            }
            int headIndex = (int) (currentHead & mask);
            int length = data.get(JAVA_INT, headIndex);
            if (length == 0) {                                // sentinel
                currentHead = (currentHead & ~mask) + capacity;
                continue;
            }
            long position = currentHead;
            currentHead += HEADER_SIZE + align(length, 8);   // advance before the callback
            handler.onMessage(data, headIndex + HEADER_SIZE, length, position);
            count++;
        }
    } finally {
        head = currentHead;
        setLongRelease(memory, headOffset, currentHead);
    }
    return count;
}
```

**Key points**:
- **Acquire read of tail**: Synchronizes with the writer's release write, so every frame below the observed tail is fully visible. A batch reuses the observed tail and only reloads it once exhausted.
- **Local head**: Read position kept in a local until the batch completes, then published once.
- **Exception safety**: The `finally` block publishes the head even if a handler throws; the frame whose handler threw counts as consumed.

## Synchronization Protocol

The correctness of SPSC communication relies on acquire/release ordering:

### Happens-Before Relationships

```
Producer Thread                 Consumer Thread
─────────────────────────────────────────────────────
1. Write length field
2. Write payload data
3. setLongRelease(tail) ────┐
                            │  happens-before
                            └──> 4. getLongAcquire(tail)
                                 5. Read length field
                                 6. Read payload data
                                 7. setLongRelease(head)
                            ┌──> 8. getLongAcquire(head)
                            │  happens-before
                            └────
```

**Guarantees**:
- Operations 1-2 are visible after operation 4 (acquire loads preceding release store)
- Consumer never sees partial writes
- Consumer never reads uncommitted frames (tail not advanced)

### Implementation via VarHandle

```java
private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

public static long getLongAcquire(MemorySegment seg, long offset) {
    return (long) LONG.getAcquire(seg, offset);
}

public static void setLongRelease(MemorySegment seg, long offset, long value) {
    LONG.setRelease(seg, offset, value);
}
```

**JMM Semantics** (JLS §17.4):
- **Release write**: Prevents reordering of prior writes past the release
- **Acquire read**: Prevents reordering of subsequent reads before the acquire
- Together: Establishes synchronization between threads

**Hardware**: On x86-64, acquire loads and release stores compile to plain MOVs (TSO already provides the ordering). On AArch64, HotSpot emits LDAR/STLR.

## Space Management

### Available Space Calculation

```java
long availableSpace = capacity - (tail - head);
```

**Invariant**: `tail - head ≤ capacity` always holds.

**Proof**:
- Initially: `head = tail = 0`, difference is 0
- Writer advances tail only if `tail + frameSize - head ≤ capacity`
- Reader advances head, reducing the difference
- Thus: `tail - head ≤ capacity` maintained

### Full vs Empty

```java
// Empty
head == tail

// Full
tail - head == capacity

// Partially filled
0 < tail - head < capacity
```

No ambiguity between empty and full (unlike some ring buffer designs that need an extra slot or flag).

## Wrapping Mechanics

### Sentinel Placement

When a frame doesn't fit at the end:

```
Before wrap (tail = 232, capacity = 256):
┌────────────────────────────────────────────────┐
│ ... existing data ... │ 24 bytes free          │
└────────────────────────────────────────────────┘
                        ↑ tailIndex = 232

Need 40-byte frame → doesn't fit
```

The sentinel is only written once the writer has confirmed the reader already consumed `[232, 256)`; the new tail (256) is published immediately.

```
After sentinel write:
┌────────────────────────────────────────────────┐
│ ... existing data ... │ [0]│ 20 bytes unused   │
└────────────────────────────────────────────────┘
                        ↑ sentinel

Virtual cursor advanced: tail = (232 & ~255) + 256 = 256
Physical index: tailIndex = 256 & 255 = 0
```

```
Frame written at index 0:
┌────────────────────────────────────────────────┐
│ [len=32][payload...]   │ ... (old data)        │
└────────────────────────────────────────────────┘
  ↑ tailIndex = 0

Virtual cursor now: tail = 256 + 40 = 296
```

**Virtual cursor arithmetic**: `(cursor & ~mask) + capacity` advances to next lap without changing physical index to 0 yet. Next modulo operation yields index 0.

### Reader Handling of Sentinel

```java
int length = memory.get(JAVA_INT, framePos);
if (length == 0) {
    // Sentinel: advance to next lap
    currentHead = (currentHead & ~mask) + capacity;
    continue;  // Skip to next iteration
}
```

Reader skips sentinel without calling handler. Next frame is at physical index 0.

## Alignment

### Why 8-Byte Alignment?

1. **ValueLayout requirement**: `JAVA_LONG`, `JAVA_DOUBLE` require 8-byte aligned offsets
2. **Performance**: Aligned access avoids crossing cache line boundaries
3. **Atomicity**: Many platforms guarantee atomic access only for aligned words
4. **Simplicity**: Single alignment value for all operations

### Alignment Enforcement

**Frame alignment**:
```java
int frameSize = HEADER_SIZE + align(payloadLength, 8);
```

Where `align(x, 8) = (x + 7) & ~7`.

**Cursor start**: `dataOffset()` returns 256, which is 8-byte aligned (256 % 8 = 0).

**Virtual cursor arithmetic**: Adding multiples of 8 preserves alignment.

**Result**: Every payload offset is 8-byte aligned:
```
payloadOffset = dataOffset (256, aligned)
                + tailIndex (sum of aligned frame sizes, aligned)
                + HEADER_SIZE (8, aligned)
              = aligned
```

## Cache Considerations

### False Sharing Prevention

```
Core 0 (Producer)                 Core 1 (Consumer)
┌─────────────────┐               ┌─────────────────┐
│   L1 Cache      │               │   L1 Cache      │
│  ┌────────┐     │               │  ┌────────┐     │
│  │  Tail  │     │               │  │  Head  │     │
│  └────────┘     │               │  └────────┘     │
└─────────────────┘               └─────────────────┘
        │                                 │
        └────────── L3 (Shared) ──────────┘
                 ┌──────────────────┐
                 │ Cache line 0:    │
                 │   Head + padding │
                 ├──────────────────┤
                 │ Cache line 2+:   │  (128B gap)
                 │   Tail + padding │
                 └──────────────────┘
```

**Without padding**: Producer writes to tail → cache line invalidation → consumer's cache line evicted → false sharing → performance degradation.

**With 128B padding**: Head and tail in separate cache lines (even with 128B prefetching). Cores can operate without invalidating each other's caches.

### On-Heap Writer/Reader State

The writer's private fields (`tail`, `claimedTail`, `claimedNextTail`, `cachedHead`) and the reader's (`head`, `cachedTail`) are written on every operation by different threads. The two objects are allocated back to back, so without padding they share a cache line (JOL: 40 + 32 bytes) and undo the off-heap cursor padding. Both classes use inheritance padding: 128 bytes of `long` fields in a superclass, the hot fields in a middle class, and 128 bytes in the subclass, because HotSpot lays out superclass fields first. `RingBufferLayoutTest` asserts the result with JOL; it already caught HotSpot packing an `int` field into the 4-byte gap after the object header, ahead of the padding.

### Spatial Locality

Ring buffer data is contiguous. Sequential reads/writes benefit from:
- Hardware prefetching
- Cache line fills (64 bytes loaded at once)
- TLB efficiency (fewer page table entries)

**Optimization**: Size frames to fit cache lines. Example: 56-byte payload + 8-byte header = 64 bytes = 1 cache line.

## Edge Cases

### Minimum Capacity

**Constraint**: Capacity must hold at least one maximum-size frame.

```java
if (frameSize > capacity) {
    throw new MessageTooLargeException(length, capacity, "...");
}
```

Minimum sensible capacity: `64` (one cache line).

### Maximum Message Size

Limited by frame size fitting in ring:

```java
maxMessageSize = capacity - HEADER_SIZE;
```

A frame of exactly `capacity` bytes can only be placed at index 0 with an empty ring, so the writer waits for the reader to drain fully first.

### Sentinel at Index 0

If a message ends exactly at capacity boundary, next message naturally starts at index 0. No sentinel needed.

```java
if (tailIndex + frameSize > capacity) {  // Strictly greater
    // Write sentinel
}
```

Equal case (`==`) does not write sentinel.

### Full Ring with Sentinel

The gap between the sentinel and the end of the lap counts as used space: the tail jumps to the next lap, so `tail - head` includes it until the reader skips past. No separate adjustment is needed.

### Overflow Protection

**64-bit cursors**: At 10B messages/sec, would take 58 years to overflow. No special handling needed in practice.

If overflow concerns exist:
```java
if (tail > Long.MAX_VALUE - frameSize) {
    // Wrap cursors back to 0 (requires coordination)
}
```

Wally does not implement this (not needed for realistic workloads).

## Performance

Measured numbers and methodology are in [Benchmarks](benchmarks.md).

## Implementation Trade-offs

### Power-of-Two Capacity

**Pros**:
- Fast modulo via AND
- No division instructions
- Compiler optimizes mask constant

**Cons**:
- Wastes capacity if natural size isn't power of 2
- Example: Need 1500 KB → must allocate 2048 KB

**Decision**: Performance benefit outweighs waste.

### 8-Byte Header

Alternative: 4-byte header (length only, no padding).

**Pros of 8-byte**:
- Payload always aligned
- No conditional alignment logic
- Simpler arithmetic

**Cons of 8-byte**:
- 4 bytes overhead per message
- Example: 1M × 32-byte messages → 4 MB wasted

**Decision**: Simplicity and alignment guarantees worth the overhead.

### Virtual Cursors

Alternative: Wrap cursors at capacity.

**Pros of virtual cursors**:
- Natural sequence numbers
- No wrap-around arithmetic
- Simpler space calculation
- Easier to reason about

**Cons**:
- 64-bit arithmetic (negligible on 64-bit CPUs)

**Decision**: Virtual cursors preferred for simplicity.

### Cached Head in Writer

**Benefit**: Reduces contention on head cursor. Writer only reads when space check fails.

**Cost**: May claim space that was recently freed (brief full condition).

## Correctness Properties

### Safety Properties

1. **No data races**: All shared memory accessed with acquire/release
2. **No lost messages**: Every committed message eventually read (if capacity sufficient)
3. **FIFO order**: Messages read in write order
4. **Exactly-once delivery**: Each message read once (reader advances atomically)
5. **No partial reads**: Consumer sees complete message (synchronization protocol)

### Liveness Properties

1. **Bounded steps**: `tryClaim`, `commit` and `read` never block or retry internally; a full or empty ring returns immediately.
2. **Writer progress**: Any frame up to `capacity` bytes is eventually placed once the reader drains enough space.

### Verification

- **`RingBufferWrapTest`**: deterministic reproduction of the sentinel-overwrite case, plus 2M random-size messages across two threads with per-byte payload checks
- **`RingBufferTest` / `EdgeCaseTest`**: protocol, wrapping, backpressure and argument validation
- **`RingBufferLayoutTest`** (JOL): at least 128 bytes of padding around the writer and reader hot fields
- **`ZeroAllocationTest`** (Epsilon GC, 128 MB heap): 50M write/read cycles without allocation
- **jcstress** (`wally-stress`): a reader that sees a non-zero length sees the full payload, with and without a wrap

## References

- [The JSR-133 Cookbook](http://gee.cs.oswego.edu/dl/jmm/cookbook.html)
- [VarHandle Javadoc](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/invoke/VarHandle.html)
- [Martin Thompson: Lock-Free Algorithms](https://mechanical-sympathy.blogspot.com/)
- [Intel 64 Memory Ordering](https://www.intel.com/content/www/us/en/developer/articles/technical/intel-sdm.html) (Vol 3A, §8.2)
