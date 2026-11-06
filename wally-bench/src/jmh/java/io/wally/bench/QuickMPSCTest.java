package io.wally.bench;

import io.wally.api.Appender;
import io.wally.api.ProducerMode;
import io.wally.api.RecordHandler;
import io.wally.api.Tailer;
import io.wally.api.Wally;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quick MPSC validation test - runs in seconds.
 */
public class QuickMPSCTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Quick MPSC Validation Test ===\n");

        // Test 1: SINGLE mode (backward compatibility)
        System.out.println("Test 1: SINGLE mode (backward compatibility)...");
        runTest(ProducerMode.SINGLE, 1, 10_000);
        System.out.println("✅ PASS\n");

        // Test 2: MULTI mode with 4 producers
        System.out.println("Test 2: MULTI mode with 4 producers...");
        runTest(ProducerMode.MULTI, 4, 10_000);
        System.out.println("✅ PASS\n");

        // Test 3: MULTI mode with 8 producers (stress test)
        System.out.println("Test 3: MULTI mode with 8 producers...");
        runTest(ProducerMode.MULTI, 8, 20_000);
        System.out.println("✅ PASS\n");

        System.out.println("=== All Tests Passed ===");
    }

    private static void runTest(ProducerMode mode, int producers, int recordsPerProducer) throws Exception {
        Path tempDir = Files.createTempDirectory("wally-quick-test-");
        try {
            Wally wally = Wally.builder()
                    .directory(tempDir)
                    .segmentSize(16 * 1024 * 1024)  // 16 MB segments
                    .producerMode(mode)
                    .open();

            try {
                ConcurrentHashMap<String, AtomicLong> recordsSeen = new ConcurrentHashMap<>();
                AtomicLong totalConsumed = new AtomicLong(0);
                CountDownLatch producersFinished = new CountDownLatch(producers);
                CountDownLatch consumerFinished = new CountDownLatch(1);

                // Start consumer
                Thread consumer = new Thread(() -> {
                    Tailer tailer = wally.tailerFrom(0);
                    RecordHandler handler = (buf, off, len, pos) -> {
                        int producerId = buf.get(ValueLayout.JAVA_INT, off);
                        long sequence = buf.get(ValueLayout.JAVA_LONG, off + 4);
                        String key = producerId + ":" + sequence;
                        recordsSeen.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
                        totalConsumed.incrementAndGet();
                    };

                    while (producersFinished.getCount() > 0 || totalConsumed.get() < producers * recordsPerProducer) {
                        tailer.poll(handler, 1000);
                    }
                    // Final drain
                    while (tailer.poll(handler, 1000) > 0) {
                        // Continue
                    }
                    consumerFinished.countDown();
                });
                consumer.start();

                // Start producers
                Thread[] producerThreads = new Thread[producers];
                for (int p = 0; p < producers; p++) {
                    final int producerId = p;
                    producerThreads[p] = new Thread(() -> {
                        try {
                            Appender appender = wally.appender();
                            byte[] payload = new byte[64];

                            for (long seq = 0; seq < recordsPerProducer; seq++) {
                                var segment = java.lang.foreign.MemorySegment.ofArray(payload);
                                segment.set(ValueLayout.JAVA_INT, 0, producerId);
                                segment.set(ValueLayout.JAVA_LONG, 4, seq);

                                long pos;
                                while ((pos = appender.append(payload, 0, 64)) < 0) {
                                    Thread.onSpinWait();
                                }
                            }
                            producersFinished.countDown();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    producerThreads[p].start();
                }

                // Wait for completion
                for (Thread t : producerThreads) {
                    t.join();
                }
                consumerFinished.await();
                consumer.join();

                // Validate
                long expected = producers * recordsPerProducer;
                long actual = totalConsumed.get();

                System.out.printf("  Expected: %,d records\n", expected);
                System.out.printf("  Consumed: %,d records\n", actual);
                System.out.printf("  Unique:   %,d records\n", recordsSeen.size());

                if (actual != expected) {
                    throw new AssertionError("Record count mismatch: expected " + expected + ", got " + actual);
                }

                // Check for duplicates
                for (var entry : recordsSeen.entrySet()) {
                    if (entry.getValue().get() != 1) {
                        throw new AssertionError("Duplicate record: " + entry.getKey() + " seen " + entry.getValue().get() + " times");
                    }
                }

            } finally {
                wally.close();
            }
        } finally {
            // Cleanup
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
}
