package io.wally.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActualMultiSegmentTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndReadAcrossManySegments() {
        // 4096 B segment - 64 B header = 4032 B usable
        // Each record: 16 B header + 64 B payload = 80 B aligned
        // 4032 / 80 = 50 records per segment
        // 200 records = 4 segments
        try (Wally wal = Wally.builder().directory(tempDir).segmentSize(4096).open()) {
            Appender appender = wal.appender();

            int recordCount = 200;
            System.out.println("Writing " + recordCount + " records...");
            for (int i = 0; i < recordCount; i++) {
                byte[] payload = new byte[64];
                payload[0] = (byte) i;
                long pos = appender.append(payload, 0, payload.length);
                if (i % 50 == 0) {
                    System.out.println("  Record " + i + " at position " + pos);
                }
            }
            System.out.println("Done writing.");

            System.out.println("\nReading back...");
            Tailer tailer = wal.tailerFrom(0);
            int[] count = {0};
            int attempts = 0;
            while (count[0] < recordCount && attempts < 500) {
                attempts++;
                int read = tailer.poll((buf, off, len, pos) -> {
                    count[0]++;
                }, 100);
                if (attempts % 10 == 1) {
                    System.out.println("  Attempt " + attempts + ": read=" + read + ", total=" + count[0] + ", position=" + tailer.position());
                }
                if (read == 0) {
                    System.out.println("  Poll returned 0, stopping. Total read: " + count[0]);
                    break;
                }
            }

            System.out.println("Final: read " + count[0] + " out of " + recordCount);
            assertEquals(recordCount, count[0], "Should read all records");
        }
    }
}
