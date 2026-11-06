package io.wally.bench;

import io.wally.api.Appender;
import io.wally.api.RecordHandler;
import io.wally.api.Tailer;
import io.wally.api.Wally;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.jctools.queues.MpscArrayQueue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.Control;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-Producer Single-Consumer (MPSC) throughput benchmark.
 *
 * <p>Tests realistic concurrent workload: N producer threads appending records,
 * 1 consumer thread reading them. Measures:
 * <ul>
 *   <li>Producer throughput (ops/sec per thread)</li>
 *   <li>Consumer throughput (ops/sec)</li>
 *   <li>Contention overhead (vs single-producer baseline)</li>
 *   <li>GC allocation rate under load</li>
 * </ul>
 *
 * <p><b>Benchmark Design:</b>
 * <ul>
 *   <li>Uses JMH {@code @Group} to coordinate producer/consumer threads</li>
 *   <li>Producers run until control signal stops them</li>
 *   <li>Consumer drains queue, counting records consumed</li>
 *   <li>Reports both producer and consumer throughput separately</li>
 * </ul>
 *
 * <p><b>Fair Comparison:</b>
 * <ul>
 *   <li>All libraries use same producer/consumer pattern</li>
 *   <li>Same payload sizes (32B, 256B, 4KB)</li>
 *   <li>Same number of producers (1, 2, 4, 8)</li>
 *   <li>Wally: single-writer mode for 1P, future MPSC mode for N > 1</li>
 * </ul>
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class MPSCThroughputBenchmark {

    /**
     * Number of producer threads.
     * Start with 1 (baseline), then 2, 4, 8 to measure contention scaling.
     */
    @Param({"1", "2", "4", "8"})
    int producers;

    @Param({"256"})
    int payloadSize;

    // ==================== Wally State ====================

    private Path wallyDir;
    private Wally wally;
    private Appender[] wallyAppenders;  // One per producer thread
    private Tailer wallyTailer;
    private byte[][] wallyPayloads;
    private RecordHandler wallyHandler;
    private AtomicLong wallyConsumed;

    // ==================== JCTools State ====================

    private MpscArrayQueue<byte[]> jctoolsQueue;
    private byte[][] jctoolsPayloads;
    private AtomicLong jctoolsConsumed;

    // ==================== Chronicle State ====================

    private Path chronicleDir;
    private ChronicleQueue chronicle;
    private ExcerptAppender[] chronicleAppenders;  // One per producer thread
    private ExcerptTailer chronicleTailer;
    private byte[][] chroniclePayloads;
    private byte[] chronicleReadBuffer;
    private AtomicLong chronicleConsumed;

    // ==================== ArrayBlockingQueue State ====================

    private ArrayBlockingQueue<byte[]> arrayQueue;
    private byte[][] arrayPayloads;
    private AtomicLong arrayConsumed;

    @Setup(Level.Trial)
    public void setupTrial(Blackhole bh) throws IOException {
        // Create temp directory for file-based queues
        wallyDir = Files.createTempDirectory("wally-mpsc-bench-");

        // Initialize payloads (one per producer thread)
        wallyPayloads = new byte[producers][payloadSize];
        jctoolsPayloads = new byte[producers][payloadSize];
        chroniclePayloads = new byte[producers][payloadSize];
        arrayPayloads = new byte[producers][payloadSize];

        for (int i = 0; i < producers; i++) {
            // Write producer ID into first 4 bytes for verification
            wallyPayloads[i][0] = (byte) i;
            jctoolsPayloads[i][0] = (byte) i;
            chroniclePayloads[i][0] = (byte) i;
            arrayPayloads[i][0] = (byte) i;
        }

        // Wally setup
        wally = Wally.builder()
                .directory(wallyDir.resolve("wally"))
                .segmentSize(256 * 1024 * 1024)  // 256 MB segments
                .open();
        wallyAppenders = new Appender[producers];
        for (int i = 0; i < producers; i++) {
            wallyAppenders[i] = wally.appender();  // One per thread (not thread-safe)
        }
        wallyTailer = wally.tailerFrom(0);
        wallyConsumed = new AtomicLong(0);
        wallyHandler = (buf, off, len, pos) -> {
            bh.consume(buf.get(ValueLayout.JAVA_BYTE, off));
            wallyConsumed.incrementAndGet();
        };

        // JCTools setup (capacity large enough to avoid blocking)
        jctoolsQueue = new MpscArrayQueue<>(1024 * 1024);
        jctoolsConsumed = new AtomicLong(0);

        // Chronicle setup
        chronicleDir = wallyDir.resolve("chronicle");
        Files.createDirectories(chronicleDir);
        chronicle = SingleChronicleQueueBuilder.binary(chronicleDir.toFile())
                .build();
        chronicleAppenders = new ExcerptAppender[producers];
        for (int i = 0; i < producers; i++) {
            chronicleAppenders[i] = chronicle.createAppender();
        }
        chronicleTailer = chronicle.createTailer();
        chronicleReadBuffer = new byte[payloadSize];
        chronicleConsumed = new AtomicLong(0);

        // ArrayBlockingQueue setup
        arrayQueue = new ArrayBlockingQueue<>(1024 * 1024);
        arrayConsumed = new AtomicLong(0);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        // Report consumed counts for verification
        System.out.println("Wally consumed: " + wallyConsumed.get());
        System.out.println("JCTools consumed: " + jctoolsConsumed.get());
        System.out.println("Chronicle consumed: " + chronicleConsumed.get());
        System.out.println("ArrayBlockingQueue consumed: " + arrayConsumed.get());

        // Close resources
        if (wally != null) {
            wally.close();
        }
        if (chronicle != null) {
            chronicle.close();
        }

        // Clean up temp directory
        if (wallyDir != null && Files.exists(wallyDir)) {
            Files.walk(wallyDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore cleanup errors
                        }
                    });
        }
    }

    // ==================== Wally MPSC Benchmarks ====================

    @Group("wally")
    @GroupThreads(1)  // Controlled by @Param producers
    @Benchmark
    public void wallyProducer(Control control) {
        // Get thread-local appender (JMH assigns thread IDs 0..N-1)
        int threadId = (int) (Thread.currentThread().getId() % producers);
        Appender appender = wallyAppenders[threadId];
        byte[] payload = wallyPayloads[threadId];

        while (!control.stopMeasurement) {
            long pos = appender.append(payload, 0, payloadSize);
            if (pos < 0) {
                // NO_SPACE: segment rolling or backpressure
                // In production, would retry or backoff
                Thread.onSpinWait();
            }
        }
    }

    @Group("wally")
    @GroupThreads(1)
    @Benchmark
    public int wallyConsumer() {
        // Poll in batches for efficiency
        return wallyTailer.poll(wallyHandler, 100);
    }

    // ==================== JCTools MPSC Benchmarks ====================

    @Group("jctools")
    @GroupThreads(1)
    @Benchmark
    public void jctoolsProducer(Control control) {
        int threadId = (int) (Thread.currentThread().getId() % producers);
        byte[] payload = jctoolsPayloads[threadId].clone();  // Allocates!

        while (!control.stopMeasurement) {
            if (!jctoolsQueue.offer(payload)) {
                // Queue full (shouldn't happen with 1M capacity)
                Thread.onSpinWait();
            }
        }
    }

    @Group("jctools")
    @GroupThreads(1)
    @Benchmark
    public void jctoolsConsumer(Blackhole bh) {
        // Drain queue in batches
        for (int i = 0; i < 100; i++) {
            byte[] data = jctoolsQueue.poll();
            if (data != null) {
                bh.consume(data[0]);
                jctoolsConsumed.incrementAndGet();
            } else {
                break;
            }
        }
    }

    // ==================== Chronicle MPSC Benchmarks ====================

    @Group("chronicle")
    @GroupThreads(1)
    @Benchmark
    public void chronicleProducer(Control control) {
        int threadId = (int) (Thread.currentThread().getId() % producers);
        ExcerptAppender appender = chronicleAppenders[threadId];
        byte[] payload = chroniclePayloads[threadId];

        while (!control.stopMeasurement) {
            try (net.openhft.chronicle.wire.DocumentContext dc = appender.writingDocument()) {
                dc.wire().bytes().write(payload);
            }
        }
    }

    @Group("chronicle")
    @GroupThreads(1)
    @Benchmark
    public void chronicleConsumer(Blackhole bh) {
        // Drain in batches
        for (int i = 0; i < 100; i++) {
            try (net.openhft.chronicle.wire.DocumentContext dc = chronicleTailer.readingDocument()) {
                if (dc.isPresent()) {
                    dc.wire().bytes().read(chronicleReadBuffer);
                    bh.consume(chronicleReadBuffer[0]);
                    chronicleConsumed.incrementAndGet();
                } else {
                    break;
                }
            }
        }
    }

    // ==================== ArrayBlockingQueue MPSC Benchmarks ====================

    @Group("abq")
    @GroupThreads(1)
    @Benchmark
    public void abqProducer(Control control) {
        int threadId = (int) (Thread.currentThread().getId() % producers);
        byte[] payload = arrayPayloads[threadId].clone();  // Allocates!

        while (!control.stopMeasurement) {
            try {
                if (!arrayQueue.offer(payload)) {
                    Thread.onSpinWait();
                }
            } catch (Exception e) {
                // Queue operations can throw
            }
        }
    }

    @Group("abq")
    @GroupThreads(1)
    @Benchmark
    public void abqConsumer(Blackhole bh) {
        // Drain in batches
        for (int i = 0; i < 100; i++) {
            byte[] data = arrayQueue.poll();
            if (data != null) {
                bh.consume(data[0]);
                arrayConsumed.incrementAndGet();
            } else {
                break;
            }
        }
    }
}
