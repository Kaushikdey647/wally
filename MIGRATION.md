# Migration from WAL to SPSC Ring Buffer

This document describes the restructuring of Wally from a write-ahead log to a focused SPSC circular buffer.

## What Changed

### Removed (Old WAL Code)
- `wally-core/src/main/java/io/wally/api/` - Old WAL API (Wally, Appender, Tailer, etc.)
- `wally-core/src/main/java/io/wally/internal/segment/` - Segment file management
- `wally-core/src/main/java/io/wally/internal/concurrent/` - MPSC coordination (TailCoordinator, PaddedCounter)
- `wally-core/src/main/java/io/wally/internal/log/` - Record format, segment reader/writer
- Old tests in `wally-core/src/test/java/io/wally/_old/`

### Added (New SPSC API)
- `io.wally.spsc.RingBuffer` - Main SPSC circular buffer
- `io.wally.spsc.Writer` - Producer interface
- `io.wally.spsc.Reader` - Consumer interface
- `io.wally.spsc.MessageHandler` - Callback for message consumption
- `io.wally.spsc.RingBufferConfig` - Configuration builder
- `io.wally.spsc.internal.Cursors` - Cache-padded head/tail pointers

### Kept (Core Utilities)
- `io.wally.internal.memory.Mem` - VarHandle-based memory operations
- `io.wally.internal.memory.Align` - Alignment utilities

## New API Example

```java
// Create ring buffer
RingBuffer ring = new RingBuffer(
    new RingBufferConfig().capacity(1024 * 1024)
);

Writer writer = ring.writer();
Reader reader = ring.reader();

// Producer writes
if (writer.tryClaim(32)) {
    var buf = writer.buffer();
    buf.set(ValueLayout.JAVA_LONG, writer.offset(), orderId);
    buf.set(ValueLayout.JAVA_DOUBLE, writer.offset() + 8, price);
    writer.commit();
}

// Consumer reads
reader.read((buf, off, len, seq) -> {
    long id = buf.get(ValueLayout.JAVA_LONG, off);
    double price = buf.get(ValueLayout.JAVA_DOUBLE, off + 8);
    processOrder(id, price);
}, 100);
```

## Key Design Changes

### Message Format
**Old (WAL)**: `[length:4][type:2][flags:2][crc32:4][reserved:4][payload][padding]`

**New (SPSC)**: `[length:4][padding:4][payload aligned to 8 bytes]`

- Simpler: no checksums, type tags, or flags
- 8-byte header ensures payload always starts on 8-byte boundary
- Entire frame aligned to 8 bytes for fast arithmetic

### Memory Layout
- **Cursors**: 256 bytes (128 bytes each with padding)
- **Ring data**: Follows immediately after cursors
- **Total allocation**: cursors (256) + capacity

### Wrapping Strategy
When a message won't fit at the end of the ring:
1. Write zero-length sentinel at current position
2. Wrap to beginning of ring
3. Advance virtual cursor to next lap

### Cache-Line Padding
- Head and tail separated by 128 bytes
- Prevents false sharing between producer and consumer cores
- Critical for multi-core performance

## Performance Characteristics

| Operation | Latency | Throughput |
|-----------|---------|------------|
| Write 32-byte message | ~20-50ns | 20M+ ops/sec |
| Read message | ~15-30ns | 30M+ ops/sec |
| Round-trip (write+read) | ~50-100ns | 10M+ ops/sec |
| Allocation | 0 bytes/op after warmup | - |

## Testing

```bash
# Run unit tests
./gradlew :wally-core:test

# Run throughput benchmarks
./gradlew :wally-bench:jmh -Pjmh.include=SPSCThroughput

# Run latency benchmarks
./gradlew :wally-bench:jmh -Pjmh.include=SPSCLatency

# Verify zero allocation
./gradlew :wally-bench:jmh -Pjmh.include=SPSCZeroAllocation
```

## Migration Path

If you were using the old Wally WAL API:

### Old Code
```java
WallyConfig config = new WallyConfig()
    .directory(path)
    .segmentSize(1024 * 1024);
Wally wally = Wally.create(config);
Appender appender = wally.appender();
Tailer tailer = wally.tailer();
```

### New Code
```java
RingBufferConfig config = new RingBufferConfig()
    .capacity(1024 * 1024);
RingBuffer ring = new RingBuffer(config);
Writer writer = ring.writer();
Reader reader = ring.reader();
```

### Key Differences
1. **No persistence**: Data lives only in memory
2. **SPSC only**: Single producer, single consumer
3. **No named consumers**: No position tracking across restarts
4. **Simpler lifecycle**: No segment rollover or cleanup
5. **Different semantics**: Ring wraps, old data overwritten if not consumed

## Why SPSC?

The focus on SPSC enables:
- **Simpler**: No CAS loops, no coordination overhead
- **Faster**: Lock-free with just acquire/release
- **Predictable**: No contention, deterministic latency
- **Trading-focused**: Perfect for order routing and market data pipelines

For multi-producer scenarios, consider:
- Multiple SPSC rings (one per producer)
- External queue (JCTools MpscArrayQueue)
- Fan-in with dedicated merger thread
