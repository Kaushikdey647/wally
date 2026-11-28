# Wally

A high-performance, lock-free SPSC (Single Producer Single Consumer) circular buffer for the JVM, designed for ultra-low-latency inter-thread communication in quantitative trading and high-frequency systems.

## Overview

Wally provides a zero-allocation, cache-optimized ring buffer implementation using Java's Foreign Function & Memory (FFM) API for direct off-heap memory access. Measured numbers, with hardware and JDK, are in [Performance](#performance).

**Key characteristics:**
- **Lock-free**: Uses acquire/release memory ordering, no CAS loops or locks
- **Zero allocation**: No objects created per message after warmup
- **Cache-optimized**: 128-byte padding around the off-heap cursors and the on-heap writer/reader state prevents false sharing
- **Power-of-two sizing**: Fast index calculation via bit masking
- **Bounded**: Fixed capacity with explicit backpressure semantics

## Requirements

- **Java 22+** (FFM API finalized in JEP 454). Built with JDK 25 using `--release 22`.
- **Platform**: x86-64 or AArch64 (little-endian architectures only)
- **JVM**: HotSpot, GraalVM, or any JVM with FFM support

## Installation

Wally is not published to any repository yet; the current version is `0.1.0-SNAPSHOT`. Build the jar:

```bash
./gradlew :wally-core:jar   # wally-core/build/libs/wally-core-0.1.0-SNAPSHOT.jar
```

Or consume it from another Gradle build with a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../wally")

// build.gradle.kts
dependencies {
    implementation("io.wally:wally-core:0.1.0-SNAPSHOT")
}
```

## Quick Start

```java
import io.wally.spsc.*;
import java.lang.foreign.ValueLayout;

// Create a 1 MB ring buffer (must be power of 2)
RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024 * 1024));
Writer writer = ring.writer();
Reader reader = ring.reader();

// Producer thread: claim space, write, commit
if (writer.tryClaim(32)) {
    var buf = writer.buffer();
    long off = writer.offset();
    
    buf.set(ValueLayout.JAVA_LONG, off, 123456789L);      // order ID
    buf.set(ValueLayout.JAVA_DOUBLE, off + 8, 142.50);    // price
    buf.set(ValueLayout.JAVA_INT, off + 16, 1000);        // quantity
    
    writer.commit();
}

// Consumer thread: read with callback
reader.read((buf, off, len, seq) -> {
    long orderId = buf.get(ValueLayout.JAVA_LONG, off);
    double price = buf.get(ValueLayout.JAVA_DOUBLE, off + 8);
    int quantity = buf.get(ValueLayout.JAVA_INT, off + 16);
    
    processOrder(orderId, price, quantity);
}, 100);  // Read up to 100 messages
```

## Architecture

### Memory Layout

```
┌─────────────────────────────────────────────────────────────┐
│ Cursor Metadata (256 bytes)                                 │
│ ┌─────────────────────┐  ┌─────────────────────┐          │
│ │ Head (8B) + pad 120B│  │ Tail (8B) + pad 120B│          │
│ └─────────────────────┘  └─────────────────────┘          │
├─────────────────────────────────────────────────────────────┤
│ Ring Buffer Data (capacity bytes)                           │
│ ┌────────┬────────────────┬────────┬────────────────┬      │
│ │ Frame 0│    Payload 0   │ Frame 1│    Payload 1   │ ...  │
│ └────────┴────────────────┴────────┴────────────────┴      │
└─────────────────────────────────────────────────────────────┘
```

**Cursor separation**: Head and tail cursors are separated by 128 bytes to prevent false sharing between producer and consumer cores. This padding exceeds typical cache line sizes (64 bytes) to accommodate Intel's Adjacent Cache Line Prefetch.

### Frame Format

Each message is stored in an 8-byte aligned frame:

```
Byte:  0        4        8                                N
      ┌─────────┬────────┬────────────────────────────────┐
      │ Length  │ Pad(4) │  Payload (8-byte aligned)      │
      │ (4B)    │        │                                │
      └─────────┴────────┴────────────────────────────────┘
      ◄─── Header (8B) ──►◄─── length bytes, padded ────►
```

**Frame alignment**: Total frame size (header + payload) is always a multiple of 8 bytes. This ensures:
1. Payload always starts on an 8-byte boundary (required for `ValueLayout` operations)
2. Next frame always starts on an 8-byte boundary
3. Fast pointer arithmetic without alignment checks

**Length field**: Stores the actual user payload length (not the padded frame size). Reader uses this to deliver the exact message size.

### Ring Wrapping

When a message doesn't fit at the end of the ring:

```
┌──────────────────────────────────────────────────┐
│ ... │ Message N │ Sentinel │                      │
│                   (len=0)   ◄──── unused ────────►│
└──────────────────────────────────────────────────┘
                                ↓ wrap
