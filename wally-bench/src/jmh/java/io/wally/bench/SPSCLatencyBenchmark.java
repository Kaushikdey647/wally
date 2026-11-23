package io.wally.bench;

import io.wally.spsc.MessageHandler;
import io.wally.spsc.Reader;
import io.wally.spsc.RingBuffer;
import io.wally.spsc.RingBufferConfig;
import io.wally.spsc.Writer;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/**
 * Single-pass latency measurement for critical path analysis.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
@Fork(1)
@State(Scope.Thread)
public class SPSCLatencyBenchmark {

    private RingBuffer ring;
    private Writer writer;
    private Reader reader;
    private final TimingHandler handler = new TimingHandler();

    @Setup
    public void setup() {
        ring = new RingBuffer(new RingBufferConfig().capacity(1024 * 1024));
        writer = ring.writer();
        reader = ring.reader();
    }

    /**
     * Measure write-then-read latency for a single order.
     */
    @Benchmark
    public long orderRoundTrip() {
        long t0 = System.nanoTime();

        if (writer.tryClaim(32)) {
            var buf = writer.buffer();
            long off = writer.offset();
            buf.set(ValueLayout.JAVA_LONG, off, t0);  // timestamp
            buf.set(ValueLayout.JAVA_LONG, off + 8, 123456L);
            buf.set(ValueLayout.JAVA_DOUBLE, off + 16, 142.50);
            buf.set(ValueLayout.JAVA_INT, off + 24, 100);
            writer.commit();
        }

        reader.read(handler, 1);
        return handler.lastTimestamp;
    }

    /**
     * Measure just the write (claim + commit) latency.
     */
    @Benchmark
    public void writeOnly() {
        if (writer.tryClaim(32)) {
            var buf = writer.buffer();
            long off = writer.offset();
            buf.set(ValueLayout.JAVA_LONG, off, System.nanoTime());
            writer.commit();
        }
    }

    /**
     * Measure just the read latency (pre-populated buffer).
     */
    @Benchmark
    public void readOnly() {
        // Pre-populate one message
        if (writer.tryClaim(32)) {
            writer.commit();
        }
        reader.read(handler, 1);
    }

    private static final class TimingHandler implements MessageHandler {
        long lastTimestamp;

        @Override
        public void onMessage(MemorySegment buffer, long offset, int length, long sequence) {
            lastTimestamp = buffer.get(ValueLayout.JAVA_LONG, offset);
        }
    }
}
