package io.wally.bench;

import io.wally.spsc.MessageHandler;
import io.wally.spsc.Reader;
import io.wally.spsc.RingBuffer;
import io.wally.spsc.RingBufferConfig;
import io.wally.spsc.Writer;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Verifies zero allocation for SPSC operations.
 */
@State(Scope.Thread)
public class SPSCZeroAllocationTest {

    private static final int ITERATIONS = 1_000_000;
    private static final com.sun.management.ThreadMXBean threadMXBean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private RingBuffer ring;
    private Writer writer;
    private Reader reader;
    private final ReusableHandler handler = new ReusableHandler();

    @Setup
    public void setup() {
        ring = new RingBuffer(new RingBufferConfig().capacity(1024 * 1024));
        writer = ring.writer();
        reader = ring.reader();

        // Warmup to initialize all code paths
        for (int i = 0; i < 10000; i++) {
            if (writer.tryClaim(32)) {
                writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), i);
                writer.commit();
            }
            reader.read(handler, 1);
        }
    }

    @Benchmark
    public void measureAllocation() {
        long before = threadMXBean.getCurrentThreadAllocatedBytes();

        for (int i = 0; i < ITERATIONS; i++) {
            if (writer.tryClaim(32)) {
                var buf = writer.buffer();
                long off = writer.offset();
                buf.set(ValueLayout.JAVA_LONG, off, i);
                buf.set(ValueLayout.JAVA_LONG, off + 8, System.nanoTime());
                buf.set(ValueLayout.JAVA_DOUBLE, off + 16, 142.50);
                writer.commit();
            }
            reader.read(handler, 1);
        }

        long after = threadMXBean.getCurrentThreadAllocatedBytes();
        long allocated = after - before;

        // Should be under 1 KB for 1M ops (practically zero)
        if (allocated > 1024) {
            throw new AssertionError(
                String.format("Allocated %d bytes for %d operations (%.3f bytes/op)",
                    allocated, ITERATIONS, allocated / (double) ITERATIONS)
            );
        }

        System.out.printf("✓ Zero allocation verified: %d bytes for %d ops (%.6f bytes/op)%n",
            allocated, ITERATIONS, allocated / (double) ITERATIONS);
    }

    private static final class ReusableHandler implements MessageHandler {
        long sum;

        @Override
        public void onMessage(MemorySegment buffer, long offset, int length, long sequence) {
            sum += buffer.get(ValueLayout.JAVA_LONG, offset);
        }
    }
}
