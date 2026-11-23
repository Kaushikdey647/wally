package io.wally.bench;

import io.wally.spsc.MessageHandler;
import io.wally.spsc.Reader;
import io.wally.spsc.RingBuffer;
import io.wally.spsc.RingBufferConfig;
import io.wally.spsc.Writer;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/**
 * Throughput benchmark for SPSC ring buffer with trading workloads.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class SPSCThroughputBenchmark {

    @Param({"65536", "262144", "1048576"})
    private int capacity;

    private RingBuffer ring;
    private Writer writer;
    private Reader reader;
    private final ConsumingHandler handler = new ConsumingHandler();

    @Setup
    public void setup() {
        ring = new RingBuffer(new RingBufferConfig().capacity(capacity));
        writer = ring.writer();
        reader = ring.reader();
    }

    /**
     * Order message: 32 bytes typical
     * [orderId:8][symbol:8][price:8][quantity:4][side:1][padding:3]
     */
    @Benchmark
    public void orderFlow() {
        if (writer.tryClaim(32)) {
            var buf = writer.buffer();
            long off = writer.offset();
            buf.set(ValueLayout.JAVA_LONG, off, 123456789L);       // orderId
            buf.set(ValueLayout.JAVA_LONG, off + 8, 0xABCDL);      // symbol
            buf.set(ValueLayout.JAVA_DOUBLE, off + 16, 142.50);     // price
            buf.set(ValueLayout.JAVA_INT, off + 24, 100);           // quantity
            buf.set(ValueLayout.JAVA_BYTE, off + 28, (byte) 'B');  // side
            writer.commit();
        }
        reader.read(handler, 1);
    }

    /**
     * Market data tick: 24 bytes
     * [symbol:8][timestamp:8][price:8]
     */
    @Benchmark
    public void marketDataTick() {
        if (writer.tryClaim(24)) {
            var buf = writer.buffer();
            long off = writer.offset();
            buf.set(ValueLayout.JAVA_LONG, off, 0xABCDL);              // symbol
            buf.set(ValueLayout.JAVA_LONG, off + 8, System.nanoTime()); // timestamp
            buf.set(ValueLayout.JAVA_DOUBLE, off + 16, 142.50);         // price
            writer.commit();
        }
        reader.read(handler, 1);
    }

    /**
     * Batch processing: write 10, read 10
     */
    @Benchmark
    public void batchProcessing() {
        for (int i = 0; i < 10; i++) {
            if (writer.tryClaim(32)) {
                var buf = writer.buffer();
                long off = writer.offset();
                buf.set(ValueLayout.JAVA_LONG, off, i);
                writer.commit();
            }
        }
        reader.read(handler, 10);
    }

    /**
     * Variable-size messages (8-128 bytes)
     */
    @Benchmark
    public void variableSize() {
        int size = (int) (System.nanoTime() & 0x78) + 8;  // 8, 16, 24...128
        if (writer.tryClaim(size)) {
            writer.commit();
        }
        reader.read(handler, 1);
    }

    private static final class ConsumingHandler implements MessageHandler {
        @Override
        public void onMessage(java.lang.foreign.MemorySegment buffer, long offset, int length, long sequence) {
            // Consume to prevent DCE
        }
    }
}
