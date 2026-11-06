package io.wally.internal.segment;

import java.lang.foreign.MemorySegment;

/**
 * Lightweight descriptor for a cached segment in the {@link SegmentManager} pool.
 *
 * <p><b>Contents:</b>
 * <ul>
 *   <li><b>segmentIndex:</b> The segment's index (0, 1, 2, ...)
 *   <li><b>file:</b> The {@link SegmentFile} wrapper (path, mmap, arena)
 *   <li><b>data:</b> The data region (mapped memory excluding the 64-byte header)
 *   <li><b>refCount:</b> Number of active appenders/tailers using this segment
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>Created by {@link SegmentManager#acquire} when a segment is first opened
 *   <li>Refcount incremented on each acquire, decremented on each release
 *   <li>Unmapped and removed from the pool when refcount reaches 0 and the segment is old
 * </ul>
 *
 * <p><b>Data Slice:</b> The {@code data} field is a slice of the mapped file that excludes the
 * 64-byte header, so writers and readers can address it directly with offset 0 being the first
 * record position.
 *
 * <p><b>Thread Safety:</b> Package-private fields are modified only within synchronized blocks
 * of {@link SegmentManager} (via {@link java.util.concurrent.ConcurrentHashMap#compute}).
 *
 * @see SegmentManager
 * @see SegmentFile
 */
public final class SegmentDescriptor {
    /** The segment's index (0-based). */
    public final long segmentIndex;

    /** The segment file (path, mmap, arena). */
    public final SegmentFile file;

    /**
     * The data region (mapped memory excluding the 64-byte header).
     * Equivalent to {@code file.mapped().asSlice(SegmentFile.HEADER_SIZE)}.
     */
    public final MemorySegment data;

    /**
     * Number of active acquires (appenders/tailers using this segment).
     * Modified only by SegmentManager under synchronization.
     */
    int refCount;

    /**
     * Package-private constructor called by {@link SegmentManager#acquire}.
     *
     * @param segmentIndex the segment's index
     * @param file the segment file wrapper
     */
    SegmentDescriptor(long segmentIndex, SegmentFile file) {
        this.segmentIndex = segmentIndex;
        this.file = file;
        // Slice the data region (skip 64-byte header)
        this.data = file.mapped().asSlice(SegmentFile.HEADER_SIZE);
        this.refCount = 0;
    }
}
