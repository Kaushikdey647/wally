package io.wally.spsc;

import io.wally.internal.memory.Align;
import io.wally.internal.memory.Mem;
import io.wally.spsc.internal.Cursors;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Lock-free SPSC (Single Producer Single Consumer) circular buffer.
 *
 * <p>Features:
 * <ul>
 *   <li>Zero allocation after warmup
 *   <li>Cache-line padded cursors (off-heap) and writer/reader state (on-heap) to prevent false sharing
 *   <li>Acquire/release memory ordering for correctness
 *   <li>Power-of-two capacity for fast modulo via masking
 * </ul>
 *
 * <p><b>Usage contract:</b>
 * <ul>
 *   <li>Exactly one thread uses {@link #writer()}
 *   <li>Exactly one thread uses {@link #reader()}
 *   <li>Different threads must not share Writer/Reader instances
 * </ul>
 *
 * <p>Frame format: [4-byte length][4-byte pad][payload], padded to a multiple of 8 bytes.
 * A length of 0 is a wrap sentinel: the rest of the lap is unused and the next frame starts at index 0.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(64 * 1024));
 * Writer w = ring.writer();
 * Reader r = ring.reader();
 *
 * // Producer thread
 * if (w.tryClaim(8)) {
 *     w.buffer().set(ValueLayout.JAVA_LONG, w.offset(), 42L);
 *     w.commit();
 * }
 *
 * // Consumer thread
 * r.read((buf, off, len, pos) -> {
 *     long value = buf.get(ValueLayout.JAVA_LONG, off);
 * }, 100);
 * }</pre>
 */
public final class RingBuffer {

    private static final int HEADER_SIZE = 8;  // Length (4 bytes) + padding (4 bytes)
    private static final int ALIGNMENT = 8;    // All frames aligned to 8 bytes

    private final MemorySegment memory;
    private final MemorySegment data;
    private final int capacity;
    private final int mask;
    private final long headOffset;
    private final long tailOffset;

    private final WriterImpl writer = new WriterImpl();
    private final ReaderImpl reader = new ReaderImpl();

    public RingBuffer(RingBufferConfig config) {
        this.capacity = config.capacity();
        this.mask = config.mask();
        this.memory = Cursors.allocate(capacity, config.arena());
        this.data = memory.asSlice(Cursors.dataOffset(), capacity);
        this.headOffset = Cursors.headOffset();
        this.tailOffset = Cursors.tailOffset();

        Mem.setLongRelease(memory, headOffset, 0L);
        Mem.setLongRelease(memory, tailOffset, 0L);
    }

    public Writer writer() {
        return writer;
    }

    public Reader reader() {
        return reader;
    }

    /**
     * @return ring buffer capacity in bytes
     */
    public int capacity() {
        return capacity;
    }

    // Inheritance-based padding: HotSpot lays out superclass fields first, so the hot fields sit
    // between two 128-byte pads and can't share a cache line with a neighbouring object.
    abstract static class Pad128 {
        long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;
    }

    abstract static class WriterFields extends Pad128 {
        long tail;
        long claimedTail;
        long claimedNextTail;
        long cachedHead;
    }

    abstract static class ReaderFields extends Pad128 {
        long head;
        long cachedTail;
    }

    private final class WriterImpl extends WriterFields implements Writer {
        long q00, q01, q02, q03, q04, q05, q06, q07, q08, q09, q10, q11, q12, q13, q14, q15;

        @Override
        public boolean tryClaim(int length) {
            if (length <= 0) {
                throw new IllegalArgumentException("length must be positive, was: " + length);
            }
            if (length > Integer.MAX_VALUE - HEADER_SIZE - ALIGNMENT) {
                throw new MessageTooLargeException(length, capacity,
                    "length would overflow frame size calculation");
            }

            int frameSize = HEADER_SIZE + Align.align(length, ALIGNMENT);
            if (frameSize > capacity) {
                throw new MessageTooLargeException(length, capacity,
                    "frame size " + frameSize + " exceeds capacity");
            }

            long tail = this.tail;
            int tailIndex = (int) (tail & mask);

            if (tailIndex + frameSize > capacity) {
                // The sentinel may only be written once the gap [tail, nextLap) is known to be free,
                // otherwise it can overwrite the header of an unread frame. The skip is published on
                // its own so a frame larger than both tailIndex and the gap can still be placed later.
                long nextLap = (tail & ~mask) + capacity;
                if (!hasSpace(nextLap)) {
                    return false;
                }
                data.set(JAVA_INT, tailIndex, 0);
                this.tail = tail = nextLap;
                tailIndex = 0;
                Mem.setLongRelease(memory, tailOffset, tail);
            }

            long newTail = tail + frameSize;
            if (!hasSpace(newTail)) {
                return false;
            }
            data.set(JAVA_INT, tailIndex, length);

            claimedTail = tail;
            claimedNextTail = newTail;
            return true;
        }

