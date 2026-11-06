package io.wally.internal.log;

import io.wally.api.Claim;
import io.wally.api.ProducerMode;
import io.wally.internal.concurrent.TailCoordinator;
import io.wally.internal.memory.Align;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static io.wally.internal.log.RecordFormat.*;

/**
 * MPSC-enabled segment writer with shared tail coordination.
 *
 * <p>Extends {@link SegmentWriter} with multi-producer support via {@link TailCoordinator}.
 * In SINGLE mode, behaves identically to SegmentWriter (local tail, no atomics).
 * In MULTI mode, uses atomic fetch-and-add for lock-free tail claims.
 *
 * <p><b>MPSC Claim Protocol:</b>
 * <ol>
 *   <li>Atomically claim space via {@code tailCoordinator.claimAndAdvance(frameLength)}</li>
 *   <li>Check if claim crosses segment boundary → retry in new segment</li>
 *   <li>Write record payload</li>
 *   <li>Publish via release fence on frameLength field</li>
 * </ol>
 *
 * <p><b>Segment Rollover Coordination:</b>
 * When a claim crosses the segment boundary:
 * <ul>
 *   <li>Writer whose claim crosses writes padding to fill remainder</li>
 *   <li>Returns {@link #SEGMENT_ROLLED} sentinel</li>
 *   <li>Caller (Appender) must acquire next segment and retry</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 * <ul>
 *   <li><b>SINGLE mode:</b> One writer only (like SegmentWriter)</li>
 *   <li><b>MULTI mode:</b> Multiple writers safe via atomic tail coordination</li>
 *   <li>Each writer must have own instance (claim state is per-writer)</li>
 * </ul>
 */
public final class SegmentWriterMPSC implements Claim {

    /** Sentinel: record doesn't fit in current segment (same as SegmentWriter). */
    public static final long NO_SPACE = -1;

    /** Sentinel: segment rolled, retry in new segment (MPSC only). */
    public static final long SEGMENT_ROLLED = -2;

    // Segment memory and bounds
    private MemorySegment buffer;
    private long start;
    private long limit;
    private long basePosition;

    // Tail coordination (shared in MULTI mode, local in SINGLE mode)
    private final TailCoordinator tailCoordinator;
    private final ProducerMode mode;

    // Per-writer claim state (not shared)
    private long claimOffset = -1;
    private int claimFrameLength;

    /**
     * Creates a segment writer with the given tail coordinator.
     *
     * @param tailCoordinator shared tail coordinator (one per SegmentManager in MULTI mode)
     * @param mode the producer mode (SINGLE or MULTI)
     */
    public SegmentWriterMPSC(TailCoordinator tailCoordinator, ProducerMode mode) {
        this.tailCoordinator = tailCoordinator;
        this.mode = mode;
    }

    /**
     * Wraps this writer around a segment's memory region.
     *
     * @param buffer the memory-mapped segment data region
     * @param startOffset starting offset within buffer (usually 0)
     * @param basePosition absolute position of segment's first byte
     */
    public void wrap(MemorySegment buffer, long startOffset, long basePosition) {
        if (!Align.isAligned(startOffset, ALIGNMENT) || !Align.isAligned(buffer.byteSize(), ALIGNMENT)) {
            throw new IllegalArgumentException("start offset and buffer size must be multiples of " + ALIGNMENT);
        }
        this.buffer = buffer;
        this.start = startOffset;
        this.limit = buffer.byteSize();
        this.basePosition = basePosition;
        this.claimOffset = -1;

        // Update base position
        tailCoordinator.setSegmentBasePosition(basePosition);

        // For SINGLE mode, reset tail to start offset for each new segment
        // For MULTI mode, tail is managed globally by SegmentManager.rollToNext()
        if (mode == ProducerMode.SINGLE) {
            tailCoordinator.set(startOffset);
        }
    }

    /**
     * Returns the maximum payload length.
     *
     * @return max payload bytes
     */
    public int maxPayloadLength() {
        return (int) Math.min(Integer.MAX_VALUE - ALIGNMENT, limit - start - HEADER_LENGTH);
    }

    /**
     * Returns the current tail position.
     *
     * @return absolute position where next record will start
     */
    public long tailPosition() {
        return tailCoordinator.toAbsolutePosition(tailCoordinator.get());
    }

