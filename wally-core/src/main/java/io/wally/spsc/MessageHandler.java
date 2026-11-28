package io.wally.spsc;

import java.lang.foreign.MemorySegment;

/**
 * Callback for messages read from the ring buffer.
 *
 * <p>The message is {@code buffer[offset, offset + length)}. The buffer is only valid
 * during the callback; copy any data that must outlive it.
 *
 * <p>For zero-allocation operation, implement this interface on a reusable class
 * rather than using a capturing lambda.
 */
@FunctionalInterface
public interface MessageHandler {
    /**
     * Process one message.
     *
     * @param buffer   the ring's data region (excludes the cursors)
     * @param offset   byte offset of the payload within {@code buffer}
     * @param length   message length in bytes
     * @param sequence byte position of the frame; monotonic from 0, not contiguous across messages
     */
    void onMessage(MemorySegment buffer, long offset, int length, long sequence);
}
