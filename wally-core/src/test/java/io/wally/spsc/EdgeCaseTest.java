package io.wally.spsc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

class EdgeCaseTest {

    // Null inputs
    @Test
    void writeNullByteArray() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();

        NullPointerException ex = assertThrows(NullPointerException.class,
            () -> writer.write((byte[]) null, 0, 10));
        assertTrue(ex.getMessage().contains("src"));
    }

    @Test
    void writeNullMemorySegment() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();

        NullPointerException ex = assertThrows(NullPointerException.class,
            () -> writer.write((MemorySegment) null, 0, 10));
        assertTrue(ex.getMessage().contains("segment"));
    }

    @Test
    void nullHandler() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Reader reader = ring.reader();

        NullPointerException ex = assertThrows(NullPointerException.class,
            () -> reader.read(null, 10));
        assertTrue(ex.getMessage().contains("handler"));
    }

    // Negative/zero inputs
    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void tryClaimInvalidLength(int length) {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> writer.tryClaim(length));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void readInvalidLimit(int limit) {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Reader reader = ring.reader();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> reader.read((buf, off, len, seq) -> {}, limit));
        assertTrue(ex.getMessage().contains("positive"));
    }

    // Array bounds
    @Test
    void writeNegativeOffset() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        byte[] data = new byte[10];

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> writer.write(data, -1, 5));
        assertTrue(ex.getMessage().contains("srcPos"));
    }

    @Test
    void writeOffsetPlusLengthExceedsArray() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        byte[] data = new byte[10];

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> writer.write(data, 5, 10));  // 5 + 10 > 10
        assertTrue(ex.getMessage().contains("exceeds array length"));
    }

    // Boundary conditions
    @Test
    void messageExactlyFillsCapacity() {
        int capacity = 256;
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(capacity));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        // Header (8) + payload (240) + padding (8) = 256
        int maxPayload = capacity - 8 - 8;

        assertTrue(writer.tryClaim(maxPayload));
        writer.commit();

        int read = reader.read((buf, off, len, seq) -> {
            assertEquals(maxPayload, len);
        }, 1);
        assertEquals(1, read);
    }

    @Test
    void messageLargerThanCapacity() {
        int capacity = 256;
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(capacity));
        Writer writer = ring.writer();

        MessageTooLargeException ex = assertThrows(MessageTooLargeException.class,
            () -> writer.tryClaim(capacity));  // Definitely too large

        assertEquals(capacity, ex.getMessageLength());
        assertEquals(capacity, ex.getCapacity());
    }

    @Test
    void emptyRing() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Reader reader = ring.reader();

        int read = reader.read((buf, off, len, seq) -> {
            fail("Should not call handler on empty ring");
        }, 10);

        assertEquals(0, read);
        assertEquals(0, reader.available());
    }

    // Alignment edge cases
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 15, 16, 17})
    void variousAlignments(int messageLength) {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        assertTrue(writer.tryClaim(messageLength));
        // Write pattern for verification
        for (int i = 0; i < messageLength; i++) {
            writer.buffer().set(ValueLayout.JAVA_BYTE, writer.offset() + i, (byte) i);
        }
        writer.commit();

        int[] receivedLength = {0};
        int read = reader.read((buf, off, len, seq) -> {
            receivedLength[0] = len;
            // Verify pattern
            for (int i = 0; i < len; i++) {
                assertEquals((byte) i, buf.get(ValueLayout.JAVA_BYTE, off + i));
            }
        }, 1);

        assertEquals(1, read);
        assertEquals(messageLength, receivedLength[0]);
    }

    // Handler exception
    @Test
    void handlerThrowsException() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        // Write 3 messages
        for (int i = 0; i < 3; i++) {
            assertTrue(writer.tryClaim(8));
            writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), i);
            writer.commit();
        }

        // Handler throws on message 1
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            reader.read((buf, off, len, seq) -> {
                long value = buf.get(ValueLayout.JAVA_LONG, off);
                if (value == 1) {
                    throw new RuntimeException("Test exception");
                }
            }, 10);
        });

        // Verify: message 0 consumed, message 1 triggered exception, head advanced
        // Next read should start at message 2
        int read = reader.read((buf, off, len, seq) -> {
            long value = buf.get(ValueLayout.JAVA_LONG, off);
            assertEquals(2, value);  // Should be message 2, not message 1
        }, 1);

        assertEquals(1, read);
    }

    // Overflow checks
    @Test
    void integerOverflowProtection() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();

        // Try to trigger overflow in frame size calculation
        MessageTooLargeException ex = assertThrows(MessageTooLargeException.class,
            () -> writer.tryClaim(Integer.MAX_VALUE - 10));
        assertTrue(ex.getMessage().contains("overflow"));
    }
}
