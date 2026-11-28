package io.wally.spsc;

/**
 * Thrown when attempting to write to a full ring buffer (non-blocking variant).
 *
 * <p>This is an alternative to {@link Writer#tryClaim(int)} returning {@code false}.
 * Use when exceptions are preferred over boolean returns for control flow.
 */
public class RingBufferFullException extends RingBufferException {
    private static final long serialVersionUID = 1L;

    private final int requestedLength;
    private final int capacity;
    private final long availableSpace;

    public RingBufferFullException(int requestedLength, int capacity, long availableSpace) {
        super(String.format("Ring buffer full: requested %d bytes, capacity %d, available %d",
            requestedLength, capacity, availableSpace));
        this.requestedLength = requestedLength;
        this.capacity = capacity;
        this.availableSpace = availableSpace;
    }

    public int getRequestedLength() { return requestedLength; }
    public int getCapacity() { return capacity; }
    public long getAvailableSpace() { return availableSpace; }
}
