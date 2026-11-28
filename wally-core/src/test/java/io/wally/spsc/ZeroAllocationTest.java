package io.wally.spsc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs under Epsilon GC with a 128 MB heap ({@code epsilonTest} task): 16 bytes allocated per cycle
 * would need 800 MB, so any per-op allocation fails the run with OutOfMemoryError.
 */
@Tag("epsilon")
class ZeroAllocationTest {

    private static final long CYCLES = 50_000_000L;

    private static final class SumHandler implements MessageHandler {
        long sum;

        @Override
        public void onMessage(MemorySegment buffer, long offset, int length, long sequence) {
            sum += buffer.get(JAVA_LONG, offset);
        }
    }

    @Test
    void writeReadCyclesDoNotAllocate() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(64 * 1024));
        Writer w = ring.writer();
        Reader r = ring.reader();
        SumHandler handler = new SumHandler();

        for (long i = 0; i < CYCLES; i++) {
            if (!w.tryClaim(8)) {
                throw new IllegalStateException("ring full at " + i);
            }
            w.buffer().set(JAVA_LONG, w.offset(), i);
            w.commit();
            if (r.read(handler, 1) != 1) {
                throw new IllegalStateException("no message at " + i);
            }
        }

        assertEquals(CYCLES * (CYCLES - 1) / 2, handler.sum);
    }
}
