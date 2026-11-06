package io.wally.internal.log;

import io.wally.api.Claim;
import io.wally.internal.memory.Align;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static io.wally.internal.log.RecordFormat.ALIGNMENT;
import static io.wally.internal.log.RecordFormat.HEADER_LENGTH;
import static io.wally.internal.log.RecordFormat.TYPE_OFFSET;

/**
 * Writes records into a single memory-mapped segment.
 *
 * <p><b>Thread Safety:</b> Not thread-safe. One writer thread per instance. Multi-writer
 * coordination (M3) will use separate instances with interleaved sequences.
 *
 * <p><b>Write Modes:</b>
 * <ul>
 *   <li><b>Copy Mode:</b> {@link #append(MemorySegment, long, int)} or {@link #append(byte[], int, int)}
 *       copies data into the segment and immediately commits
 *   <li><b>Zero-Copy Mode:</b> {@link #tryClaim(int)} reserves space and returns a {@link Claim},
 *       caller writes directly into the buffer, then calls {@link #commit()} or {@link #abort()}
 * </ul>
 *
 * <p><b>Claim Pattern:</b> This class implements {@link Claim} itself, so the zero-copy path
 * avoids allocating a separate claim object. After {@code tryClaim()}, the writer fields
 * ({@link #buffer()}, {@link #offset()}, {@link #length()}) provide the reserved region.
 *
 * <p><b>Record Layout:</b> Each record is a frame with a 16-byte header followed by the payload:
 * <pre>
 *  0: int   frameLength (header + payload, 0 = uncommitted)
 *  4: short type (DATA or PADDING)
 *  6: short flags
 *  8: int   crc32c (payload checksum, 0 when disabled)
 * 12: int   reserved
 * 16: ...   payload
 * </pre>
 * Frames are aligned to 8-byte boundaries.
 *
 * <p><b>Commit Protocol:</b> The {@code frameLength} field is written last with a release fence,
 * making the record visible to readers atomically.
 *
 * @see RecordFormat
 * @see Claim
 */
public final class SegmentWriter implements Claim {

    /** Sentinel value returned when the record does not fit in the remaining space. */
    public static final long NO_SPACE = -1;

    /** The memory-mapped segment buffer (data region, excluding header). */
    private MemorySegment buffer;

    /** Starting offset within the buffer where writing begins (usually 0). */
    private long start;

    /** End offset within the buffer (usually buffer.byteSize()). */
    private long limit;

    /** Absolute position of the segment's first byte in the log. */
    private long basePosition;

    /** Current write offset within the buffer (next frame will start here). */
    private long tail;

    /** Offset of the currently claimed frame, or -1 if no claim is active. */
    private long claimOffset = -1;

    /** Length of the currently claimed frame (header + payload). */
    private int claimFrameLength;

    /**
     * Wraps this writer around a segment's memory region.
     *
     * <p><b>Preconditions:</b>
     * <ul>
     *   <li>{@code startOffset} and {@code buffer.byteSize()} must be multiples of 8 (enforced)
     *   <li>The region {@code buffer[startOffset, byteSize)} must be zero-filled (caller's responsibility)
     * </ul>
     *
     * <p><b>Usage:</b> Called when the appender acquires a new segment. The buffer is the segment's
     * data region (excluding the 64-byte header). The startOffset is usually 0, but can be non-zero
     * when resuming a partially written segment (future tail recovery feature).
     *
     * @param buffer the memory-mapped segment's data region
     * @param startOffset the offset within the buffer where writing begins (must be 8-byte aligned)
     * @param basePosition the absolute position of the segment's first byte in the log
     * @throws IllegalArgumentException if alignment requirements are violated
     */
    public void wrap(MemorySegment buffer, long startOffset, long basePosition) {
        if (!Align.isAligned(startOffset, ALIGNMENT) || !Align.isAligned(buffer.byteSize(), ALIGNMENT)) {
            throw new IllegalArgumentException("start offset and buffer size must be multiples of " + ALIGNMENT);
        }
        this.buffer = buffer;
        this.start = startOffset;
        this.limit = buffer.byteSize();
        this.basePosition = basePosition;
        this.tail = startOffset;
        this.claimOffset = -1;  // No active claim
    }

