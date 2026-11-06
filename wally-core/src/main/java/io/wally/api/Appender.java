package io.wally.api;

import io.wally.internal.log.SegmentWriter;
import io.wally.internal.segment.SegmentDescriptor;
import io.wally.internal.segment.SegmentFile;
import io.wally.internal.segment.SegmentManager;

import java.lang.foreign.MemorySegment;

/**
 * Write handle for appending records to the write-ahead log.
 *
 * <p><b>Thread Safety:</b> Not thread-safe. Create one appender per producer thread. Multi-writer
 * support (M3) will use sequence coordination to interleave writes from multiple appenders.
 *
 * <p><b>Write Modes:</b>
 * <ul>
 *   <li><b>Copy Mode:</b> {@link #append(MemorySegment, long, int)} or {@link #append(byte[], int, int)}
 *       copies data into the log and returns the position
 *   <li><b>Zero-Copy Mode:</b> {@link #tryClaim(int)} reserves space, caller writes directly via
 *       {@link Claim#buffer()}/{@link Claim#offset()}, then calls {@link #commit()} or {@link #abort()}
 * </ul>
 *
 * <p><b>Zero-Copy Pattern:</b> This class implements {@link Claim} itself, so the zero-copy path
 * avoids allocating a separate claim object. After {@code tryClaim()}, the appender's {@code buffer()},
 * {@code offset()}, and {@code length()} methods provide the reserved region.
 *
 * <p><b>Segment Rolling:</b> When a record doesn't fit in the current segment, the appender
 * automatically pads the segment to the end, rolls to the next segment, and retries the write.
 * This is transparent to the caller.
 *
 * <p><b>Usage Pattern:</b>
 * <pre>
 * // Copy mode
 * byte[] data = "hello".getBytes();
 * long position = appender.append(data, 0, data.length);
 *
 * // Zero-copy mode
 * long position = appender.tryClaim(100);
 * if (position >= 0) {
 *     // Write directly into appender.buffer() at appender.offset()
 *     // ... write logic ...
 *     appender.commit();
 * }
 * </pre>
 *
 * @see Wally#appender()
 * @see Claim
 */
public final class Appender implements Claim {

    /** Manages segment acquisition, release, and rolling. */
    private final SegmentManager manager;

    /** Configuration (checksum mode, segment size, etc.). */
    private final WallyConfig config;

    /** Writes records into the current segment. */
    private final SegmentWriter writer = new SegmentWriter();

    /** The currently acquired segment descriptor (file + mmap + refcount). */
    private SegmentDescriptor current;

    /**
     * Package-private constructor called by {@link Wally#appender()}.
     *
     * @param manager the segment manager for acquiring and releasing segments
     * @param config the WAL configuration
     */
    Appender(SegmentManager manager, WallyConfig config) {
        this.manager = manager;
        this.config = config;
        // Acquire the first segment (segment 0) and wrap the writer at its beginning
        this.current = manager.acquire(0);
        writer.wrap(current.data, 0, current.file.basePosition());
    }

    /**
     * Appends a record by copying data from a MemorySegment.
     *
     * <p><b>Behavior:</b>
     * <ol>
     *   <li>Attempts to write the record into the current segment
     *   <li>If it doesn't fit, rolls to the next segment and retries
     *   <li>Returns the record's absolute position in the log
     * </ol>
     *
     * <p><b>Atomicity:</b> The copy and commit are atomic from the reader's perspective. Readers
     * either see the complete record or nothing (frameLength == 0).
     *
     * @param src the source buffer
     * @param srcOffset the offset within the source buffer
     * @param length the number of bytes to copy
     * @return the record's absolute position in the log
     */
    public long append(MemorySegment src, long srcOffset, int length) {
        long position = writer.append(src, srcOffset, length);
        if (position == SegmentWriter.NO_SPACE) {
            roll();  // Pad current segment, acquire next one
            position = writer.append(src, srcOffset, length);
        }
        return position;
    }

