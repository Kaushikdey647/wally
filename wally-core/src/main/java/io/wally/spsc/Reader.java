package io.wally.spsc;

/**
 * Consumer interface for reading from a SPSC ring buffer.
 *
 * <p>Must be used by exactly one thread. Not thread-safe.
 */
public interface Reader {

    /**
     * Read available messages, up to the specified limit.
     *
     * <p>Calls {@code handler.onMessage(...)} for each message in order.
     * Advances the read position even if the handler throws.
     *
     * @param handler callback for each message
     * @param limit   maximum number of messages to read (must be positive)
     * @return number of messages actually read
     */
    int read(MessageHandler handler, int limit);

    /**
     * @return committed bytes not yet consumed, including frame headers, padding and wrap gaps
     *         (not a message count)
     */
    long available();

    /**
     * @return byte position of the next frame to read; monotonic, but not contiguous across
     *         messages (it advances by frame size and skips wrap gaps)
     */
    long sequence();
}
