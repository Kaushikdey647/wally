package io.wally.spsc;

/**
 * Base exception for ring buffer operations.
 */
public class RingBufferException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RingBufferException(String message) {
        super(message);
    }

    public RingBufferException(String message, Throwable cause) {
        super(message, cause);
    }
}
