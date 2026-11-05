package io.wally.internal.log;

import io.wally.api.RecordHandler;
import io.wally.internal.memory.Align;
import io.wally.internal.memory.Mem;

import java.lang.foreign.MemorySegment;

import static io.wally.internal.log.RecordFormat.ALIGNMENT;
import static io.wally.internal.log.RecordFormat.HEADER_LENGTH;

/** Reads committed records from one segment. Not thread-safe: one reader thread per instance. */
public final class SegmentReader {

    private MemorySegment buffer;
    private long limit;
    private long basePosition;
    private long offset;

    public void wrap(MemorySegment buffer, long startOffset, long basePosition) {
        this.buffer = buffer;
        this.limit = buffer.byteSize();
        this.basePosition = basePosition;
        this.offset = startOffset;
    }

    /**
     * Delivers up to {@code recordLimit} committed records, stopping at the first uncommitted frame.
     * If the handler throws, the failing record counts as consumed so it is not redelivered.
     *
     * @return number of records delivered (padding frames are skipped and not counted)
     */
    public int read(RecordHandler handler, int recordLimit) {
        int count = 0;
        long offset = this.offset;
        try {
            while (count < recordLimit && offset < limit) {
                int frameLength = Mem.getIntAcquire(buffer, offset);
                if (frameLength == 0) {
                    break;
                }
                long frameOffset = offset;
                offset += Align.align(frameLength, ALIGNMENT);
                if (RecordFormat.type(buffer, frameOffset) == RecordFormat.TYPE_DATA) {
                    count++;
                    handler.onRecord(buffer, frameOffset + HEADER_LENGTH, frameLength - HEADER_LENGTH, basePosition + frameOffset);
                }
            }
        } finally {
            this.offset = offset;
        }
        return count;
    }

    /** True once every frame in the segment, including trailing padding, has been consumed. */
    public boolean isAtEnd() {
        return offset >= limit;
    }

    public long position() {
        return basePosition + offset;
    }
}
