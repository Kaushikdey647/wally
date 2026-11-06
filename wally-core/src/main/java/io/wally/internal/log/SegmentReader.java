package io.wally.internal.log;

import io.wally.api.RecordHandler;
import io.wally.internal.memory.Align;
import io.wally.internal.memory.Mem;

import java.lang.foreign.MemorySegment;

import static io.wally.internal.log.RecordFormat.ALIGNMENT;
import static io.wally.internal.log.RecordFormat.HEADER_LENGTH;

/**
 * Reads committed records from a single memory-mapped segment.
 *
 * <p><b>Thread Safety:</b> Not thread-safe. One reader thread per instance. Multiple tailers
 * can read concurrently if each has its own SegmentReader instance.
 *
 * <p><b>Read Protocol:</b>
 * <ol>
 *   <li>Read the {@code frameLength} field with acquire semantics
 *   <li>If frameLength is 0, the frame is uncommitted (writer hasn't published it yet) → stop
 *   <li>If frameLength > 0, the frame is committed → read type, payload, and deliver to handler
 *   <li>Skip padding frames (they don't count toward the record limit)
 *   <li>Advance offset by {@code align(frameLength, 8)} to the next frame
 * </ol>
 *
 * <p><b>Exception Handling:</b> If the handler throws, the record is still considered consumed
 * (offset advances past it) to avoid redelivery loops. The exception propagates to the caller.
 *
 * <p><b>Position Tracking:</b> The reader tracks its offset within the segment and can report
 * the absolute position via {@link #position()}.
 *
 * @see RecordFormat
 * @see RecordHandler
 */
public final class SegmentReader {

    /** The memory-mapped segment buffer (data region, excluding header). */
    private MemorySegment buffer;

    /** End offset within the buffer (usually buffer.byteSize()). */
    private long limit;

    /** Absolute position of the segment's first byte in the log. */
    private long basePosition;

    /** Current read offset within the buffer (next frame will be read from here). */
    private long offset;

    /**
     * Wraps this reader around a segment's memory region.
     *
     * <p><b>Usage:</b> Called when the tailer acquires a new segment. The buffer is the segment's
     * data region (excluding the 64-byte header). The startOffset is usually 0, but can be non-zero
     * when resuming from a saved position mid-segment.
     *
     * @param buffer the memory-mapped segment's data region
     * @param startOffset the offset within the buffer where reading begins
     * @param basePosition the absolute position of the segment's first byte in the log
     */
    public void wrap(MemorySegment buffer, long startOffset, long basePosition) {
        this.buffer = buffer;
        this.limit = buffer.byteSize();
        this.basePosition = basePosition;
        this.offset = startOffset;
    }

    /**
     * Reads and delivers committed records to the handler.
     *
     * <p><b>Stopping Conditions:</b>
     * <ul>
     *   <li>Delivered {@code recordLimit} data records (padding frames don't count)
     *   <li>Reached an uncommitted frame (frameLength == 0)
     *   <li>Reached the end of the segment
     * </ul>
     *
     * <p><b>Acquire Semantics:</b> The {@code frameLength} field is read with acquire fence,
     * ensuring that if a frame is committed (length > 0), all payload writes are visible.
     *
     * <p><b>Padding Frames:</b> Frames with {@code type == TYPE_PADDING} are skipped and do not
     * count toward the record limit. They indicate unused space (e.g., at the end of a segment).
     *
     * <p><b>Exception Handling:</b> If the handler throws, the failing record is still marked as
     * consumed (offset advances) to avoid infinite redelivery. The exception propagates to the caller.
     *
     * <p><b>Example:</b>
     * <pre>
     * int delivered = reader.read((buf, off, len, pos) -> {
     *     byte[] data = new byte[len];
     *     MemorySegment.copy(buf, off, data, 0, len);
     *     System.out.println("Record at " + pos + ": " + len + " bytes");
     * }, 100);
     * </pre>
     *
     * @param handler callback invoked for each data record (receives buffer, offset, length, position)
     * @param recordLimit maximum number of data records to deliver
     * @return actual number of data records delivered (0 to recordLimit)
     */
    public int read(RecordHandler handler, int recordLimit) {
        int count = 0;
        long offset = this.offset;
        try {
            while (count < recordLimit && offset < limit) {
                // Read frameLength with acquire semantics
                int frameLength = Mem.getIntAcquire(buffer, offset);
                if (frameLength == 0) {
                    // Uncommitted frame: stop reading
                    break;
                }
                long frameOffset = offset;
                offset += Align.align(frameLength, ALIGNMENT);
                // Deliver only DATA frames, skip PADDING frames
                if (RecordFormat.type(buffer, frameOffset) == RecordFormat.TYPE_DATA) {
                    count++;
                    handler.onRecord(buffer, frameOffset + HEADER_LENGTH, frameLength - HEADER_LENGTH, basePosition + frameOffset);
                }
            }
        } finally {
            // Update offset even if handler throws (mark record as consumed)
            this.offset = offset;
        }
        return count;
    }

    /**
     * Returns true if the reader has consumed all frames in the segment.
     *
     * <p><b>Usage:</b> The tailer uses this to detect when to roll to the next segment. Note that
     * this means "reached the segment size limit", not "encountered an uncommitted frame".
     *
     * @return true if offset >= limit (segment boundary reached)
     */
    public boolean isAtEnd() {
        return offset >= limit;
    }

    /**
     * Returns the current read position (absolute offset in the log).
     *
     * <p>This position can be saved and passed to {@link io.wally.api.Wally#tailerFrom(long)}
     * to resume reading from this point.
     *
     * @return the absolute position of the next frame to be read
     */
    public long position() {
        return basePosition + offset;
    }
}
