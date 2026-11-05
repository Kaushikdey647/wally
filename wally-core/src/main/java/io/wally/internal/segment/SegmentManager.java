package io.wally.internal.segment;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the pool of open segment files, rolling to the next segment, and reference-counting
 * segments so they aren't unmapped while readers are using them.
 *
 * <p>Thread-safe.
 */
public final class SegmentManager implements AutoCloseable {

    private final Path directory;
    private final long segmentSize;
    private final ConcurrentHashMap<Long, SegmentDescriptor> segments = new ConcurrentHashMap<>();
    private final AtomicLong currentSegmentIndex = new AtomicLong(0);

    public SegmentManager(Path directory, long segmentSize) {
        this.directory = directory;
        this.segmentSize = segmentSize;
    }

    /** Returns the segment for a given position, opening it if necessary. Increments the refcount. */
    public SegmentDescriptor acquire(long position) {
        long index = position / segmentSize;
        return segments.compute(index, (k, desc) -> {
            if (desc == null) {
                Path path = directory.resolve(String.format("%020d.wly", index));
                long basePosition = index * segmentSize;
                SegmentFile file = SegmentFile.open(path, segmentSize, basePosition);
                desc = new SegmentDescriptor(index, file);
            }
            desc.refCount++;
            return desc;
        });
    }

    /** Decrements the refcount. If it reaches zero and the segment is old, unmaps and removes it. */
    public void release(SegmentDescriptor desc) {
        segments.computeIfPresent(desc.segmentIndex, (k, d) -> {
            d.refCount--;
            if (d.refCount == 0 && d.segmentIndex < currentSegmentIndex.get() - 1) {
                d.file.close();
                return null;
            }
            return d;
        });
    }

    /** Rolls to the next segment. Called by the writer when the current segment is full. */
    public long rollToNext() {
        return currentSegmentIndex.incrementAndGet();
    }

    public long segmentSize() {
        return segmentSize;
    }

    public long currentSegmentIndex() {
        return currentSegmentIndex.get();
    }

    @Override
    public void close() {
        segments.values().forEach(desc -> desc.file.close());
        segments.clear();
    }
}
