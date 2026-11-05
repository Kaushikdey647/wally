package io.wally.internal.log;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.wally.internal.log.SegmentWriter.NO_SPACE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentLogTest {

    private static final long START = 64;
    private static final long BASE = 1L << 20;

    private final Arena arena = Arena.ofAuto();
    private final SegmentWriter writer = new SegmentWriter();
    private final SegmentReader reader = new SegmentReader();

    private MemorySegment segment(long size) {
        MemorySegment buffer = arena.allocate(size, 4096);
        writer.wrap(buffer, START, BASE);
        reader.wrap(buffer, START, BASE);
        return buffer;
    }

    private List<byte[]> readAll() {
        List<byte[]> out = new ArrayList<>();
        reader.read((buf, off, len, pos) -> out.add(buf.asSlice(off, len).toArray(ValueLayout.JAVA_BYTE)), Integer.MAX_VALUE);
        return out;
    }

    @Test
    void roundTripsRecordsWithAlignedPositions() {
        segment(4096);
        assertEquals(BASE + START, writer.append(new byte[] {1, 2, 3}, 0, 3));
        assertEquals(BASE + START + 24, writer.append(new byte[0], 0, 0));     // 16 + 3 -> 24
        assertEquals(BASE + START + 40, writer.append(new byte[] {9}, 0, 1));  // 16 + 0 -> 16

        List<byte[]> records = readAll();
        assertEquals(3, records.size());
        assertArrayEquals(new byte[] {1, 2, 3}, records.get(0));
        assertArrayEquals(new byte[0], records.get(1));
        assertArrayEquals(new byte[] {9}, records.get(2));
        assertEquals(writer.tailPosition(), reader.position());
    }

    @Test
    void readerStopsAtUncommittedClaim() {
        segment(4096);
        writer.append(new byte[] {1}, 0, 1);
        long position = writer.tryClaim(8);
        assertEquals(1, readAll().size());
        assertEquals(position, reader.position());

        writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), 42L);
        writer.commit();
        List<Long> values = new ArrayList<>();
        reader.read((buf, off, len, pos) -> values.add(buf.get(ValueLayout.JAVA_LONG, off)), 10);
        assertEquals(List.of(42L), values);
    }

    @Test
    void abortedClaimIsSkipped() {
        segment(4096);
        writer.tryClaim(100);
        writer.abort();
        writer.append(new byte[] {7}, 0, 1);
        List<byte[]> records = readAll();
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] {7}, records.getFirst());
    }

    @Test
    void reportsNoSpaceAndPadsToEnd() {
        segment(256);                                           // 192 usable bytes
        assertTrue(writer.append(new byte[100], 0, 100) >= 0);  // 120 bytes
        assertEquals(NO_SPACE, writer.append(new byte[100], 0, 100));
        assertTrue(writer.append(new byte[40], 0, 40) >= 0);    // 56 bytes, 16 left
        writer.padToEnd();

        assertEquals(2, readAll().size());
        assertTrue(reader.isAtEnd());
    }

    @Test
    void paddingFitsInEightBytes() {
        segment(4096);
        writer.append(new byte[4096 - 64 - 16 - 8], 0, 4096 - 64 - 16 - 8);
        writer.padToEnd();
        assertEquals(1, readAll().size());
        assertTrue(reader.isAtEnd());
    }

    @Test
    void readLimitIsRespected() {
        segment(4096);
        for (int i = 0; i < 5; i++) {
            writer.append(new byte[] {(byte) i}, 0, 1);
        }
        int[] seen = new int[1];
        assertEquals(2, reader.read((buf, off, len, pos) -> seen[0]++, 2));
        assertEquals(3, reader.read((buf, off, len, pos) -> seen[0]++, 10));
        assertEquals(5, seen[0]);
    }

    @Test
    void failingHandlerDoesNotRedeliver() {
        segment(4096);
        writer.append(new byte[] {1}, 0, 1);
        writer.append(new byte[] {2}, 0, 1);
        assertThrows(IllegalStateException.class, () -> reader.read((b, o, l, p) -> { throw new IllegalStateException(); }, 10));
        List<byte[]> rest = readAll();
        assertEquals(1, rest.size());
        assertArrayEquals(new byte[] {2}, rest.getFirst());
    }

    @Test
    void rejectsInvalidUsage() {
        segment(256);
        assertThrows(IllegalArgumentException.class, () -> writer.tryClaim(-1));
        assertThrows(IllegalArgumentException.class, () -> writer.tryClaim(writer.maxPayloadLength() + 1));
        assertThrows(IllegalStateException.class, writer::commit);
        writer.tryClaim(1);
        assertThrows(IllegalStateException.class, () -> writer.tryClaim(1));
        assertThrows(IllegalArgumentException.class, () -> writer.wrap(arena.allocate(100), 0, 0));
    }

    @Test
    void readerOnOtherThreadSeesEveryRecordInOrder() throws Exception {
        int count = 1_000_000;
        MemorySegment buffer = arena.allocate(START + (long) count * 24, 4096);
        writer.wrap(buffer, START, 0);
        reader.wrap(buffer, START, 0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread consumer = Thread.ofPlatform().start(() -> {
            long[] expected = {0};
            try {
                while (expected[0] < count) {
                    reader.read((buf, off, len, pos) -> {
                        long value = buf.get(ValueLayout.JAVA_LONG, off);
                        if (value != expected[0]++) {
                            throw new AssertionError("expected " + (expected[0] - 1) + " got " + value);
                        }
                    }, 256);
                    Thread.onSpinWait();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        MemorySegment src = arena.allocate(8);
        for (long i = 0; i < count; i++) {
            src.set(ValueLayout.JAVA_LONG, 0, i);
            writer.append(src, 0, 8);
        }
        consumer.join(30_000);
        assertNull(failure.get());
        assertEquals(writer.tailPosition(), reader.position());
    }
}
