package io.wally.internal.segment;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the lifecycle of segment files in the write-ahead log.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li><b>Segment Pooling:</b> Maintains a cache of open segment files to avoid repeated open/mmap operations
 *   <li><b>Reference Counting:</b> Tracks how many appenders/tailers are using each segment to prevent
 *       premature unmapping
 *   <li><b>Segment Rolling:</b> Coordinates the transition to the next segment when the current one fills
 *   <li><b>Resource Cleanup:</b> Unmaps and removes old segments when they're no longer in use
 * </ul>
 *
 * <p><b>Thread Safety:</b> Fully thread-safe. Multiple appenders and tailers can acquire/release
 * segments concurrently. Uses {@link ConcurrentHashMap} for the segment pool and {@link AtomicLong}
 * for the current segment index.
 *
 * <p><b>Segment Naming:</b> Segment files are named by their index with zero-padding:
 * {@code 00000000000000000000.wly}, {@code 00000000000000000001.wly}, etc.
 *
 * <p><b>Memory Management:</b> Segments are memory-mapped using {@link java.lang.foreign.Arena}
 * with {@link java.lang.foreign.Arena#ofShared()} for multi-threaded access. Old segments are
 * unmapped when their refcount reaches zero and they're more than one segment behind the current
 * writer position.
 *
 * <p><b>Usage Pattern:</b>
 * <pre>
 * SegmentManager manager = new SegmentManager(Path.of("data"), 4096);
 * SegmentDescriptor seg = manager.acquire(0);  // refcount: 0 -> 1
 * try {
 *     // Use seg.data for reading/writing
 * } finally {
 *     manager.release(seg);  // refcount: 1 -> 0 (may unmap if old)
 * }
 * </pre>
 *
 * @see SegmentDescriptor
 * @see SegmentFile
 */
public final class SegmentManager implements AutoCloseable {

    /** Directory where segment files are stored. */
    private final Path directory;

    /** Size of each segment file in bytes (header + data region). */
    private final long segmentSize;

    /** Pool of open segments, indexed by segment index. Thread-safe. */
    private final ConcurrentHashMap<Long, SegmentDescriptor> segments = new ConcurrentHashMap<>();

    /** Index of the segment currently being written to. Thread-safe. */
    private final AtomicLong currentSegmentIndex = new AtomicLong(0);

    /**
     * Creates a segment manager for the given directory and segment size.
     *
     * @param directory the directory where segment files will be stored (created if missing)
     * @param segmentSize the size of each segment file in bytes (must be a multiple of 8)
     */
    public SegmentManager(Path directory, long segmentSize) {
        this.directory = directory;
        this.segmentSize = segmentSize;
    }

    /**
     * Acquires the segment containing the given position, opening it if necessary.
     *
     * <p><b>Behavior:</b>
     * <ul>
     *   <li>Calculates the segment index from the position: {@code index = position / segmentSize}
     *   <li>If the segment is already open, increments its refcount
     *   <li>If the segment is not open, opens (or creates) the file, mmaps it, validates the header,
     *       creates a descriptor, and sets refcount to 1
     *   <li>Returns the descriptor with the mapped memory region ready for use
     * </ul>
     *
     * <p><b>Thread Safety:</b> Uses {@link ConcurrentHashMap#compute} to atomically check-or-create
     * and increment the refcount, preventing races between concurrent acquires.
     *
     * <p><b>File Creation:</b> If the segment file doesn't exist, it's created, pre-allocated to
     * {@code segmentSize}, and initialized with a valid header. The data region is zero-filled by
     * the OS.
     *
     * <p><b>Lifecycle:</b> The caller MUST call {@link #release(SegmentDescriptor)} when done with
     * the segment to decrement the refcount and allow eventual cleanup.
     *
     * @param position any absolute position within the desired segment
     * @return the segment descriptor with incremented refcount
     * @throws java.io.UncheckedIOException if the file cannot be opened or mapped
     */
    public SegmentDescriptor acquire(long position) {
        long index = position / segmentSize;
        return segments.compute(index, (k, desc) -> {
            if (desc == null) {
                // Segment not yet open: create file, mmap it, validate/write header
                Path path = directory.resolve(String.format("%020d.wly", index));
                long basePosition = index * segmentSize;
                SegmentFile file = SegmentFile.open(path, segmentSize, basePosition);
                desc = new SegmentDescriptor(index, file);
            }
            // Increment refcount (whether newly created or already open)
            desc.refCount++;
            return desc;
        });
    }

    /**
     * Releases a previously acquired segment, decrementing its refcount.
     *
     * <p><b>Cleanup Policy:</b> If the refcount reaches zero AND the segment is more than one
     * segment behind the current writer position, the segment is unmapped and removed from the
     * pool. This prevents unbounded memory growth while keeping recent segments cached for
     * catch-up readers.
     *
     * <p><b>Example:</b>
     * <ul>
     *   <li>Current writer is on segment 5
     *   <li>A tailer releases segment 3 with refcount 1 -> 0
     *   <li>Since 3 < (5 - 1), segment 3 is unmapped and removed
     *   <li>If the tailer releases segment 4 with refcount 1 -> 0, it's kept (4 >= 4)
     * </ul>
     *
     * <p><b>Thread Safety:</b> Uses {@link ConcurrentHashMap#computeIfPresent} to atomically
     * decrement and conditionally remove.
     *
     * @param desc the segment descriptor to release (must have been acquired previously)
     */
    public void release(SegmentDescriptor desc) {
        segments.computeIfPresent(desc.segmentIndex, (k, d) -> {
            d.refCount--;
            // If refcount hits zero and segment is old (more than 1 behind current), unmap it
            if (d.refCount == 0 && d.segmentIndex < currentSegmentIndex.get() - 1) {
                d.file.close();  // Closes the Arena, unmapping the memory
                return null;      // Remove from the map
            }
            return d;  // Keep in the map
        });
    }

    /**
     * Advances the current segment index to the next segment.
     *
     * <p><b>When Called:</b> This is called by the appender when the current segment is full
     * (after padding to the end). The writer then acquires the new segment and continues.
     *
     * <p><b>Thread Safety:</b> Uses {@link AtomicLong#incrementAndGet()} for atomic increment.
     * If multiple writers exist (future M3 feature), this ensures they coordinate on segment
     * allocation.
     *
     * @return the new current segment index (previous + 1)
     */
    public long rollToNext() {
        return currentSegmentIndex.incrementAndGet();
    }

    /**
     * Returns the configured segment size in bytes.
     *
     * @return segment size (header + data region)
     */
    public long segmentSize() {
        return segmentSize;
    }

    /**
     * Returns the current segment index being written to.
     *
     * @return the segment index (0-based)
     */
    public long currentSegmentIndex() {
        return currentSegmentIndex.get();
    }

    /**
     * Closes all open segments and clears the pool.
     *
     * <p><b>Usage:</b> Called when the WAL is closed. Unmaps all segments regardless of refcount.
     * Calling {@link #acquire} or {@link #release} after close is undefined behavior.
     */
    @Override
    public void close() {
        segments.values().forEach(desc -> desc.file.close());
        segments.clear();
    }
}