    /**
     * Reserves space for a record (MPSC-safe).
     *
     * <p>In MULTI mode, uses atomic getAndAdd for lock-free coordination.
     * In SINGLE mode, uses local tail increment (same as SegmentWriter).
     *
     * @param payloadLength payload size in bytes
     * @return absolute position, {@link #NO_SPACE}, or {@link #SEGMENT_ROLLED}
     */
    public long tryClaim(int payloadLength) {
        if (payloadLength < 0 || payloadLength > maxPayloadLength()) {
            throw new IllegalArgumentException("payload length " + payloadLength + " outside [0, " + maxPayloadLength() + "]");
        }
        if (claimOffset >= 0) {
            throw new IllegalStateException("previous claim not committed or aborted");
        }

        int frameLength = HEADER_LENGTH + payloadLength;
        int alignedFrameLength = Align.align(frameLength, ALIGNMENT);

        // Atomically claim space (MULTI mode uses getAndAdd, SINGLE uses local increment)
        long claimedOffset = tailCoordinator.claimAndAdvance(alignedFrameLength);
        long claimEnd = claimedOffset + alignedFrameLength;

        // Check if claim crosses segment boundary
        if (claimEnd > limit) {
            // Claim crossed boundary - need to handle rollover
            if (mode == ProducerMode.MULTI) {
                // In MPSC mode, the first writer to cross writes padding
                // We detect this by checking if our claimed offset is still within bounds
                if (claimedOffset < limit) {
                    // We're the writer that crossed - write padding and signal rollover
                    writePaddingRange(claimedOffset, (int) (limit - claimedOffset));
                }
                // Signal caller to retry in new segment
                return SEGMENT_ROLLED;
            } else {
                // SINGLE mode: just return NO_SPACE (same as SegmentWriter)
                return NO_SPACE;
            }
        }

        // Claim succeeded - set up frame
        buffer.set(ValueLayout.JAVA_SHORT, claimedOffset + TYPE_OFFSET, TYPE_DATA);
        claimOffset = claimedOffset;
        claimFrameLength = frameLength;

        return basePosition + claimedOffset;
    }

    /**
     * Appends a record (copy mode, MPSC-safe).
     *
     * @param src source buffer
     * @param srcOffset offset in source
     * @param length bytes to copy
     * @return position, {@link #NO_SPACE}, or {@link #SEGMENT_ROLLED}
     */
    public long append(MemorySegment src, long srcOffset, int length) {
        long position = tryClaim(length);
        if (position >= 0) {  // Successful claim
            MemorySegment.copy(src, srcOffset, buffer, claimOffset + HEADER_LENGTH, length);
            commit();
        }
        return position;
    }

    /**
     * Appends a record (copy mode, byte array, MPSC-safe).
     *
     * @param src source array
     * @param srcOffset offset in source
     * @param length bytes to copy
     * @return position, {@link #NO_SPACE}, or {@link #SEGMENT_ROLLED}
     */
    public long append(byte[] src, int srcOffset, int length) {
        long position = tryClaim(length);
        if (position >= 0) {  // Successful claim
            MemorySegment.copy(src, srcOffset, buffer, ValueLayout.JAVA_BYTE, claimOffset + HEADER_LENGTH, length);
            commit();
        }
        return position;
    }

    /**
     * Fills remaining space with padding frame (for segment rollover).
     */
    public void padToEnd() {
        if (claimOffset >= 0) {
            throw new IllegalStateException("claim in progress");
        }
        long currentTail = tailCoordinator.get();
        if (currentTail < limit) {
            writePaddingRange(currentTail, (int) (limit - currentTail));
            tailCoordinator.set(limit);  // Advance tail to end
        }
    }

    /**
     * Writes a padding frame at the given offset.
     *
     * @param offset offset within buffer
     * @param length padding length (will be rounded up to alignment)
     */
    private void writePaddingRange(long offset, int length) {
        if (length > 0) {
            RecordFormat.writePadding(buffer, offset, length);
        }
    }

    // Claim interface implementation

    @Override
    public MemorySegment buffer() {
        return buffer;
    }

    @Override
    public long offset() {
        return claimOffset + HEADER_LENGTH;
    }

    @Override
    public int length() {
        return claimFrameLength - HEADER_LENGTH;
    }

    @Override
    public long position() {
        return basePosition + claimOffset;
    }

    @Override
    public void commit() {
        checkClaimed();
        // Publish via release fence on frameLength
        RecordFormat.publish(buffer, claimOffset, claimFrameLength);
        claimOffset = -1;  // Clear claim
    }

    @Override
    public void abort() {
        checkClaimed();
        // Convert to padding
        RecordFormat.writePadding(buffer, claimOffset, Align.align(claimFrameLength, ALIGNMENT));
        claimOffset = -1;  // Clear claim
    }

    private void checkClaimed() {
        if (claimOffset < 0) {
            throw new IllegalStateException("no claim in progress");
        }
    }
}
