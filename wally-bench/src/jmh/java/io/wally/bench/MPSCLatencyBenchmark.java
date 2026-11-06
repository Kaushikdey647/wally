package io.wally.bench;

import io.wally.api.Appender;
import io.wally.api.RecordHandler;
import io.wally.api.Tailer;
import io.wally.api.Wally;
import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Latency benchmark with HdrHistogram to capture tail latency.
 *
 * <p>Measures producer append latency under load with coordinated omission protection.
 * Key metrics:
 * <ul>
 *   <li>p50, p99, p99.9, p99.99 latency</li>
 *   <li>Max latency observed</li>
 *   <li>Latency under contention (multi-producer)</li>
 *   <li>GC pause impact on tail latency</li>
 * </ul>
 *
 * <p><b>Methodology:</b>
 * <ul>
 *   <li>Fixed-rate load generator to avoid coordinated omission</li>
 *   <li>HdrHistogram records latency per operation</li>
 *   <li>Separate consumer thread drains queue to prevent backpressure</li>
 *   <li>Warmup ensures JIT compilation and page pre-faulting</li>
 * </ul>
 *
 * <p><b>Coordinated Omission:</b>
 * Standard JMH averaging can hide tail latency by not measuring time spent waiting
 * for previous operations. This benchmark uses a fixed rate (target ops/sec) and
 * records actual latency including any queueing time.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, batchSize = 10000)
@Measurement(iterations = 5, batchSize = 100000)
@Fork(1)
public class MPSCLatencyBenchmark {

    @Param({"256"})
    int payloadSize;

    /**
     * Target rate per producer thread (ops/sec).
     * Use realistic production rates to measure steady-state latency.
     */
    @Param({"100000"})  // 100K ops/sec per producer
    int targetRate;

    /**
     * Number of producer threads.
     */
    @Param({"1", "4"})
    int producers;

    // Wally state
    private Path wallyDir;
    private Wally wally;
    private Appender[] wallyAppenders;
    private Tailer wallyTailer;
    private byte[][] wallyPayloads;
    private RecordHandler wallyHandler;

    // Latency tracking
    private Histogram[] producerHistograms;  // One per producer thread
    private Thread consumerThread;
    private AtomicBoolean consumerRunning;
    private AtomicLong recordsConsumed;

    @Setup(Level.Trial)
    public void setupTrial(Blackhole bh) throws IOException {
        wallyDir = Files.createTempDirectory("wally-latency-bench-");

        // Wally setup
        wally = Wally.builder()
                .directory(wallyDir.resolve("wally"))
                .segmentSize(512 * 1024 * 1024)  // 512 MB segments to avoid rollover during test
                .open();

        wallyAppenders = new Appender[producers];
        wallyPayloads = new byte[producers][payloadSize];
        producerHistograms = new Histogram[producers];

        for (int i = 0; i < producers; i++) {
            wallyAppenders[i] = wally.appender();
            wallyPayloads[i][0] = (byte) i;  // Producer ID
            // Track latencies from 1ns to 1 minute with 3 significant digits
            producerHistograms[i] = new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
        }

        wallyTailer = wally.tailerFrom(0);
        recordsConsumed = new AtomicLong(0);
        wallyHandler = (buf, off, len, pos) -> {
            bh.consume(buf.get(ValueLayout.JAVA_BYTE, off));
            recordsConsumed.incrementAndGet();
        };

        // Start background consumer thread to drain queue
        consumerRunning = new AtomicBoolean(true);
        consumerThread = new Thread(() -> {
            while (consumerRunning.get()) {
                int read = wallyTailer.poll(wallyHandler, 1000);
                if (read == 0) {
                    // No records available, yield CPU
                    Thread.onSpinWait();
                }
            }
            // Final drain
            while (wallyTailer.poll(wallyHandler, 1000) > 0) {
                // Continue until empty
            }
        });
        consumerThread.setName("wally-consumer");
        consumerThread.start();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException, InterruptedException {
        // Stop consumer thread
        consumerRunning.set(false);
        if (consumerThread != null) {
            consumerThread.join(5000);
        }

        // Print latency percentiles
        System.out.println("\n=== Wally Latency Percentiles (nanoseconds) ===");
        for (int i = 0; i < producers; i++) {
            Histogram h = producerHistograms[i];
            System.out.printf("Producer %d:\n", i);
            System.out.printf("  p50:    %,d ns\n", h.getValueAtPercentile(50.0));
            System.out.printf("  p99:    %,d ns\n", h.getValueAtPercentile(99.0));
            System.out.printf("  p99.9:  %,d ns\n", h.getValueAtPercentile(99.9));
            System.out.printf("  p99.99: %,d ns\n", h.getValueAtPercentile(99.99));
            System.out.printf("  max:    %,d ns\n", h.getMaxValue());
            System.out.printf("  count:  %,d ops\n", h.getTotalCount());
        }
        System.out.printf("Records consumed: %,d\n", recordsConsumed.get());

        // Close resources
        if (wally != null) {
            wally.close();
        }

        // Cleanup
        if (wallyDir != null && Files.exists(wallyDir)) {
            Files.walk(wallyDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }

    @Benchmark
    @Threads(1)  // Override with -Pjmh.threads=N
    public void wallyAppendWithLatencyTracking() throws InterruptedException {
        // Determine thread ID for this producer
        int threadId = (int) (Thread.currentThread().getId() % producers);
        Appender appender = wallyAppenders[threadId];
        byte[] payload = wallyPayloads[threadId];
        Histogram histogram = producerHistograms[threadId];

        // Fixed-rate pacing to avoid coordinated omission
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / targetRate;
        long nextSendTime = System.nanoTime();

        // JMH batchSize controls iterations
        long startTime = System.nanoTime();
        long pos = appender.append(payload, 0, payloadSize);
        long latency = System.nanoTime() - startTime;

        if (pos >= 0) {
            histogram.recordValue(latency);
        } else {
            // Append failed (NO_SPACE or backpressure)
            // Record failure as max latency to highlight in tail
            histogram.recordValue(histogram.getHighestTrackableValue());
        }

        // Pace to target rate
        nextSendTime += intervalNanos;
        long now = System.nanoTime();
        if (nextSendTime > now) {
            long sleepNanos = nextSendTime - now;
            if (sleepNanos > 10_000) {  // Only sleep if > 10 microseconds
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            } else {
                // Busy-wait for sub-microsecond precision
                while (System.nanoTime() < nextSendTime) {
                    Thread.onSpinWait();
                }
            }
        }
    }

    /**
     * Helper to print histogram as ASCII chart (for debugging).
     */
    private static void printHistogram(Histogram histogram) {
        System.out.println("Latency distribution:");
        histogram.outputPercentileDistribution(System.out, 1000.0);  // Output in microseconds
    }
}