        private boolean hasSpace(long newTail) {
            if (newTail - cachedHead <= capacity) {
                return true;
            }
            cachedHead = Mem.getLongAcquire(memory, headOffset);
            return newTail - cachedHead <= capacity;
        }

        @Override
        public MemorySegment buffer() {
            return data;
        }

        @Override
        public long offset() {
            return (claimedTail & mask) + HEADER_SIZE;
        }

        @Override
        public int length() {
            return data.get(JAVA_INT, claimedTail & mask);
        }

        @Override
        public void commit() {
            tail = claimedNextTail;
            Mem.setLongRelease(memory, tailOffset, tail);
        }

        @Override
        public boolean write(byte[] src, int srcPos, int length) {
            if (src == null) {
                throw new NullPointerException("src array must not be null");
            }
            if (srcPos < 0) {
                throw new IllegalArgumentException("srcPos must be non-negative, was: " + srcPos);
            }
            if (length <= 0) {
                throw new IllegalArgumentException("length must be positive, was: " + length);
            }
            if (srcPos + length > src.length) {
                throw new IllegalArgumentException(
                    String.format("srcPos (%d) + length (%d) exceeds array length (%d)",
                        srcPos, length, src.length));
            }

            if (!tryClaim(length)) {
                return false;
            }
            MemorySegment.copy(MemorySegment.ofArray(src), srcPos, data, offset(), length);
            commit();
            return true;
        }

        @Override
        public boolean write(MemorySegment src, long srcOffset, int length) {
            if (src == null) {
                throw new NullPointerException("src segment must not be null");
            }
            if (srcOffset < 0) {
                throw new IllegalArgumentException("srcOffset must be non-negative, was: " + srcOffset);
            }
            if (length <= 0) {
                throw new IllegalArgumentException("length must be positive, was: " + length);
            }
            if (srcOffset + length > src.byteSize()) {
                throw new IllegalArgumentException(
                    String.format("srcOffset (%d) + length (%d) exceeds segment size (%d)",
                        srcOffset, length, src.byteSize()));
            }

            if (!tryClaim(length)) {
                return false;
            }
            MemorySegment.copy(src, srcOffset, data, offset(), length);
            commit();
            return true;
        }
    }

    private final class ReaderImpl extends ReaderFields implements Reader {
        long q00, q01, q02, q03, q04, q05, q06, q07, q08, q09, q10, q11, q12, q13, q14, q15;

        @Override
        public int read(MessageHandler handler, int limit) {
            if (handler == null) {
                throw new NullPointerException("handler must not be null");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive, was: " + limit);
            }

            int count = 0;
            long currentHead = head;
            long tail = cachedTail;

            try {
                while (count < limit) {
                    if (currentHead >= tail) {
                        tail = Mem.getLongAcquire(memory, tailOffset);
                        cachedTail = tail;
                        if (currentHead >= tail) {
                            break;
                        }
                    }

                    int headIndex = (int) (currentHead & mask);
                    int length = data.get(JAVA_INT, headIndex);

                    if (length == 0) {
                        currentHead = (currentHead & ~mask) + capacity;
                        continue;
                    }

                    // Advance before the callback so a throwing handler still consumes the frame
                    long position = currentHead;
                    currentHead += HEADER_SIZE + Align.align(length, ALIGNMENT);

                    handler.onMessage(data, headIndex + HEADER_SIZE, length, position);
                    count++;
                }
            } finally {
                head = currentHead;
                Mem.setLongRelease(memory, headOffset, currentHead);
            }

            return count;
        }

        @Override
        public long available() {
            return Mem.getLongAcquire(memory, tailOffset) - head;
        }

        @Override
        public long sequence() {
            return head;
        }
    }
}
