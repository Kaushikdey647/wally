package io.wally.spsc;

/**
 * Thrown when attempting to use a closed ring buffer.
 */
public class RingBufferClosedException extends RingBufferException {
    private static final long serialVersionUID = 1L;

    public RingBufferClosedException() {
        super("Ring buffer has been closed");
    }
}