    /**
     * Returns the maximum payload length that can fit in a single record.
     *
     * <p>Limited by:
     * <ul>
     *   <li>Segment size minus header (16 bytes)
     *   <li>{@code Integer.MAX_VALUE - ALIGNMENT} to prevent overflow in frame length calculation
     * </ul>
     *
     * @return max payload bytes (typically segment size - 16)
     */
    public int maxPayloadLength() {
        return (int) Math.min(Integer.MAX_VALUE - ALIGNMENT, limit - start - HEADER_LENGTH);
    }

    /**
     * Returns the absolute position where the next record will be written.
     *
     * @return the tail position (basePosition + current offset)
     */
    public long tailPosition() {
        return basePosition + tail;
    }

    /**
     * Reserves space for a record in zero-copy mode.
     *
     * <p><b>Protocol:</b>
     * <ol>
     *   <li>Call {@code tryClaim(payloadLength)} to reserve space
     *   <li>If successful (returns position >= 0), write payload via {@link #buffer()}/{@link #offset()}
     *   <li>Call {@link #commit()} to publish the record, or {@link #abort()} to discard it
     * </ol>
     *
     * <p><b>Space Check:</b> If the record doesn't fit in the remaining space, returns {@link #NO_SPACE}.
     * The caller should then pad the segment and roll to the next one.
     *
     * <p><b>Alignment:</b> The actual space consumed is {@code align(16 + payloadLength, 8)}, where
     * 16 is the header size.
     *
     * <p><b>State Change:</b> Sets the frame's type field to DATA and reserves the region. The
     * {@code frameLength} field is still 0 (uncommitted) until {@link #commit()} is called.
     *
     * @param payloadLength the number of payload bytes to reserve (0 to maxPayloadLength)
     * @return the absolute position of the record, or {@link #NO_SPACE} if it doesn't fit
     * @throws IllegalArgumentException if payloadLength is out of bounds
     * @throws IllegalStateException if a previous claim is still active
     */
    public long tryClaim(int payloadLength) {
        if (payloadLength < 0 || payloadLength > maxPayloadLength()) {
            throw new IllegalArgumentException("payload length " + payloadLength + " outside [0, " + maxPayloadLength() + "]");
        }
        if (claimOffset >= 0) {
            throw new IllegalStateException("previous claim not committed or aborted");
        }
        int frameLength = HEADER_LENGTH + payloadLength;
        long offset = tail;
        long next = offset + Align.align(frameLength, ALIGNMENT);
        if (next > limit) {
            return NO_SPACE;  // Doesn't fit
        }
        // Write the type field (frameLength is still 0 = uncommitted)
        buffer.set(ValueLayout.JAVA_SHORT, offset + TYPE_OFFSET, RecordFormat.TYPE_DATA);
        tail = next;
        claimOffset = offset;
        claimFrameLength = frameLength;
        return basePosition + offset;
    }

    /**
     * Appends a record by copying data from a MemorySegment.
     *
     * <p><b>Behavior:</b> Combines {@link #tryClaim}, a copy, and {@link #commit()} into one operation.
     *
     * @param src the source buffer
     * @param srcOffset the offset within the source buffer
     * @param length the number of bytes to copy
     * @return the absolute position of the record, or {@link #NO_SPACE} if it doesn't fit
     */
    public long append(MemorySegment src, long srcOffset, int length) {
        long position = tryClaim(length);
        if (position != NO_SPACE) {
            MemorySegment.copy(src, srcOffset, buffer, claimOffset + HEADER_LENGTH, length);
            commit();
        }
        return position;
    }

