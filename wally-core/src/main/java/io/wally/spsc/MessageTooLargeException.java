package io.wally.spsc;

/**
 * Thrown when a message is too large to ever fit in the ring buffer.
 */
public class MessageTooLargeException extends RingBufferException {
    private static final long serialVersionUID = 1L;

    private final int messageLength;
    private final int capacity;

    public MessageTooLargeException(int messageLength, int capacity, String reason) {
        super(String.format("Message too large: %d bytes, capacity %d. %s",
            messageLength, capacity, reason));
        this.messageLength = messageLength;
        this.capacity = capacity;
    }

    public int getMessageLength() { return messageLength; }
    public int getCapacity() { return capacity; }
}
