package io.wally.internal.segment;

import java.lang.foreign.MemorySegment;

/** Lightweight descriptor for one segment: its index, file, mapped memory, and refcount. */
public final class SegmentDescriptor {
    public final long segmentIndex;
    public final SegmentFile file;
    public final MemorySegment data;  // file.mapped().asSlice(SegmentFile.HEADER_SIZE)
    int refCount;

    SegmentDescriptor(long segmentIndex, SegmentFile file) {
        this.segmentIndex = segmentIndex;
        this.file = file;
        this.data = file.mapped().asSlice(SegmentFile.HEADER_SIZE);
        this.refCount = 0;
    }
}
