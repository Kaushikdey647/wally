package io.wally.bench;

import io.wally.spsc.MessageHandler;
import io.wally.spsc.Reader;
import io.wally.spsc.RingBuffer;
import io.wally.spsc.RingBufferConfig;
import io.wally.spsc.Writer;
import org.jctools.queues.SpscArrayQueue;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * 1P1C throughput: one producer thread and one consumer thread per group, both non-blocking.
 *
 * <p>Read the {@code polled} counter: messages actually handed from producer to consumer per second.
 * {@code offerFailed}/{@code pollEmpty} count full-queue and empty-queue spins.
 *
 * <p>Wally copies a 32-byte payload into the ring; the JCTools and ArrayBlockingQueue baselines pass a
 * preallocated object reference, which is the usual way they are used.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class SpscThroughputBenchmark {

    static final int RING_BYTES = 1 << 20;
    static final int QUEUE_SLOTS = 32 * 1024;
    static final int PAYLOAD = 32;
    static final Object MESSAGE = new Object();

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class Counters {
        public long offered;
        public long offerFailed;
        public long polled;
        public long pollEmpty;

        @Setup(Level.Iteration)
        public void reset() {
            offered = offerFailed = polled = pollEmpty = 0;
        }
    }

    static final class LastValue implements MessageHandler {
        long value;

        @Override
        public void onMessage(MemorySegment buffer, long offset, int length, long sequence) {
            value = buffer.get(JAVA_LONG, offset);
        }
    }

    @State(Scope.Group)
    public static class WallyState {
        Writer writer;
        Reader reader;
        final LastValue handler = new LastValue();

        @Setup(Level.Trial)
        public void setup() {
            RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(RING_BYTES));
            writer = ring.writer();
            reader = ring.reader();
        }
    }

    @State(Scope.Group)
    public static class JctoolsState {
        final SpscArrayQueue<Object> queue = new SpscArrayQueue<>(QUEUE_SLOTS);
    }

    @State(Scope.Group)
    public static class AbqState {
        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_SLOTS);
    }

    @Benchmark
    @Group("wally")
    @GroupThreads(1)
    public void wallyOffer(WallyState s, Counters c) {
        Writer w = s.writer;
        if (w.tryClaim(PAYLOAD)) {
            w.buffer().set(JAVA_LONG, w.offset(), c.offered);
            w.commit();
            c.offered++;
        } else {
            c.offerFailed++;
        }
    }

    @Benchmark
    @Group("wally")
    @GroupThreads(1)
    public long wallyPoll(WallyState s, Counters c) {
        if (s.reader.read(s.handler, 1) == 1) {
            c.polled++;
        } else {
            c.pollEmpty++;
        }
        return s.handler.value;
    }

    @Benchmark
    @Group("jctools")
    @GroupThreads(1)
    public void jctoolsOffer(JctoolsState s, Counters c) {
        if (s.queue.offer(MESSAGE)) {
            c.offered++;
        } else {
            c.offerFailed++;
        }
    }

    @Benchmark
    @Group("jctools")
    @GroupThreads(1)
    public Object jctoolsPoll(JctoolsState s, Counters c) {
        Object m = s.queue.poll();
        if (m != null) {
            c.polled++;
        } else {
            c.pollEmpty++;
        }
        return m;
    }

    @Benchmark
    @Group("abq")
    @GroupThreads(1)
    public void abqOffer(AbqState s, Counters c) {
        if (s.queue.offer(MESSAGE)) {
            c.offered++;
        } else {
            c.offerFailed++;
        }
    }

    @Benchmark
    @Group("abq")
    @GroupThreads(1)
    public Object abqPoll(AbqState s, Counters c) {
        Object m = s.queue.poll();
        if (m != null) {
            c.polled++;
        } else {
            c.pollEmpty++;
        }
        return m;
    }
}
