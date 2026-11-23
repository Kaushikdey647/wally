package io.wally.spsc;

import java.lang.foreign.MemorySegment;

/**
 * Producer interface for writing to a SPSC ring buffer.
 *
 * <p>Must be used by exactly one thread. Not thread-safe.
 */
public interface Writer {

    /**
     * Try to claim space for a message.
     *
     * <p>Returns {@code true} if space is available. You must then write your data
     * via {@link #buffer()}/{@link #offset()} and call {@link #commit()}.
     *
     * <p>If this returns {@code false}, the ring is full. The consumer must drain
     * messages before more can be written.
     *
     * @param length message length in bytes (must be positive)
     * @return {@code true} if claimed, {@code false} if no space
     */
    boolean tryClaim(int length);

    /**
     * @return the ring buffer memory
     */
    MemorySegment buffer();

    /**
     * @return byte offset where the claimed message should be written
     */
    long offset();

    /**
     * @return length of the claimed message
     */
    int length();

    /**
     * Commit the claimed message, making it visible to the consumer.
     *
     * <p>Must be called exactly once after a successful {@link #tryClaim(int)}.
     */
    void commit();

    /**
     * Write a message by copying from a byte array.
     *
     * @param src    source array
     * @param srcPos starting position in source
     * @param length number of bytes to write
     * @return {@code true} if written, {@code false} if no space
     */
    boolean write(byte[] src, int srcPos, int length);

    /**
     * Write a message by copying from a memory segment.
     *
     * @param src       source segment
     * @param srcOffset starting offset in source
     * @param length    number of bytes to write
     * @return {@code true} if written, {@code false} if no space
     */
    boolean write(MemorySegment src, long srcOffset, int length);
}
