package io.wally.api;

import io.wally.internal.log.SegmentReader;
import io.wally.internal.segment.SegmentDescriptor;
import io.wally.internal.segment.SegmentFile;
import io.wally.internal.segment.SegmentManager;

/**
 * Read handle for consuming records from the write-ahead log.
 *
 * <p><b>Thread Safety:</b> Not thread-safe. Create one tailer per consumer thread.
 *
 * <p><b>Lifecycle:</b> This is an ad-hoc tailer that starts at a given position and doesn't
 * persist its cursor across restarts. Named consumer groups (M4) will add persistent cursor
 * management and offset tracking.
 *
 * <p><b>Usage Pattern:</b>
 * <pre>
 * Tailer tailer = wally.tailerFrom(0);  // Start from position 0
 * tailer.poll((buf, off, len, pos) -> {
 *     // Process record
 *     System.out.println("Record at " + pos + ": " + len + " bytes");
 * }, 100);  // Read up to 100 records
 * </pre>
 *
 * <p><b>Segment Coordination:</b> The tailer automatically rolls to the next segment when it
 * reaches the end of the current segment. The rollover logic ensures correct segment index
 * calculation to prevent getting stuck on the same segment.
 *
 * @see Wally#tailerFrom(long)
 * @see RecordHandler
 */
public final class Tailer {

    /** Manages segment acquisition, release, and pooling. */
    private final SegmentManager manager;

    /** Reads committed records from the current segment. */
    private final SegmentReader reader = new SegmentReader();

    /** The currently acquired segment descriptor (file + mmap + refcount). */
    private SegmentDescriptor current;

    /**
     * Package-private constructor called by {@link Wally#tailerFrom(long)}.
     *
     * @param manager the segment manager for acquiring and releasing segments
     * @param startPosition the absolute position to start reading from (can be mid-segment)
     */
    Tailer(SegmentManager manager, long startPosition) {
        this.manager = manager;
        // Acquire the segment containing startPosition and increment its refcount
        this.current = manager.acquire(startPosition);
        // Calculate the offset within the segment where reading should begin
        long offsetInSegment = startPosition - current.file.basePosition();
        // Initialize the reader at the correct offset within the segment's data region
        reader.wrap(current.data, offsetInSegment, current.file.basePosition());
    }

    /**
     * Polls for available records and delivers them to the handler.
     *
     * <p><b>Behavior:</b>
     * <ul>
     *   <li>Reads up to {@code recordLimit} committed data records from the current segment
     *   <li>Stops early if an uncommitted frame is encountered (writer hasn't published it yet)
     *   <li>Skips padding frames without counting them toward the limit
     *   <li>Automatically rolls to the next segment if the current one is fully consumed
     * </ul>
     *
     * <p><b>Exception Handling:</b> If the handler throws an exception, that record is still
     * considered consumed and won't be redelivered on the next poll. The exception propagates
     * to the caller.
     *
     * <p><b>Segment Rollover:</b> When the reader reaches the end of a segment (indicated by
     * {@code isAtEnd()}), the tailer automatically rolls to the next segment. The condition
     * {@code position() > basePosition()} prevents rolling on an empty segment.
     *
     * @param handler callback invoked for each data record (receives buffer, offset, length, position)
     * @param recordLimit maximum number of data records to deliver (padding frames don't count)
     * @return actual number of data records delivered (0 to recordLimit)
     * @throws RuntimeException if the handler throws during record processing
     */
    public int poll(RecordHandler handler, int recordLimit) {
        int count = reader.read(handler, recordLimit);
        // Roll to the next segment if we consumed everything up to the segment boundary.
        // reader.isAtEnd() means we hit the segment size limit, not an uncommitted frame.
        // The position check prevents rolling on an empty segment.
        if (reader.isAtEnd() && reader.position() > current.file.basePosition()) {
            rollToNextSegment();
        }
        return count;
    }

    /**
     * Rolls the tailer to the next segment.
     *
     * <p><b>CRITICAL FIX (commit 4b9c630):</b> This method previously had a bug where it would
     * acquire the current position (e.g., 4032) and divide by segmentSize (4096), which rounded
     * down to segment index 0. Calling {@code acquire(4032)} would re-acquire segment 0, causing
     * the tailer to stay stuck on the same segment forever.
     *
     * <p><b>Correct Algorithm:</b>
     * <ol>
     *   <li>Get the current position (e.g., 4032)
     *   <li>Calculate the current segment index (4032 / 4096 = 0)
     *   <li>Calculate the <i>next</i> segment index (0 + 1 = 1)
     *   <li>Calculate the next segment's starting position (1 * 4096 = 4096)
     *   <li>Acquire the segment at position 4096 (segment index 1)
     * </ol>
     *
     * <p><b>Lifecycle:</b>
     * <ul>
     *   <li>Releases the current segment (decrements refcount, may unmap if old)
     *   <li>Acquires the next segment (increments refcount, may open file if not cached)
     *   <li>Wraps the reader at offset 0 of the new segment's data region
     * </ul>
     *
     * @see <a href="https://github.com/wally/docs/adr/0005-m1-segment-coordination.md">ADR 0005</a>
     */
    private void rollToNextSegment() {
        long currentPosition = reader.position();
        // Release the current segment (may unmap if it's old and refcount hits 0)
        manager.release(current);
        // Calculate the next segment's starting position explicitly.
        // This ensures we always move forward, not re-acquire the same segment.
        long currentSegmentIndex = currentPosition / manager.segmentSize();
        long nextSegmentIndex = currentSegmentIndex + 1;
        long nextSegmentStart = nextSegmentIndex * manager.segmentSize();
        // Acquire the next segment and wrap the reader at its beginning
        current = manager.acquire(nextSegmentStart);
        reader.wrap(current.data, 0, current.file.basePosition());
    }

    /**
     * Returns the current read position (absolute offset in the log).
     *
     * <p>This position can be passed to {@link Wally#tailerFrom(long)} to resume reading
     * from this point in a new tailer instance.
     *
     * @return the absolute position of the next record to be read
     */
    public long position() {
        return reader.position();
    }
}
