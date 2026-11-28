package io.wally.spsc;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldLayout;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Writer and reader state is written by different threads on every op; each must own its cache lines. */
class RingBufferLayoutTest {

    private static final int PAD = 128;

    @Test
    void writerHotFieldsArePadded() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        assertPadded(ring.writer(), Set.of("tail", "claimedTail", "claimedNextTail", "cachedHead"));
    }

    @Test
    void readerHotFieldsArePadded() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        assertPadded(ring.reader(), Set.of("head", "cachedTail"));
    }

    private static void assertPadded(Object instance, Set<String> hot) {
        ClassLayout layout = ClassLayout.parseInstance(instance);
        long first = Long.MAX_VALUE;
        long end = 0;
        int found = 0;
        for (FieldLayout f : layout.fields()) {
            if (hot.contains(f.name())) {
                first = Math.min(first, f.offset());
                end = Math.max(end, f.offset() + f.size());
                found++;
            }
        }
        String printable = layout.toPrintable();
        assertEquals(hot.size(), found, printable);
        assertTrue(first >= PAD, "hot fields start at " + first + "\n" + printable);
        assertTrue(layout.instanceSize() - end >= PAD, "hot fields end at " + end + "\n" + printable);
    }
}
