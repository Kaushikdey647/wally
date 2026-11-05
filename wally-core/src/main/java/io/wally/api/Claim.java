package io.wally.api;

import java.lang.foreign.MemorySegment;

/**
 * Space reserved in the log that the caller fills in place, then publishes with {@link #commit()}
 * or discards with {@link #abort()}.
 *
 * <p>Claims are reused flyweights: accessors are only valid until {@code commit} or {@code abort}.
 */
public interface Claim {

    /** Buffer to write the payload into. */
    MemorySegment buffer();

    /** Offset of the payload within {@link #buffer()}. */
    long offset();

    /** Payload length in bytes. */
    int length();

    /** Log position of the record. */
    long position();

    /** Publishes the record to readers. */
    void commit();

    /** Discards the record; readers skip it. */
    void abort();
}
