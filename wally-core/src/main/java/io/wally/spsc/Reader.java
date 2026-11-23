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
     * @return number of messages available to read
     */
    long available();

    /**
     * @return monotonic sequence number of the next message that will be read
     */
    long sequence();
}
