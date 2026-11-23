package io.wally.spsc;

import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RingBufferTest {

    @Test
    void writeAndRead() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        // Write
        assertTrue(writer.tryClaim(16));
        writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), 42L);
        writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset() + 8, 100L);
        writer.commit();

        // Read
        AtomicLong value1 = new AtomicLong();
        AtomicLong value2 = new AtomicLong();

        int read = reader.read((buf, off, len, seq) -> {
            value1.set(buf.get(ValueLayout.JAVA_LONG, off));
            value2.set(buf.get(ValueLayout.JAVA_LONG, off + 8));
        }, 1);

        assertEquals(1, read);
        assertEquals(42L, value1.get());
        assertEquals(100L, value2.get());
    }

    @Test
    void multipleMessages() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        // Write 10 messages
        for (int i = 0; i < 10; i++) {
            assertTrue(writer.tryClaim(8));
            writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), i);
            writer.commit();
        }

        // Read all
        AtomicLong sum = new AtomicLong();
        int read = reader.read((buf, off, len, seq) -> {
            sum.addAndGet(buf.get(ValueLayout.JAVA_LONG, off));
        }, 100);

        assertEquals(10, read);
        assertEquals(45, sum.get());  // 0+1+2+...+9 = 45
    }

    @Test
    void ringWraps() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        // Write and read in batches to exercise wrapping
        // Frame size: 8 (header) + 8 (payload) = 16 bytes
        int batchSize = 10;
        int batches = 10;
        AtomicLong sum = new AtomicLong();

        for (int batch = 0; batch < batches; batch++) {
            // Write batch
            for (int i = 0; i < batchSize; i++) {
                int value = batch * batchSize + i;
                assertTrue(writer.tryClaim(8));
                writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), value);
                writer.commit();
            }

            // Read batch
            int read = reader.read((buf, off, len, seq) -> {
                sum.addAndGet(buf.get(ValueLayout.JAVA_LONG, off));
            }, batchSize);
            assertEquals(batchSize, read);
        }

        assertEquals(4950, sum.get());  // 0+1+2+...+99 = 4950
    }

    @Test
    void backpressure() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(256));
        Writer writer = ring.writer();

        // Fill ring (256 bytes capacity, 12 bytes per message = ~21 messages)
        int written = 0;
        while (writer.tryClaim(8)) {
            writer.commit();
            written++;
        }

        assertTrue(written > 0);
        assertFalse(writer.tryClaim(8), "Should be full");
    }

    @Test
    void concurrentProducerConsumer() throws Exception {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(64 * 1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        int messageCount = 100_000;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong consumedSum = new AtomicLong();

        // Consumer thread
        Thread consumer = new Thread(() -> {
            long received = 0;
            while (received < messageCount) {
                received += reader.read((buf, off, len, seq) -> {
                    consumedSum.addAndGet(buf.get(ValueLayout.JAVA_LONG, off));
                }, 1000);
            }
            latch.countDown();
        });
        consumer.start();

        // Producer thread (main)
        long expectedSum = 0;
        for (int i = 0; i < messageCount; i++) {
            while (!writer.tryClaim(8)) {
                Thread.onSpinWait();
            }
            writer.buffer().set(ValueLayout.JAVA_LONG, writer.offset(), i);
            writer.commit();
            expectedSum += i;
        }

        latch.await();
        consumer.join();

        assertEquals(expectedSum, consumedSum.get());
    }

    @Test
    void writeByteArray() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(1024));
        Writer writer = ring.writer();
        Reader reader = ring.reader();

        byte[] data = {1, 2, 3, 4, 5};
        assertTrue(writer.write(data, 0, data.length));

        AtomicLong sum = new AtomicLong();
        reader.read((buf, off, len, seq) -> {
            assertEquals(5, len);
            for (int i = 0; i < len; i++) {
                sum.addAndGet(buf.get(ValueLayout.JAVA_BYTE, off + i));
            }
        }, 1);

        assertEquals(15, sum.get());  // 1+2+3+4+5
    }
}
