package io.wally.api;

import io.wally.internal.log.SegmentReader;
import io.wally.internal.segment.SegmentDescriptor;
import io.wally.internal.segment.SegmentFile;
import io.wally.internal.segment.SegmentManager;

/**
 * Read handle. Not thread-safe: one tailer per consumer thread.
 *
 * <p>This is an ad-hoc tailer; it starts at a given position and doesn't persist its cursor.
 * Named consumer groups (M4) will remember their position across restarts.
 */
public final class Tailer {

    private final SegmentManager manager;
    private final SegmentReader reader = new SegmentReader();
    private SegmentDescriptor current;

    Tailer(SegmentManager manager, long startPosition) {
        this.manager = manager;
        this.current = manager.acquire(startPosition);
        long offsetInSegment = startPosition - current.file.basePosition();
        reader.wrap(current.data, offsetInSegment, current.file.basePosition());
    }

    /**
     * Delivers up to {@code recordLimit} records via the handler, stopping at the first uncommitted
     * frame or the end of the current segment.
     *
     * @return number of records delivered (padding frames are not counted)
     */
    public int poll(RecordHandler handler, int recordLimit) {
        int count = reader.read(handler, recordLimit);
        // Roll to the next segment if we consumed everything up to the segment boundary
        // (reader.isAtEnd() means we hit the segment size limit, not an uncommitted frame)
        if (reader.isAtEnd() && reader.position() > current.file.basePosition()) {
            rollToNextSegment();
        }
        return count;
    }

    private void rollToNextSegment() {
        long nextPosition = reader.position();
        manager.release(current);
        current = manager.acquire(nextPosition);
        long offsetInSegment = nextPosition - current.file.basePosition();
        reader.wrap(current.data, offsetInSegment, current.file.basePosition());
    }

    public long position() {
        return reader.position();
    }
}