┌──────────────────────────────────────────────────┐
│ Message N+1 │ ...                                 │
└──────────────────────────────────────────────────┘
```

1. Writer checks that the gap from the current position to the end of the ring is free (the reader has consumed it). If not, `tryClaim` returns `false` and writes nothing.
2. Writer writes a **sentinel** (length 0) at the current position and publishes the tail at the next lap, `(cursor & ~mask) + capacity`.
3. Writer checks space for the frame at ring index 0 and places it there.
4. Reader encounters the sentinel and skips to index 0 of the next lap.

The order matters: writing the sentinel before the space check could overwrite the length header of an unread frame when the ring is full, and the reader would skip the rest of that lap. Publishing the skip separately lets a frame that is larger than the remaining gap be placed once the reader catches up.

**Virtual cursors**: Both head and tail are monotonically increasing 64-bit counters. Ring index is computed as `cursor & mask`. This avoids complex wrapping arithmetic and provides a natural sequence number.

## API Documentation

### RingBuffer

Main entry point. Creates an off-heap ring buffer and provides access to producer/consumer interfaces.

```java
public final class RingBuffer {
    public RingBuffer(RingBufferConfig config)
    public Writer writer()
    public Reader reader()
    public int capacity()
}
```

**Thread safety**: 
- `writer()` must be called from exactly one thread (the producer)
- `reader()` must be called from exactly one thread (the consumer)
- Producer and consumer may be different threads
- Returned `Writer`/`Reader` instances are not thread-safe and must not be shared

### Writer

Producer interface for writing messages into the ring.

```java
public interface Writer {
    boolean tryClaim(int length)      // Reserve space
    MemorySegment buffer()            // Data region (excludes cursors)
    long offset()                     // Payload offset within buffer()
    int length()                      // Get claimed length
    void commit()                     // Publish message
    
    boolean write(byte[] src, int srcPos, int length)
    boolean write(MemorySegment src, long srcOffset, int length)
}
```

**Write patterns**:

1. **Zero-copy** (fastest):
```java
if (writer.tryClaim(32)) {
    // Write directly into ring buffer
    writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), value);
    writer.commit();
}
```

2. **Copy from byte array**:
```java
byte[] data = ...;
writer.write(data, 0, data.length);  // Returns false if no space
```

3. **Copy from MemorySegment**:
```java
MemorySegment src = ...;
writer.write(src, 0, length);
```

**Backpressure**: `tryClaim()` returns `false` when the ring is full. The writer must handle this:
- **Spin-wait**: `while (!writer.tryClaim(len)) Thread.onSpinWait();`
- **Drop message**: Log/metric and continue
- **Apply backpressure**: Signal upstream to slow down

**Guarantees**:
- If `tryClaim(n)` returns `true`, space is reserved until `commit()`
- After `commit()`, message is visible to reader with release semantics
- Uncommitted claims are not visible: the reader only reads frames below the published tail, and `commit()` is the only thing that publishes it
- `length` must be positive (0 is the wrap sentinel); `write(...)` and `tryClaim(...)` both throw `IllegalArgumentException` otherwise

### Reader

Consumer interface for reading messages from the ring.

```java
public interface Reader {
    int read(MessageHandler handler, int limit)
    long available()   // committed bytes not yet read (headers, padding and wrap gaps included)
    long sequence()    // byte position of the next frame
}
```

**Read behavior**:
- Reads up to `limit` messages (or until ring is empty)
- Calls `handler.onMessage()` for each message
- Advances read position even if handler throws
- Returns actual number of messages read

**Guarantees**:
- Messages delivered in FIFO order
- Each message delivered exactly once
- A message counts as consumed once its handler is called, even if the handler throws; the head is published when `read` returns

### MessageHandler

Callback interface for message consumption.

```java
@FunctionalInterface
public interface MessageHandler {
    void onMessage(MemorySegment buffer, long offset, int length, long sequence);
}
```

**Parameters**:
- `buffer`: Ring data region (valid only during callback)
- `offset`: Byte offset within `buffer` where payload starts
- `length`: Actual payload length (not padded frame size)
- `sequence`: Byte position of the frame; monotonic from 0, not a contiguous message counter

**Zero-allocation pattern**:
```java
class ReusableHandler implements MessageHandler {
    public void onMessage(MemorySegment buf, long off, int len, long seq) {
        // Process without allocating
    }
}

