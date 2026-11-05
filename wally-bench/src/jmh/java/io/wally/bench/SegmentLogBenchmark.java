package io.wally.bench;

import io.wally.api.RecordHandler;
import io.wally.internal.log.SegmentReader;
import io.wally.internal.log.SegmentWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Single-thread append + read cost per record (M0 baseline). Cross-thread 1P1C/NP1C benchmarks come
 * with M2. The {@code ArrayBlockingQueue} baseline allocates a message per op, as typical heap queues do.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SegmentLogBenchmark {

    private static final long START = 64;

    @Param({"32", "256", "4096"})
    int payload;

    private MemorySegment buffer;
    private MemorySegment src;
    private byte[] heapSrc;
    private final SegmentWriter writer = new SegmentWriter();
    private final SegmentReader reader = new SegmentReader();
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(1024);
    private RecordHandler handler;

    @Setup
    public void setup(Blackhole bh) {
        buffer = Arena.ofAuto().allocate(1 << 20, 4096);  // L2-sized so the periodic reset stays cheap
        src = Arena.ofAuto().allocate(payload);
        heapSrc = new byte[payload];
        handler = (buf, off, len, pos) -> bh.consume(buf.get(ValueLayout.JAVA_LONG, off));
        reset();
    }

    private void reset() {
        buffer.fill((byte) 0);
        writer.wrap(buffer, START, 0);
        reader.wrap(buffer, START, 0);
    }

    @Benchmark
    public int wallyAppendRead() {
        if (writer.append(src, 0, payload) == SegmentWriter.NO_SPACE) {
            reset();
            writer.append(src, 0, payload);
        }
        return reader.read(handler, 1);
    }

    @Benchmark
    public long wallyClaimCommitRead() {
        if (writer.tryClaim(payload) == SegmentWriter.NO_SPACE) {
            reset();
            writer.tryClaim(payload);
        }
        writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), 42L);
        writer.commit();
        return reader.read(handler, 1);
    }

    @Benchmark
    public byte[] arrayBlockingQueueOfferPoll() {
        queue.offer(heapSrc.clone());
        return queue.poll();
    }
}
