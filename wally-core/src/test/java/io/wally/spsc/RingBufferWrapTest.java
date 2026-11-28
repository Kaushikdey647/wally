package io.wally.spsc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.*;

class RingBufferWrapTest {

    /**
     * Ring of 64 bytes. After A(16) B(24) C(24) are written and A is read, D(16) wraps to index 0,
     * leaving tail on B's header with the ring full. A failing claim that needs to wrap must not
     * write its sentinel over B.
     */
    @Test
    void failedClaimAtWrapDoesNotClobberUnreadFrame() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(64));
        Writer w = ring.writer();
        Reader r = ring.reader();

        write(w, 8, 'A');
        write(w, 16, 'B');
        write(w, 16, 'C');
        assertEquals(List.of("A"), drain(r, 1));
        write(w, 8, 'D');

        for (int i = 0; i < 10; i++) {
            assertFalse(w.tryClaim(48), "ring is full");
        }

        assertEquals(List.of("B", "C", "D"), drain(r, 10));

        // Frame of 56 bytes from tail index 16: only fits after the skip to the next lap is published.
        assertFalse(w.tryClaim(48));
        assertEquals(List.of(), drain(r, 10));
        write(w, 48, 'E');
        assertEquals(List.of("E"), drain(r, 10));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void randomSizesAcrossThreads() throws Exception {
        int messages = 2_000_000;
        long seed = 42;
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer w = ring.writer();
        Reader r = ring.reader();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            SplittableRandom sizes = new SplittableRandom(seed);
            long[] expected = {0};
            MessageHandler check = (buf, off, len, pos) -> {
                long seq = expected[0]++;
                int size = 8 + sizes.nextInt(193);
                if (len != size || buf.get(JAVA_LONG, off) != seq) {
                    throw new AssertionError("seq " + seq + ": len " + len + " (want " + size
                        + "), header seq " + buf.get(JAVA_LONG, off));
                }
                for (int i = 8; i < len; i++) {
                    if (buf.get(JAVA_BYTE, off + i) != (byte) (seq + i)) {
                        throw new AssertionError("seq " + seq + ": payload byte " + i + " corrupted");
                    }
                }
            };
            try {
                while (expected[0] < messages) {
                    r.read(check, 64);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        consumer.setDaemon(true);
        consumer.start();

        SplittableRandom sizes = new SplittableRandom(seed);
        for (long seq = 0; seq < messages && failure.get() == null; seq++) {
            int size = 8 + sizes.nextInt(193);
            while (!w.tryClaim(size)) {
                if (failure.get() != null) break;
                Thread.onSpinWait();
            }
            if (failure.get() != null) break;
            MemorySegment buf = w.buffer();
            long off = w.offset();
            buf.set(JAVA_LONG, off, seq);
            for (int i = 8; i < size; i++) {
                buf.set(JAVA_BYTE, off + i, (byte) (seq + i));
            }
            w.commit();
        }

        consumer.join();
        if (failure.get() != null) {
            fail(failure.get());
        }
    }

    private static void write(Writer w, int length, char tag) {
        assertTrue(w.tryClaim(length), "claim " + tag);
        w.buffer().set(JAVA_BYTE, w.offset(), (byte) tag);
        w.commit();
    }

    private static List<String> drain(Reader r, int limit) {
        List<String> out = new ArrayList<>();
        r.read((buf, off, len, pos) -> out.add(String.valueOf((char) buf.get(JAVA_BYTE, off))), limit);
        return out;
    }
}
