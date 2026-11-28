package io.wally.bench;

import io.wally.spsc.MessageHandler;
import io.wally.spsc.Reader;
import io.wally.spsc.RingBuffer;
import io.wally.spsc.RingBufferConfig;
import io.wally.spsc.Writer;
import org.HdrHistogram.Histogram;
import org.jctools.queues.SpscArrayQueue;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * 1P1C one-way latency at a fixed offered rate.
 *
 * <p>The producer stamps each message with its <em>intended</em> send time ({@code start + i * interval}),
 * not the time it actually got through, so producer stalls and full-queue waits show up as latency
 * instead of being hidden (coordinated omission). The consumer records {@code nanoTime() - stamp}.
 *
 * <p>Args: {@code <wally|jctools> [ratePerSec=1000000] [warmupSec=5] [measureSec=20] [outDir=results]}
 */
public final class SpscLatency {

    private static final int RING_BYTES = 1 << 20;
    private static final int QUEUE_SLOTS = 32 * 1024;
    private static final int PAYLOAD = 32;
    private static final long EMPTY = Long.MIN_VALUE;

    interface Channel {
        boolean offer(long stamp);

        /** @return the stamp, or {@code EMPTY} */
        long poll();
    }

    static final class WallyChannel implements Channel, MessageHandler {
        private final Writer writer;
        private final Reader reader;
        private long last;

        WallyChannel() {
            RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(RING_BYTES));
            writer = ring.writer();
            reader = ring.reader();
        }

        @Override
        public boolean offer(long stamp) {
            if (!writer.tryClaim(PAYLOAD)) {
                return false;
            }
            writer.buffer().set(JAVA_LONG, writer.offset(), stamp);
            writer.commit();
            return true;
        }

        @Override
        public long poll() {
            return reader.read(this, 1) == 1 ? last : EMPTY;
        }

        @Override
        public void onMessage(MemorySegment buffer, long offset, int length, long sequence) {
            last = buffer.get(JAVA_LONG, offset);
        }
    }

    /** Reuses a pool of holders larger than the queue so neither side allocates per message. */
    static final class JctoolsChannel implements Channel {
        private final SpscArrayQueue<long[]> queue = new SpscArrayQueue<>(QUEUE_SLOTS);
        private final long[][] pool = new long[QUEUE_SLOTS * 4][1];
        private int next;

        @Override
        public boolean offer(long stamp) {
            long[] holder = pool[next];
            holder[0] = stamp;
            if (!queue.offer(holder)) {
                return false;
            }
            next = (next + 1) & (pool.length - 1);
            return true;
        }

        @Override
        public long poll() {
            long[] holder = queue.poll();
            return holder == null ? EMPTY : holder[0];
        }
    }

    public static void main(String[] args) throws Exception {
        String impl = args.length > 0 ? args[0] : "wally";
        long rate = args.length > 1 ? Long.parseLong(args[1]) : 1_000_000;
        int warmupSec = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        int measureSec = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        Path outDir = Path.of(args.length > 4 ? args[4] : "results");

        Channel channel = switch (impl) {
            case "wally" -> new WallyChannel();
            case "jctools" -> new JctoolsChannel();
            default -> throw new IllegalArgumentException("unknown impl: " + impl);
        };

        long interval = TimeUnit.SECONDS.toNanos(1) / rate;
        long total = rate * (warmupSec + measureSec);
        long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        long measureFrom = start + TimeUnit.SECONDS.toNanos(warmupSec);
        long maxValue = TimeUnit.SECONDS.toNanos(10);
        Histogram histogram = new Histogram(maxValue, 3);

        Thread consumer = new Thread(() -> {
            long received = 0;
            while (received < total) {
                long stamp = channel.poll();
                if (stamp == EMPTY) {
                    Thread.onSpinWait();
                    continue;
                }
                long now = System.nanoTime();
                received++;
                if (stamp >= measureFrom) {
                    histogram.recordValue(Math.min(now - stamp, maxValue));
                }
            }
        }, "consumer");
        consumer.start();

        for (long i = 0; i < total; i++) {
            long intended = start + i * interval;
            while (System.nanoTime() < intended) {
                Thread.onSpinWait();
            }
            while (!channel.offer(intended)) {
                Thread.onSpinWait();
            }
        }
        consumer.join();

        report(impl, rate, measureSec, histogram, outDir);
    }

    private static void report(String impl, long rate, int measureSec, Histogram h, Path outDir) throws IOException {
        String summary = String.format(
            "%s rate=%d/s measured=%ds n=%d  p50=%dns p99=%dns p99.9=%dns p99.99=%dns max=%dns",
            impl, rate, measureSec, h.getTotalCount(),
            h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getValueAtPercentile(99.9),
            h.getValueAtPercentile(99.99), h.getMaxValue());
        System.out.println(summary);

        Files.createDirectories(outDir);
        Path file = outDir.resolve("latency-" + impl + "-" + rate + ".hgrm");
        try (PrintStream out = new PrintStream(Files.newOutputStream(file))) {
            out.println("# " + summary);
            out.println("# " + System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version")
                + ", " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", " + Runtime.getRuntime().availableProcessors() + " cpus");
            h.outputPercentileDistribution(out, 1.0);
        }
        System.out.println("wrote " + file.toAbsolutePath());
    }
}
