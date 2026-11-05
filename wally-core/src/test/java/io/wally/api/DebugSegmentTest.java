package io.wally.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugSegmentTest {

    @TempDir
    Path tempDir;

    @Test
    void debugSingleSegment() {
        try (Wally wal = Wally.builder().directory(tempDir).segmentSize(4096).open()) {
            Appender appender = wal.appender();

            System.out.println("=== Writing 3 records ===");
            for (int i = 0; i < 3; i++) {
                byte[] payload = new byte[64];
                payload[0] = (byte) i;
                long pos = appender.append(payload, 0, payload.length);
                System.out.println("Wrote record " + i + " at position " + pos);
            }

            System.out.println("\n=== Reading back ===");
            Tailer tailer = wal.tailerFrom(0);
            System.out.println("Tailer starts at position: " + tailer.position());

            int[] count = {0};
            for (int attempt = 0; attempt < 5; attempt++) {
                int read = tailer.poll((buf, off, len, pos) -> {
                    byte val = buf.get(ValueLayout.JAVA_BYTE, off);
                    System.out.println("  Read record value=" + val + " at position=" + pos + " length=" + len);
                    count[0]++;
                }, 10);
                System.out.println("Attempt " + attempt + ": read=" + read + ", total=" + count[0] + ", tailer.position=" + tailer.position());
                if (read == 0) {
                    System.out.println("  (no more records)");
                    break;
                }
            }

            assertEquals(3, count[0], "Should have read 3 records");
        }
    }
}
