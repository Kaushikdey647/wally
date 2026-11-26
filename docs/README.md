# Wally Documentation

## Overview

Wally is a high-performance SPSC (Single Producer Single Consumer) circular buffer for the JVM, designed for ultra-low-latency inter-thread communication in quantitative trading and high-frequency systems.

## Documentation Index

### Getting Started
- **[Quick Start Guide](quickstart.md)** - Get up and running in 5 minutes
- **[Main README](../README.md)** - Full API reference and usage examples

### Technical Deep Dive
- **[Implementation Details](implementation.md)** - Memory layout, algorithms, synchronization protocol
- **[Glossary](glossary.md)** - Terminology reference for all concepts

### Project Information
- **[Migration Guide](../MIGRATION.md)** - Transitioning from WAL to SPSC
- **[Cleanup Status](../CLEANUP_STATUS.md)** - Repository restructure details

## Quick Links

### Core API
- `RingBuffer` - Main SPSC ring buffer implementation
- `Writer` - Producer interface for writing messages
- `Reader` - Consumer interface for reading messages  
- `MessageHandler` - Callback for message processing
- `RingBufferConfig` - Configuration builder

### Key Concepts
- Lock-free SPSC communication
- Zero-allocation operation
- Acquire/release memory ordering
- Cache-line padding (false sharing prevention)
- Virtual cursor arithmetic
- 8-byte frame alignment

## Performance Summary

| Metric | Value |
|--------|-------|
| Write latency (p50) | ~25ns |
| Read latency (p50) | ~20ns |  
| Round-trip (p50) | ~50ns |
| Throughput | 20M+ msgs/sec |
| Allocation | 0 bytes/op |

## Quick Example

```java
RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024 * 1024));
Writer writer = ring.writer();
Reader reader = ring.reader();

// Producer
if (writer.tryClaim(32)) {
    writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), value);
    writer.commit();
}

// Consumer
reader.read((buf, off, len, seq) -> {
    long value = buf.get(ValueLayout.JAVA_LONG, off);
    process(value);
}, 100);
```

## Requirements

- Java 22+ (FFM API)
- x86-64 or AArch64
- HotSpot/GraalVM

## License

MIT
