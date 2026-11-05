package io.wally.api;

import io.wally.internal.log.SegmentWriter;
import io.wally.internal.segment.SegmentDescriptor;
import io.wally.internal.segment.SegmentFile;
import io.wally.internal.segment.SegmentManager;

import java.lang.foreign.MemorySegment;

/**
 * Per-thread write handle. Not thread-safe: one appender per producer thread.
 *
 * <p>The appender itself implements {@link Claim}, so the zero-copy path needs no extra object.
 */
public final class Appender implements Claim {

    private final SegmentManager manager;
    private final WallyConfig config;
    private final SegmentWriter writer = new SegmentWriter();
    private SegmentDescriptor current;

    Appender(SegmentManager manager, WallyConfig config) {
        this.manager = manager;
        this.config = config;
        this.current = manager.acquire(0);
        writer.wrap(current.data, 0, current.file.basePosition());
    }

    /**
     * Appends a record by copying {@code src[srcOffset, srcOffset + length)} into the log.
     * @return the record's position, or a negative code on failure
     */
    public long append(MemorySegment src, long srcOffset, int length) {
        long position = writer.append(src, srcOffset, length);
        if (position == SegmentWriter.NO_SPACE) {
            roll();
            position = writer.append(src, srcOffset, length);
        }
        return position;
    }

    public long append(byte[] src, int srcOffset, int length) {
        long position = writer.append(src, srcOffset, length);
        if (position == SegmentWriter.NO_SPACE) {
            roll();
            position = writer.append(src, srcOffset, length);
        }
        return position;
    }

    /**
     * Reserves space for a zero-copy write. Returns the position, or a negative code.
     * The claim is valid until {@link #commit()} or {@link #abort()}.
     */
    public long tryClaim(int payloadLength) {
        long position = writer.tryClaim(payloadLength);
        if (position == SegmentWriter.NO_SPACE) {
            roll();
            position = writer.tryClaim(payloadLength);
        }
        return position;
    }

    private void roll() {
        writer.padToEnd();
        manager.release(current);
        manager.rollToNext();
        long nextPosition = writer.tailPosition();
        current = manager.acquire(nextPosition);
        writer.wrap(current.data, 0, current.file.basePosition());
    }

    @Override
    public MemorySegment buffer() {
        return writer.buffer();
    }

    @Override
    public long offset() {
        return writer.offset();
    }

    @Override
    public int length() {
        return writer.length();
    }

    @Override
    public long position() {
        return writer.position();
    }

    @Override
    public void commit() {
        writer.commit();
    }

    @Override
    public void abort() {
        writer.abort();
    }
}
