# Quick Start Guide

Get started with Wally SPSC ring buffer in 5 minutes. This guide assumes Java 22+ and basic knowledge of off-heap memory via FFM API.

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.wally:wally-core:1.0.0")
}
```

## Basic Usage

### 1. Create a Ring Buffer

```java
import io.wally.spsc.*;

RingBuffer ring = new RingBuffer(
    new RingBufferConfig()
        .capacity(1024 * 1024)  // 1 MB, must be power of 2
        .alignment(64)          // Cache-line alignment
);

Writer writer = ring.writer();  // Use from producer thread only
Reader reader = ring.reader();  // Use from consumer thread only
```

### 2. Write Messages (Producer)

**Option A: Zero-copy (fastest)**

```java
import java.lang.foreign.ValueLayout;

if (writer.tryClaim(32)) {
    var buf = writer.buffer();
    long off = writer.offset();
    
    // Write directly into ring
    buf.set(ValueLayout.JAVA_LONG, off, orderId);
    buf.set(ValueLayout.JAVA_DOUBLE, off + 8, price);
    buf.set(ValueLayout.JAVA_INT, off + 16, quantity);
    buf.set(ValueLayout.JAVA_BYTE, off + 20, side);
    
    writer.commit();  // Make visible to consumer
}
```

**Option B: Copy from byte array**

```java
byte[] order = serializeOrder(...);
if (!writer.write(order, 0, order.length)) {
    // Ring full - consumer needs to drain
}
```

### 3. Read Messages (Consumer)

```java
reader.read((buf, off, len, seq) -> {
    // Parse message
    long orderId = buf.get(ValueLayout.JAVA_LONG, off);
    double price = buf.get(ValueLayout.JAVA_DOUBLE, off + 8);
    int quantity = buf.get(ValueLayout.JAVA_INT, off + 16);
    
    // Process order
    executeOrder(orderId, price, quantity);
}, 100);  // Read up to 100 messages
```

## Complete Example: Order Flow

```java
package com.example.trading;

import io.wally.spsc.*;
import java.lang.foreign.ValueLayout;

public class OrderProcessor {
    
    private final RingBuffer ring;
    private final Writer writer;
    private final Reader reader;
    
    public OrderProcessor() {
        this.ring = new RingBuffer(
            new RingBufferConfig().capacity(64 * 1024)
        );
        this.writer = ring.writer();
        this.reader = ring.reader();
    }
    
    // Producer thread
    public boolean sendOrder(long orderId, double price, int quantity, byte side) {
        if (!writer.tryClaim(21)) {
            return false;  // Ring full
        }
        
        var buf = writer.buffer();
        long off = writer.offset();
        buf.set(ValueLayout.JAVA_LONG, off, orderId);
        buf.set(ValueLayout.JAVA_DOUBLE, off + 8, price);
        buf.set(ValueLayout.JAVA_INT, off + 16, quantity);
        buf.set(ValueLayout.JAVA_BYTE, off + 20, side);
        
        writer.commit();
        return true;
    }
    
    // Consumer thread
    public void processOrders() {
        reader.read((buf, off, len, seq) -> {
            long orderId = buf.get(ValueLayout.JAVA_LONG, off);
            double price = buf.get(ValueLayout.JAVA_DOUBLE, off + 8);
            int quantity = buf.get(ValueLayout.JAVA_INT, off + 16);
            byte side = buf.get(ValueLayout.JAVA_BYTE, off + 20);
            
            System.out.printf("Order %d: %s %d @ %.2f%n",
                orderId, side == 'B' ? "BUY" : "SELL", quantity, price);
        }, 1000);
    }
    
    public static void main(String[] args) throws InterruptedException {
        OrderProcessor processor = new OrderProcessor();
        
        // Start consumer thread
        Thread consumer = new Thread(() -> {
            while (!Thread.interrupted()) {
                processor.processOrders();
            }
        });
        consumer.start();
        
        // Producer thread (main)
        for (int i = 0; i < 100; i++) {
            while (!processor.sendOrder(
                i, 100.0 + i, 100, (byte) 'B'
            )) {
                Thread.onSpinWait();  // Busy-spin until space available
            }
        }
        
        Thread.sleep(100);
        consumer.interrupt();
        consumer.join();
    }
}
```

## Common Patterns

### Zero-Allocation Handler

```java
class ReusableHandler implements MessageHandler {
    private long orderCount;
    
    @Override
    public void onMessage(MemorySegment buf, long off, int len, long seq) {
        orderCount++;
        // Process without allocating
    }
}

ReusableHandler handler = new ReusableHandler();
reader.read(handler, 100);  // No lambda allocation
```

### Backpressure Handling

```java
// Option 1: Spin-wait
while (!writer.tryClaim(32)) {
    Thread.onSpinWait();
}

// Option 2: Drop message
if (!writer.tryClaim(32)) {
    droppedMessages.increment();
    return;
}

// Option 3: Batch and retry
List<Message> pending = new ArrayList<>();
for (Message msg : messages) {
    if (!write(msg)) {
        pending.add(msg);
    }
}
```

### Batch Processing

```java
// Producer: write multiple
for (Order order : orders) {
    if (!writeOrder(order)) break;  // Stop when full
}

// Consumer: drain all
int read = reader.read(handler, Integer.MAX_VALUE);
```

## Performance Tips

1. **Reuse handlers**: Don't use capturing lambdas in hot path
2. **Power-of-two capacity**: Enables fast modulo via masking
3. **Thread affinity**: Pin producer/consumer to separate cores
4. **Warmup**: Run 10K ops before measuring latency
5. **Batch reads**: Read multiple messages per call

## Next Steps

- [How It Works](how-it-works.md) - Deep dive into implementation
- [Benchmarks](benchmarks.md) - Performance measurements
- [Trading Examples](examples.md) - Real-world use cases