ReusableHandler handler = new ReusableHandler();
reader.read(handler, 100);  // No lambda capture
```

**Warning**: The buffer is only valid during the callback. Copy any data that must outlive the callback.

### RingBufferConfig

Configuration builder for ring buffer creation.

```java
public final class RingBufferConfig {
    public RingBufferConfig capacity(int bytes)       // Must be power of 2
    public RingBufferConfig arena(Arena arena)        // Memory arena
}
```

**Defaults**:
- Capacity: 1 MB
- Arena: `Arena.ofAuto()` (GC cleanup)

**Capacity selection**: Choose based on:
- Message size: Capacity ≥ `(header + max_message_size) × burst_size`
- Latency budget: Larger rings tolerate longer consumer pauses
- Cache footprint: Smaller rings stay in L3 cache

**Arena lifecycle**:
- `Arena.ofAuto()`: Ring memory freed when buffer is GC'd (default)
- `Arena.ofConfined()`: Explicit cleanup with `arena.close()`
- `Arena.ofShared()`: Thread-safe cleanup (not needed for SPSC)

## Memory Ordering

Wally uses acquire/release semantics for correctness without locks:

```
Producer                              Consumer
─────────────────────────────────────────────────────────────
1. Write payload data
2. setLongRelease(tail, newTail)  →   3. getLongAcquire(tail)
                                      4. Read payload data
                                      5. setLongRelease(head, newHead)
                                  ←   6. getLongAcquire(head)
```

**Guarantees**:
- **Release write** (step 2): All prior writes visible to any thread that observes the new tail
- **Acquire read** (step 3): All writes before the release are now visible
- Consumer never sees partial updates
- No memory fences needed beyond acquire/release

**Implementation**: Uses `VarHandle` with acquire/release access modes:
```java
Mem.setLongRelease(memory, offset, value);  // Producer publishes
Mem.getLongAcquire(memory, offset);         // Consumer observes
```

## Performance

Measured 1P1C (one producer thread, one consumer thread) on an Apple M4 Pro, macOS 26.4.1 without core pinning, OpenJDK 25.0.5 (Temurin). Raw outputs are in [`wally-bench/results/`](wally-bench/results/); methodology is in [docs/benchmarks.md](docs/benchmarks.md).

**Throughput** (JMH `@Group`, one message per call, 3 forks):

| Queue | Throughput | ns/msg | Allocation |
|---|---|---|---|
| Wally (32-byte payload) | 73.5M msgs/s | 13.6 | ≈ 0 B/op |
| JCTools `SpscArrayQueue` (object reference) | 399.4M msgs/s | 2.5 | ≈ 0 B/op |
| `ArrayBlockingQueue` (object reference) | 19.7M msgs/s | 50.7 | 2.8 B/op |

**One-way latency** at a fixed 1M msgs/s, stamped with intended send time (HdrHistogram, 20M samples):

| Queue | p50 | p99 | p99.9 |
|---|---|---|---|
| Wally | 83 ns | 458 ns | 24.2 µs |
| JCTools `SpscArrayQueue` | 84 ns | 334 ns | 13.1 µs |

On this machine `System.nanoTime()` has about 42 ns resolution, and tails above p99 are dominated by the unpinned OS scheduler. JCTools is faster per message because it signals through the slot rather than a shared cursor and passes a reference instead of copying a payload; see [docs/benchmarks.md](docs/benchmarks.md#throughput).

**Allocation**: zero per message after warmup, enforced by `ZeroAllocationTest` (50M cycles under Epsilon GC, 128 MB heap) in `./gradlew check`.

## Usage Patterns

### Trading Order Flow

```java
public class OrderRouter {
    private final RingBuffer ring;
    private final Writer writer;
    
    // Strategy thread calls this
    public boolean submitOrder(long orderId, String symbol, double price, int qty, byte side) {
        if (!writer.tryClaim(37)) {
            metrics.increment("orders.dropped");
            return false;
        }
        
        var buf = writer.buffer();
        long off = writer.offset();
        
        buf.set(ValueLayout.JAVA_LONG, off, orderId);
        buf.set(ValueLayout.JAVA_LONG, off + 8, symbolToLong(symbol));
        buf.set(ValueLayout.JAVA_DOUBLE, off + 16, price);
        buf.set(ValueLayout.JAVA_INT, off + 24, qty);
        buf.set(ValueLayout.JAVA_BYTE, off + 28, side);
        
        writer.commit();
        return true;
    }
}
```

### Market Data Pipeline

```java
public class MarketDataDecoder implements MessageHandler {
    private final OrderBook book;
    
    // Decoder thread feeds ring, strategy consumes
    public void onTick(byte[] raw, int offset, int length) {
        writer.write(raw, offset, length);
    }
    
