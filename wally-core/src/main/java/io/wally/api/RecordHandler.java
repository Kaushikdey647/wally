package io.wally.api;

import java.lang.foreign.MemorySegment;

/**
 * Callback for records read from the log.
 *
 * <p>The payload is {@code buffer[offset, offset + length)}. The buffer is only valid for the duration
 * of the callback; copy anything that must outlive it.
 */
@FunctionalInterface
public interface RecordHandler {
    void onRecord(MemorySegment buffer, long offset, int length, long position);
}
