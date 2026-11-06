package io.wally.bench;

import io.wally.api.Appender;
import io.wally.api.RecordHandler;
import io.wally.api.Tailer;
import io.wally.api.Wally;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.jctools.queues.MpscArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Competitor baseline benchmarks: JCTools MPSC, Chronicle Queue, LMAX Disruptor.
 *
 * <p>M2 milestone: establishes context for Wally's multi-producer performance against
 * industry-standard alternatives.
 *
 * <p><b>Scenarios:</b>
 * <ul>
 *   <li>Single-thread append+read (existing ArrayBlockingQueue baseline)</li>
 *   <li>JCTools MpscArrayQueue (in-memory, no durability)</li>
 *   <li>Chronicle Queue (durable, similar to Wally)</li>
 *   <li>Disruptor (coming in separate benchmark due to complex threading model)</li>
 * </ul>
 *
 * <p><b>Fair Comparison Notes:</b>
 * <ul>
 *   <li>JCTools: in-memory only, no crash recovery</li>
 *   <li>Chronicle: uses sun.misc.Unsafe, Wally uses FFM API</li>
 *   <li>Wally: pre-alpha, fewer features than Chronicle</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class
CompetitorBenchmark {

    @Param({"32", "256", "4096"})
    int payloadSize;

    // Wally state
    private Path wallyDir;
    private Wally wally;
    private Appender wallyAppender;
    private Tailer wallyTailer;
    private byte[] wallyPayload;

    // JCTools state
    private MpscArrayQueue<byte[]> jctoolsQueue;
    private byte[] jctoolsPayload;

    // ArrayBlockingQueue baseline (already in SegmentLogBenchmark, included for completeness)
    private ArrayBlockingQueue<byte[]> arrayQueue;
    private byte[] arrayPayload;

    // Chronicle Queue state
    private Path chronicleDir;
    private ChronicleQueue chronicle;
    private ExcerptAppender chronicleAppender;
    private ExcerptTailer chronicleTailer;
    private byte[] chroniclePayload;

    private RecordHandler blackholeHandler;

    @Setup(Level.Iteration)
    public void setupIteration(Blackhole bh) throws IOException {
        // Fresh temp directory per iteration to avoid file state carryover
        wallyDir = Files.createTempDirectory("wally-bench-");
        wallyPayload = new byte[payloadSize];
        jctoolsPayload = new byte[payloadSize];
        arrayPayload = new byte[payloadSize];
        chroniclePayload = new byte[payloadSize];

        // Wally setup
        wally = Wally.builder()
                .directory(wallyDir)
                .segmentSize(64 * 1024 * 1024)  // 64 MB segments
                .open();
        wallyAppender = wally.appender();
        wallyTailer = wally.tailerFrom(0);
        blackholeHandler = (buf, off, len, pos) -> bh.consume(buf.get(ValueLayout.JAVA_LONG, off));

        // JCTools setup (capacity matches typical use)
        jctoolsQueue = new MpscArrayQueue<>(1024);

        // ArrayBlockingQueue setup
        arrayQueue = new ArrayBlockingQueue<>(1024);

        // Chronicle Queue setup
        chronicleDir = wallyDir.resolve("chronicle");
        Files.createDirectories(chronicleDir);
        chronicle = SingleChronicleQueueBuilder.binary(chronicleDir.toFile())
                // Use default roll cycle for simplicity
                .build();
        chronicleAppender = chronicle.createAppender();
        chronicleTailer = chronicle.createTailer();
    }

    @TearDown(Level.Iteration)
    public void tearDownIteration() throws IOException {
        // Close Wally
        if (wally != null) {
            wally.close();
        }

        // Close Chronicle
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

    // ==================== Wally Benchmarks ====================

    @Benchmark
    public int wallyAppendRead() {
        long pos = wallyAppender.append(wallyPayload, 0, payloadSize);
        if (pos < 0) {
            throw new IllegalStateException("Wally append failed");
        }
        return wallyTailer.poll(blackholeHandler, 1);
    }

    // ==================== JCTools Benchmarks ====================

    @Benchmark
    public byte[] jctoolsMpscOfferPoll() {
        // Note: allocates new byte[] per message (typical heap queue behavior)
        jctoolsQueue.offer(jctoolsPayload.clone());
        return jctoolsQueue.poll();
    }

    // ==================== ArrayBlockingQueue Baseline ====================

    @Benchmark
    public byte[] arrayBlockingQueueOfferPoll() {
        // Note: allocates new byte[] per message
        arrayQueue.offer(arrayPayload.clone());
        return arrayQueue.poll();
    }

    // ==================== Chronicle Queue Benchmarks ====================

    @Benchmark
    public long chronicleAppendRead(Blackhole bh) {
        // Write using Document context - simpler API
        try (net.openhft.chronicle.wire.DocumentContext dc = chronicleAppender.writingDocument()) {
            dc.wire().bytes().write(chroniclePayload);
        }

        // Read using Document context
        try (net.openhft.chronicle.wire.DocumentContext dc = chronicleTailer.readingDocument()) {
            if (dc.isPresent()) {
                byte[] data = new byte[payloadSize];
                dc.wire().bytes().read(data);
                bh.consume(data[0]);
                return 1;
            }
        }
        return 0;
    }
}
