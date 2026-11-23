# Wally

A zero-allocation SPSC (Single Producer Single Consumer) circular buffer for the JVM, optimized for quantitative trading systems where microseconds matter.

Built on Java's Foreign Function & Memory API (FFM) for direct off-heap access with no GC pressure.

## Why Wally?

Trading systems move fast. Order routing, market data processing, and risk checks happen in microseconds. Traditional Java queues allocate objects per message, triggering GC pauses that can cost you trades.

Wally solves this:

- **Zero allocation**: No objects created after warmup
- **Lock-free**: SPSC with acquire/release memory ordering
- **Cache-optimized**: Padded cursors prevent false sharing
- **Direct writes**: Claim space, write directly, commit
- **Power-of-two sizing**: Fast modulo via bit masking

## Quick Start

```java
import io.wally.spsc.*;
import java.lang.foreign.ValueLayout;

// Create a 1 MB ring buffer
RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024 * 1024));
Writer writer = ring.writer();
Reader reader = ring.reader();

// Producer thread: write an order (32 bytes)
if (writer.tryClaim(32)) {
    var buf = writer.buffer();
    long off = writer.offset();
    buf.set(ValueLayout.JAVA_LONG, off, orderId);
    buf.set(ValueLayout.JAVA_DOUBLE, off + 8, price);
    buf.set(ValueLayout.JAVA_INT, off + 16, quantity);
    writer.commit();
}

// Consumer thread: read orders
reader.read((buf, off, len, seq) -> {
    long orderId = buf.get(ValueLayout.JAVA_LONG, off);
    double price = buf.get(ValueLayout.JAVA_DOUBLE, off + 8);
    int quantity = buf.get(ValueLayout.JAVA_INT, off + 16);
    // Process order...
}, 100);
```

## Requirements

- **Java 22+** (FFM API finalized)
- **JVM flags**: `--enable-preview` (if using preview features)
- **Platform**: x86-64 or AArch64 (little-endian)

## Building

```bash
./gradlew build
```

## Running Benchmarks

```bash
# Throughput (ops/sec)
./gradlew :wally-bench:jmh -Pjmh.include=SPSCThroughput

# Latency (nanoseconds)
./gradlew :wally-bench:jmh -Pjmh.include=SPSCLatency

# Zero allocation test
./gradlew :wally-bench:jmh -Pjmh.include=SPSCZeroAllocation
```

## How It Works

**Message format**: `[4-byte length][payload]`

**Writer** (producer):
1. `tryClaim(length)` - reserves space at tail cursor
2. Write payload directly into ring buffer
3. `commit()` - publishes tail with release semantics

**Reader** (consumer):
1. Reads tail cursor with acquire semantics
2. If `tail > head`, messages available
3. Reads length, delivers message via callback
4. Advances head cursor with release semantics

**Wrapping**: When a message won't fit at the end, a zero-length sentinel is written and the writer wraps to the beginning.

**Cache-line padding**: Head and tail cursors are separated by 128 bytes to prevent false sharing between producer and consumer cores.

## Trading Use Cases

- **Order routing**: Pass orders between strategy and execution threads
- **Market data**: Stream ticks from decoder to strategy
- **Risk checks**: Queue trades for async risk validation
- **Event replay**: Buffer events for backtesting pipelines

## Design Philosophy

Wally does **one thing well**: SPSC circular buffering with zero allocation.

It doesn't do:
- ❌ File persistence
- ❌ Multi-producer (MPSC/MPMC)
- ❌ Transactions or batching
- ❌ Network transport

If you need a full queue system, consider Chronicle Queue or Aeron. If you need fast in-memory thread coordination with no GC, Wally is your tool.

## License

MIT
