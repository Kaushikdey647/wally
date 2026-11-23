package io.wally.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

class MultiSegmentDebugTest {

    @TempDir
    Path tempDir;

    @Test
    void debugMultipleSegments() {
        // Small segments to force rollover
        try (Wally wal = Wally.builder().directory(tempDir).segmentSize(4096).open()) {
            Appender appender = wal.appender();

            System.out.println("=== Writing 10 records (should span takes 1 segment) ===");
            for (int i = 0; i < 10; i++) {
                byte[] payload = new byte[64];
                payload[0] = (byte) i;
                long pos = appender.append(payload, 0, payload.length);
                System.out.println("Wrote record " + i + " at position " + pos);
            }

            System.out.println("\n=== Reading back ===");
            Tailer tailer = wal.tailerFrom(0);
            System.out.println("Tailer starts at position: " + tailer.position());

            int[] count = {0};
            for (int attempt = 0; attempt < 15 && count[0] < 10; attempt++) {
                int read = tailer.poll((buf, off, len, pos) -> {
                    byte val = buf.get(ValueLayout.JAVA_BYTE, off);
                    System.out.println("  Read record value=" + val + " at position=" + pos);
                    count[0]++;
                }, 5);
                System.out.println("Attempt " + attempt + ": read=" + read + ", total=" + count[0] + ", tailer.position=" + tailer.position());
                if (read == 0 && count[0] > 0) {
                    System.out.println("  (stopped reading, breaking)");
                    break;
                }
            }

            System.out.println("\nFinal: read " + count[0] + " out of 10 records");
        }
    }
}