    /**
     * Appends a record by copying data from a byte array.
     *
     * <p><b>Behavior:</b>
     * <ol>
     *   <li>Attempts to write the record into the current segment
     *   <li>If it doesn't fit, rolls to the next segment and retries
     *   <li>Returns the record's absolute position in the log
     * </ol>
     *
     * <p><b>Atomicity:</b> The copy and commit are atomic from the reader's perspective. Readers
     * either see the complete record or nothing (frameLength == 0).
     *
     * @param src the source array
     * @param srcOffset the offset within the source array
     * @param length the number of bytes to copy
     * @return the record's absolute position in the log
     */
    public long append(byte[] src, int srcOffset, int length) {
        long position = writer.append(src, srcOffset, length);
        if (position == SegmentWriter.NO_SPACE) {
            roll();  // Pad current segment, acquire next one
            position = writer.append(src, srcOffset, length);
        }
        return position;
    }

    /**
     * Reserves space for a zero-copy write.
     *
     * <p><b>Protocol:</b>
     * <ol>
     *   <li>Call {@code tryClaim(payloadLength)} to reserve space
     *   <li>If successful (returns position >= 0), write payload via {@link #buffer()}/{@link #offset()}
     *   <li>Call {@link #commit()} to publish the record, or {@link #abort()} to discard it
     * </ol>
     *
     * <p><b>Segment Rolling:</b> If the record doesn't fit in the current segment, automatically
     * rolls to the next segment and retries.
     *
     * <p><b>Example:</b>
     * <pre>
     * long position = appender.tryClaim(100);
     * MemorySegment buf = appender.buffer();
     * long offset = appender.offset();
     * // Write 100 bytes starting at buf[offset]
     * buf.set(ValueLayout.JAVA_INT, offset, 42);
     * // ... more writes ...
     * appender.commit();  // Publish the record
     * </pre>
     *
     * @param payloadLength the number of payload bytes to reserve
     * @return the record's absolute position in the log
     */
    public long tryClaim(int payloadLength) {
        long position = writer.tryClaim(payloadLength);
        if (position == SegmentWriter.NO_SPACE) {
            roll();  // Pad current segment, acquire next one
            position = writer.tryClaim(payloadLength);
        }
        return position;
    }

    /**
     * Rolls to the next segment.
     *
     * <p><b>Steps:</b>
     * <ol>
     *   <li>Pad the current segment to the end (fills unused space with a padding frame)
     *   <li>Release the current segment (decrements refcount, may unmap if old)
     *   <li>Increment the global current segment index
     *   <li>Acquire the next segment (increments refcount, may create file)
     *   <li>Wrap the writer at the beginning of the new segment
     * </ol>
     */
    private void roll() {
        // Fill remaining space with a padding frame
        writer.padToEnd();
        // Release the current segment (may unmap if refcount hits 0 and segment is old)
        manager.release(current);
        // Advance the global current segment index
        long nextSegmentIndex = manager.rollToNext();
        long nextPosition = nextSegmentIndex * manager.segmentSize();
        // Acquire the next segment (may open/create the file)
        current = manager.acquire(nextPosition);
        // Wrap the writer at the beginning of the new segment
        writer.wrap(current.data, 0, current.file.basePosition());
    }

    /**
     * Returns the buffer for zero-copy writes (delegates to the writer).
     *
     * @return the memory-mapped segment buffer
     */
    @Override
    public MemorySegment buffer() {
        return writer.buffer();
    }

    /**
     * Returns the offset within the buffer where the payload should be written (delegates to the writer).
     *
     * @return the payload offset
     */
    @Override
    public long offset() {
        return writer.offset();
    }

    /**
     * Returns the length of the reserved payload region (delegates to the writer).
     *
     * @return the payload length in bytes
     */
    @Override
    public int length() {
        return writer.length();
    }

    /**
     * Returns the absolute position of the claimed record (delegates to the writer).
     *
     * @return the record's position in the log
     */
    @Override
    public long position() {
        return writer.position();
    }

    /**
     * Commits the claimed record, making it visible to readers (delegates to the writer).
     *
     * @throws IllegalStateException if no claim is active
     */
    @Override
    public void commit() {
        writer.commit();
    }

    /**
     * Aborts the claimed record, converting it to padding (delegates to the writer).
     *
     * @throws IllegalStateException if no claim is active
     */
    @Override
    public void abort() {
        writer.abort();
    }
}