    /**
     * Appends a record by copying data from a byte array.
     *
     * <p><b>Behavior:</b> Combines {@link #tryClaim}, a copy, and {@link #commit()} into one operation.
     *
     * @param src the source array
     * @param srcOffset the offset within the source array
     * @param length the number of bytes to copy
     * @return the absolute position of the record, or {@link #NO_SPACE} if it doesn't fit
     */
    public long append(byte[] src, int srcOffset, int length) {
        long position = tryClaim(length);
        if (position != NO_SPACE) {
            MemorySegment.copy(src, srcOffset, buffer, ValueLayout.JAVA_BYTE, claimOffset + HEADER_LENGTH, length);
            commit();
        }
        return position;
    }

    /**
     * Fills the remaining space with a padding frame.
     *
     * <p><b>When Called:</b> Called by the appender when rolling to the next segment. The padding
     * frame signals to readers that there's no more data in this segment and they should move to
     * the next one.
     *
     * <p><b>Frame Format:</b> A padding frame only needs {@code frameLength} and {@code type} fields,
     * so it can be as short as 8 bytes. The entire remaining space is consumed.
     *
     * @throws IllegalStateException if a claim is currently active
     */
    public void padToEnd() {
        if (claimOffset >= 0) {
            throw new IllegalStateException("claim in progress");
        }
        if (tail < limit) {
            RecordFormat.writePadding(buffer, tail, (int) (limit - tail));
            tail = limit;
        }
    }

    /**
     * Returns the buffer for zero-copy writes.
     *
     * <p><b>Usage:</b> After {@link #tryClaim}, write directly into {@code buffer[offset(), offset() + length())}.
     *
     * @return the memory-mapped segment buffer
     */
    @Override
    public MemorySegment buffer() {
        return buffer;
    }

    /**
     * Returns the offset within the buffer where the payload should be written.
     *
     * <p>This points past the 16-byte header to the payload region.
     *
     * @return the payload offset (claimOffset + 16)
     */
    @Override
    public long offset() {
        return claimOffset + HEADER_LENGTH;
    }

    /**
     * Returns the length of the reserved payload region.
     *
     * @return the payload length in bytes (excludes the 16-byte header)
     */
    @Override
    public int length() {
        return claimFrameLength - HEADER_LENGTH;
    }

    /**
     * Returns the absolute position of the claimed record.
     *
     * @return the record's position in the log
     */
    @Override
    public long position() {
        return basePosition + claimOffset;
    }

    /**
     * Commits the claimed record, making it visible to readers.
     *
     * <p><b>Publish Protocol:</b> Writes the {@code frameLength} field with a release fence,
     * ensuring all prior payload writes are visible before the frame becomes committed.
     *
     * <p><b>State Change:</b> Clears the claim state, allowing the next record to be written.
     *
     * @throws IllegalStateException if no claim is active
     */
    @Override
    public void commit() {
        checkClaimed();
        // Publish the frame by writing frameLength with release semantics
        RecordFormat.publish(buffer, claimOffset, claimFrameLength);
        claimOffset = -1;  // Clear claim state
    }

    /**
     * Aborts the claimed record, discarding it by converting it to a padding frame.
     *
     * <p><b>Use Case:</b> If the caller encounters an error after claiming space but before
     * writing valid data, abort ensures the space is filled with padding rather than left
     * with partial/corrupt data.
     *
     * <p><b>State Change:</b> Clears the claim state, allowing the next record to be written.
     *
     * @throws IllegalStateException if no claim is active
     */
    @Override
    public void abort() {
        checkClaimed();
        // Convert the claimed frame to padding
        RecordFormat.writePadding(buffer, claimOffset, Align.align(claimFrameLength, ALIGNMENT));
        claimOffset = -1;  // Clear claim state
    }

    /**
     * Checks that a claim is currently active.
     *
     * @throws IllegalStateException if no claim is active
     */
    private void checkClaimed() {
        if (claimOffset < 0) {
            throw new IllegalStateException("no claim in progress");
        }
    }
}