    @Override
    public void onMessage(MemorySegment buf, long off, int len, long seq) {
        // Decode and update order book without allocating
        byte msgType = buf.get(ValueLayout.JAVA_BYTE, off);
        if (msgType == TYPE_QUOTE) {
            long symbol = buf.get(ValueLayout.JAVA_LONG, off + 1);
            double bid = buf.get(ValueLayout.JAVA_DOUBLE, off + 9);
            double ask = buf.get(ValueLayout.JAVA_DOUBLE, off + 17);
            book.update(symbol, bid, ask);
        }
    }
}
```

### Batch Processing

```java
// Producer: write burst
for (Trade trade : trades) {
    while (!writeTrade(trade)) {
        Thread.onSpinWait();  // Backpressure
    }
}

// Consumer: drain all available
int processed = reader.read(handler, Integer.MAX_VALUE);
```

### Error Handling

```java
class ResilientHandler implements MessageHandler {
    @Override
    public void onMessage(MemorySegment buf, long off, int len, long seq) {
        try {
            processMessage(buf, off, len);
        } catch (Exception e) {
            log.error("Failed to process message seq={}", seq, e);
            // Reader will still advance, message is skipped
        }
    }
}
```

## Performance Tuning

### CPU Pinning

Pin producer and consumer to separate cores (preferably on same NUMA node):

```bash
# Linux: taskset
taskset -c 4 java -cp ... ProducerMain &
taskset -c 5 java -cp ... ConsumerMain &

```

### JVM Flags

```bash
java \
  -XX:+UseZGC \                      # Low-latency GC
  -XX:+AlwaysPreTouch \              # Pre-fault memory
  -XX:+DisableExplicitGC \           # Prevent System.gc()
  -XX:GuaranteedSafepointInterval=300000 \  # Reduce safepoints
  -Xms16g -Xmx16g \                  # Fixed heap
  -cp ...
```

### Capacity Sizing

```java
int messageSize = 32;                    // Average payload
int headerSize = 8;                      // Frame header
int frameSize = headerSize + align(messageSize, 8);
int burstSize = 1000;                    // Max burst before consumer drains
int capacity = nextPowerOfTwo(frameSize * burstSize * 2);  // 2x safety margin
```

## Benchmarking

```bash
# 1P1C throughput (Wally, JCTools SpscArrayQueue, ArrayBlockingQueue) with allocation profiling
./gradlew :wally-bench:jmh -Pjmh.profilers=gc        # -> wally-bench/results/jmh-throughput.json

# 1P1C one-way latency at a fixed offered rate (HdrHistogram, coordinated-omission safe)
./gradlew :wally-bench:latency -Prate=1000000        # -> wally-bench/results/latency-*.hgrm

# Zero allocation: 50M write/read cycles under Epsilon GC with a 128 MB heap (part of check)
./gradlew :wally-core:epsilonTest

# Memory ordering (jcstress)
./gradlew :wally-stress:jcstress -Pjcstress.args="-m default"
```

## Troubleshooting

### "Target offset X is incompatible with alignment constraint"

**Cause**: Writing non-aligned data types at non-aligned offsets.

**Solution**: Ensure payload offsets are multiples of the data type size:
```java
buf.set(ValueLayout.JAVA_LONG, off, value);      // off must be 8-byte aligned
buf.set(ValueLayout.JAVA_INT, off + 8, value);   // off+8 is 8-byte aligned
```

Wally guarantees `writer.offset()` is always 8-byte aligned.

### Ring fills up / "tryClaim returns false"

**Causes**:
- Consumer too slow
- Burst exceeds ring capacity
- Consumer thread not running

**Solutions**:
- Increase capacity
- Add monitoring: `reader.available()`
- Implement backpressure

### High latency / poor throughput

**Check**:
1. CPU pinning: `taskset -c X` or thread affinity
2. NUMA locality: Producer/consumer on same socket
3. Compiler flags: `-XX:CompileThreshold=1500`
4. Memory prefetching: Try different capacity sizes
5. False sharing: Ensure no other data structures share cache lines

## Limitations

- **SPSC only**: One producer, one consumer. For MPSC/MPMC, use JCTools or Disruptor.
- **Bounded**: Fixed capacity. No dynamic resizing.
- **In-memory only**: No persistence. For durable queues, see Chronicle Queue.
- **Little-endian**: x86-64, AArch64. No big-endian support.
- **No transactions**: Messages visible immediately after commit.

## License

MIT License - see LICENSE file for details.

## References

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [Java Memory Model](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4)
- [Mechanical Sympathy: Lock-Free Queues](https://mechanical-sympathy.blogspot.com/)
- [Disruptor Technical Paper](https://lmax-exchange.github.io/disruptor/files/Disruptor-1.0.pdf)
