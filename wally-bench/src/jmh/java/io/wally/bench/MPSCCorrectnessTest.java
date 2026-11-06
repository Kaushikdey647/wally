package io.wally.bench;

import io.wally.api.Appender;
import io.wally.api.RecordHandler;
import io.wally.api.Tailer;
import io.wally.api.Wally;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correctness validation for multi-producer scenarios.
 *
 * <p>NOT a JMH benchmark - this is a standalone test that validates:
 * <ul>
 *   <li>All records written are eventually readable (no loss)</li>
 *   <li>No duplicate records (position uniqueness)</li>
 *   <li>Records are read in position order</li>
 *   <li>Multi-producer contention doesn't corrupt data</li>
 * </ul>
 *
 * <p><b>Test Methodology:</b>
 * <ol>
 *   <li>N producer threads each write M records with unique IDs</li>
 *   <li>Each record contains: producer ID (4 bytes) + sequence number (8 bytes)</li>
 *   <li>Consumer thread reads all records and validates uniqueness</li>
 *   <li>After all producers finish, verify all N*M records were consumed</li>
 * </ol>
 *
 * <p><b>Run with:</b>
 * <pre>
 * ./gradlew :wally-bench:test --tests MPSCCorrectnessTest
 * </pre>
 */
public class MPSCCorrectnessTest {

    private static final int PRODUCERS = 8;
    private static final int RECORDS_PER_PRODUCER = 100_000;
    private static final int PAYLOAD_SIZE = 64;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Wally MPSC Correctness Test ===");
        System.out.printf("Producers: %d\n", PRODUCERS);
        System.out.printf("Records per producer: %,d\n", RECORDS_PER_PRODUCER);
        System.out.printf("Total expected: %,d\n", PRODUCERS * RECORDS_PER_PRODUCER);

        Path tempDir = Files.createTempDirectory("wally-mpsc-test-");
        try {
            runTest(tempDir);
            System.out.println("\n✅ PASS: All correctness checks passed");
        } finally {
            cleanup(tempDir);
        }
    }

    private static void runTest(Path tempDir) throws Exception {
        // Setup Wally
        Wally wally = Wally.builder()
                .directory(tempDir)
                .segmentSize(64 * 1024 * 1024)  // 64 MB segments - will roll during test
                .open();

        try {
            // Track what we've seen
            Set<RecordKey> seenRecords = ConcurrentHashMap.newKeySet();
            AtomicLong recordsConsumed = new AtomicLong(0);
            AtomicLong lastPosition = new AtomicLong(-1);
            CountDownLatch producersFinished = new CountDownLatch(PRODUCERS);
            CountDownLatch consumerFinished = new CountDownLatch(1);

            // Start consumer thread
            Thread consumer = new Thread(() -> {
                Tailer tailer = wally.tailerFrom(0);
                RecordHandler handler = (buf, off, len, pos) -> {
                    // Read producer ID and sequence
                    int producerId = buf.get(ValueLayout.JAVA_INT, off);
                    long sequence = buf.get(ValueLayout.JAVA_LONG, off + 4);

                    // Validate position ordering
                    long prev = lastPosition.get();
                    if (pos <= prev) {
                        throw new AssertionError(String.format(
                                "Position out of order: %d <= %d", pos, prev));
                    }
                    lastPosition.set(pos);

                    // Check for duplicates
                    RecordKey key = new RecordKey(producerId, sequence);
                    if (!seenRecords.add(key)) {
                        throw new AssertionError(String.format(
                                "Duplicate record: producer=%d, seq=%d", producerId, sequence));
                    }

                    recordsConsumed.incrementAndGet();
                };

                // Poll until all producers finish and queue is drained
                while (producersFinished.getCount() > 0 || recordsConsumed.get() < PRODUCERS * RECORDS_PER_PRODUCER) {
                    int read = tailer.poll(handler, 1000);
                    if (read == 0) {
                        Thread.onSpinWait();
                    }
                }

                // Final drain
                while (tailer.poll(handler, 1000) > 0) {
                    // Continue
                }

                consumerFinished.countDown();
            });
            consumer.setName("consumer");
            consumer.start();

            // Start producer threads
            ExecutorService producers = Executors.newFixedThreadPool(PRODUCERS);
            CountDownLatch producersStarted = new CountDownLatch(PRODUCERS);

            for (int p = 0; p < PRODUCERS; p++) {
                final int producerId = p;
                producers.submit(() -> {
                    try {
                        Appender appender = wally.appender();
                        byte[] payload = new byte[PAYLOAD_SIZE];

                        producersStarted.countDown();
                        producersStarted.await();  // Synchronized start for max contention

                        for (long seq = 0; seq < RECORDS_PER_PRODUCER; seq++) {
                            // Write producer ID + sequence into payload
                            var segment = java.lang.foreign.MemorySegment.ofArray(payload);
                            segment.set(ValueLayout.JAVA_INT, 0, producerId);
                            segment.set(ValueLayout.JAVA_LONG, 4, seq);

                            // Append with retry on backpressure
                            long pos;
                            while ((pos = appender.append(payload, 0, PAYLOAD_SIZE)) < 0) {
                                // NO_SPACE: retry after yield
                                Thread.onSpinWait();
                            }

                            // Occasional progress report
                            if (seq % 10000 == 0) {
                                System.out.printf("Producer %d: %,d records written\n", producerId, seq);
                            }
                        }

                        System.out.printf("Producer %d: FINISHED (%,d records)\n", producerId, RECORDS_PER_PRODUCER);
                        producersFinished.countDown();
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                });
            }

            // Wait for all producers to finish
            System.out.println("\nWaiting for producers to finish...");
            producersFinished.await();
            System.out.println("All producers finished");

            // Wait for consumer to drain
            System.out.println("Waiting for consumer to drain queue...");
            consumerFinished.await(30, TimeUnit.SECONDS);

            if (consumerFinished.getCount() > 0) {
                throw new AssertionError("Consumer did not finish in time");
            }

            consumer.join();
            producers.shutdown();
            producers.awaitTermination(5, TimeUnit.SECONDS);

            // Validate results
            long expected = PRODUCERS * RECORDS_PER_PRODUCER;
            long actual = recordsConsumed.get();

            System.out.println("\n=== Results ===");
            System.out.printf("Expected records: %,d\n", expected);
            System.out.printf("Consumed records: %,d\n", actual);
            System.out.printf("Unique records:   %,d\n", seenRecords.size());

            if (actual != expected) {
                throw new AssertionError(String.format(
                        "Record count mismatch: expected %d, got %d", expected, actual));
            }

            if (seenRecords.size() != expected) {
                throw new AssertionError(String.format(
                        "Duplicate records detected: %d unique out of %d total",
                        seenRecords.size(), expected));
            }

            // Verify all sequences are present for each producer
            for (int p = 0; p < PRODUCERS; p++) {
                for (long seq = 0; seq < RECORDS_PER_PRODUCER; seq++) {
                    RecordKey key = new RecordKey(p, seq);
                    if (!seenRecords.contains(key)) {
                        throw new AssertionError(String.format(
                                "Missing record: producer=%d, seq=%d", p, seq));
                    }
                }
            }

            System.out.println("✓ All records accounted for");
            System.out.println("✓ No duplicates");
            System.out.println("✓ Position ordering maintained");

        } finally {
            wally.close();
        }
    }

    private static void cleanup(Path tempDir) throws IOException {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
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

    /**
     * Unique key for each record: (producerId, sequence).
     */
    private static record RecordKey(int producerId, long sequence) {
    }
}
