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
     * @param buffer  the ring buffer memory
     * @param offset  byte offset of the message
     * @param length  message length in bytes
     * @param sequence monotonic sequence number (starts at 0)
     */
    void onMessage(MemorySegment buffer, long offset, int length, long sequence);
}
