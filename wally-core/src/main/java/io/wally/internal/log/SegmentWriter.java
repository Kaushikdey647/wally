package io.wally.internal.log;

import io.wally.api.Claim;
import io.wally.internal.memory.Align;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static io.wally.internal.log.RecordFormat.ALIGNMENT;
import static io.wally.internal.log.RecordFormat.HEADER_LENGTH;
import static io.wally.internal.log.RecordFormat.TYPE_OFFSET;

/**
 * Single-writer appender over one segment. Not thread-safe: one writer thread per instance.
 *
 * <p>Implements {@link Claim} itself so the zero-copy path needs no extra object.
 */
public final class SegmentWriter implements Claim {

    /** Returned when the record does not fit in the remaining space. */
    public static final long NO_SPACE = -1;

    private MemorySegment buffer;
    private long start;
    private long limit;
    private long basePosition;
    private long tail;

    private long claimOffset = -1;
    private int claimFrameLength;

    /**
     * Writes into {@code buffer[startOffset, byteSize)}. Positions are {@code basePosition + offset}.
     * The region must be zero-filled from {@code startOffset} onwards.
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
        this.claimOffset = -1;
    }

    public int maxPayloadLength() {
        return (int) Math.min(Integer.MAX_VALUE - ALIGNMENT, limit - start - HEADER_LENGTH);
    }

    /** Position the next record will be written at. */
    public long tailPosition() {
        return basePosition + tail;
    }

    /** @return the record position, or {@link #NO_SPACE} */
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
            return NO_SPACE;
        }
        buffer.set(ValueLayout.JAVA_SHORT, offset + TYPE_OFFSET, RecordFormat.TYPE_DATA);
        tail = next;
        claimOffset = offset;
        claimFrameLength = frameLength;
        return basePosition + offset;
    }

    /** @return the record position, or {@link #NO_SPACE} */
    public long append(MemorySegment src, long srcOffset, int length) {
        long position = tryClaim(length);
        if (position != NO_SPACE) {
            MemorySegment.copy(src, srcOffset, buffer, claimOffset + HEADER_LENGTH, length);
            commit();
        }
        return position;
    }

    /** @return the record position, or {@link #NO_SPACE} */
    public long append(byte[] src, int srcOffset, int length) {
        long position = tryClaim(length);
        if (position != NO_SPACE) {
            MemorySegment.copy(src, srcOffset, buffer, ValueLayout.JAVA_BYTE, claimOffset + HEADER_LENGTH, length);
            commit();
        }
        return position;
    }

    /** Fills the remaining space with a padding frame so readers move on to the next segment. */
    public void padToEnd() {
        if (claimOffset >= 0) {
            throw new IllegalStateException("claim in progress");
        }
        if (tail < limit) {
            RecordFormat.writePadding(buffer, tail, (int) (limit - tail));
            tail = limit;
        }
    }

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
        RecordFormat.publish(buffer, claimOffset, claimFrameLength);
        claimOffset = -1;
    }

    @Override
    public void abort() {
        checkClaimed();
        RecordFormat.writePadding(buffer, claimOffset, Align.align(claimFrameLength, ALIGNMENT));
        claimOffset = -1;
    }

    private void checkClaimed() {
        if (claimOffset < 0) {
            throw new IllegalStateException("no claim in progress");
        }
    }
}
