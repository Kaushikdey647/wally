package io.wally.spsc;

import io.wally.internal.memory.Mem;
import io.wally.spsc.internal.Cursors;

import java.lang.foreign.MemorySegment;

/**
 * Lock-free SPSC (Single Producer Single Consumer) circular buffer.
 *
 * <p>Features:
 * <ul>
 *   <li>Zero allocation after warmup
 *   <li>Cache-line padded cursors to prevent false sharing
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
 * <p>Message format: [4-byte length][payload bytes]
 * <br>Total space per message: length + 4 bytes
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
 * r.read((buf, off, len, seq) -> {
 *     long value = buf.get(ValueLayout.JAVA_LONG, off);
 * }, 100);
 * }</pre>
 */
public final class RingBuffer {

    private static final int HEADER_SIZE = 8;  // Length (4 bytes) + padding (4 bytes)
    private static final int ALIGNMENT = 8;    // All frames aligned to 8 bytes

    private final MemorySegment memory;
    private final int capacity;
    private final int mask;
    private final long headOffset;
    private final long tailOffset;
    private final long dataOffset;

    private final WriterImpl writer = new WriterImpl();
    private final ReaderImpl reader = new ReaderImpl();

    public RingBuffer(RingBufferConfig config) {
        this.capacity = config.capacity();
        this.mask = config.mask();
        this.memory = Cursors.allocate(capacity, config.arena());
        this.headOffset = Cursors.headOffset();
        this.tailOffset = Cursors.tailOffset();
        this.dataOffset = Cursors.dataOffset();

        // Initialize cursors to 0
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

    private final class WriterImpl implements Writer {

        private long claimedTail;
        private int claimedLength;
        private long cachedHead;

        @Override
        public boolean tryClaim(int length) {
            if (length <= 0) {
                throw new IllegalArgumentException("length must be positive, was: " + length);
            }

            // Check overflow before alignment calculation
            if (length > Integer.MAX_VALUE - HEADER_SIZE - ALIGNMENT) {
                throw new MessageTooLargeException(length, capacity,
                    "length would overflow frame size calculation");
            }

            // Frame = header (8 bytes: length + padding) + payload, aligned to 8 bytes
            int frameSize = HEADER_SIZE + io.wally.internal.memory.Align.align(length, ALIGNMENT);

            if (frameSize > capacity) {
                throw new MessageTooLargeException(length, capacity,
                    "frame size " + frameSize + " exceeds capacity");
            }

            long tail = Mem.getLongAcquire(memory, tailOffset);
            int tailIndex = (int) (tail & mask);

            // Check if we need to wrap
            if (tailIndex + frameSize > capacity) {
                // Message would cross ring boundary — write sentinel and wrap
                long sentinelPos = dataOffset + tailIndex;
                memory.set(java.lang.foreign.ValueLayout.JAVA_INT, sentinelPos, 0);

                // Start from beginning of next lap
                tail = (tail & ~mask) + capacity;
                tailIndex = 0;
            }

            // Check space available
            long head = cachedHead;
            long requiredSpace = tail + frameSize;

            if (requiredSpace - head > capacity) {
                // Refresh cached head
                head = Mem.getLongAcquire(memory, headOffset);
                cachedHead = head;

                if (requiredSpace - head > capacity) {
                    return false;  // Still full
                }
            }

            // Claim succeeded
            this.claimedTail = tail;
            this.claimedLength = length;  // Store actual user length

            // Write length field at frame start
            long framePos = dataOffset + tailIndex;
            memory.set(java.lang.foreign.ValueLayout.JAVA_INT, framePos, length);

            return true;
        }

        @Override
        public MemorySegment buffer() {
            return memory;
        }

        @Override
        public long offset() {
            int index = (int) (claimedTail & mask);
            return dataOffset + index + HEADER_SIZE;
        }

        @Override
        public int length() {
            return claimedLength;
        }

        @Override
        public void commit() {
            int frameSize = HEADER_SIZE + io.wally.internal.memory.Align.align(claimedLength, ALIGNMENT);
            long newTail = claimedTail + frameSize;
            Mem.setLongRelease(memory, tailOffset, newTail);
        }

        @Override
        public boolean write(byte[] src, int srcPos, int length) {
            // Validate inputs
            if (src == null) {
                throw new NullPointerException("src array must not be null");
            }
            if (srcPos < 0) {
                throw new IllegalArgumentException("srcPos must be non-negative, was: " + srcPos);
            }
            if (length < 0) {
                throw new IllegalArgumentException("length must be non-negative, was: " + length);
            }
            if (srcPos + length > src.length) {
                throw new IllegalArgumentException(
                    String.format("srcPos (%d) + length (%d) exceeds array length (%d)",
                        srcPos, length, src.length));
            }

            if (!tryClaim(length)) {
                return false;
            }
            MemorySegment.copy(MemorySegment.ofArray(src), srcPos, memory, offset(), length);
            commit();
            return true;
        }

        @Override
        public boolean write(MemorySegment src, long srcOffset, int length) {
            // Validate inputs
            if (src == null) {
                throw new NullPointerException("src segment must not be null");
            }
            if (srcOffset < 0) {
                throw new IllegalArgumentException("srcOffset must be non-negative, was: " + srcOffset);
            }
            if (length < 0) {
                throw new IllegalArgumentException("length must be non-negative, was: " + length);
            }
            if (srcOffset + length > src.byteSize()) {
                throw new IllegalArgumentException(
                    String.format("srcOffset (%d) + length (%d) exceeds segment size (%d)",
                        srcOffset, length, src.byteSize()));
            }

            if (!tryClaim(length)) {
                return false;
            }
            MemorySegment.copy(src, srcOffset, memory, offset(), length);
            commit();
            return true;
        }
    }

    private final class ReaderImpl implements Reader {

        private long head;
        private long cachedTail;

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

            try {
                while (count < limit) {
                    long tail = Mem.getLongAcquire(memory, tailOffset);

                    if (currentHead >= tail) {
                        // No more messages
                        cachedTail = tail;
                        break;
                    }

                    int headIndex = (int) (currentHead & mask);
                    long framePos = dataOffset + headIndex;

                    // Read length field
                    int length = memory.get(java.lang.foreign.ValueLayout.JAVA_INT, framePos);

                    if (length == 0) {
                        // Wrap sentinel — jump to start of next lap
                        currentHead = (currentHead & ~mask) + capacity;
                        continue;
                    }

                    // Calculate next frame position
                    int frameSize = HEADER_SIZE + io.wally.internal.memory.Align.align(length, ALIGNMENT);

                    // Advance currentHead BEFORE handler call
                    // This ensures the finally block saves the advanced position even if handler throws
                    long messageSequence = currentHead;
                    currentHead += frameSize;

                    // Deliver message (use messageSequence which is the position of THIS message)
                    long payloadOffset = framePos + HEADER_SIZE;
                    handler.onMessage(memory, payloadOffset, length, messageSequence);

                    count++;
                }
            } finally {
                // Update head even if handler threw
                head = currentHead;
                Mem.setLongRelease(memory, headOffset, currentHead);
            }

            return count;
        }

        @Override
        public long available() {
            long tail = Mem.getLongAcquire(memory, tailOffset);
            return tail - head;
        }

        @Override
        public long sequence() {
            return head;
        }
    }
}
