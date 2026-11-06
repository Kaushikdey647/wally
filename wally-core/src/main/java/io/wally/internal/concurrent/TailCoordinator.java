package io.wally.internal.concurrent;

import io.wally.api.ProducerMode;

/**
 * Coordinates tail position claims across multiple producers.
 *
 * <p>In single-producer mode, tail updates use plain volatile stores (no contention).
 * In multi-producer mode, tail claims use atomic fetch-and-add for lock-free coordination.
 *
 * <p><b>Thread Safety:</b>
 * <ul>
 *   <li><b>SINGLE mode:</b> Only one thread should call {@link #claimAndAdvance}. Multiple calls
 *       from different threads will corrupt the tail (no atomicity).</li>
 *   <li><b>MULTI mode:</b> Multiple threads can safely call {@link #claimAndAdvance} concurrently.
 *       Uses atomic getAndAdd for lock-free coordination.</li>
 * </ul>
 *
 * <p><b>Usage Pattern:</b>
 * <pre>
 * TailCoordinator tail = new TailCoordinator(ProducerMode.MULTI, segmentSize);
 *
 * // Producer thread 1
 * long claimedOffset = tail.claimAndAdvance(80);  // Claims 80 bytes
 * if (claimedOffset >= 0) {
 *     // Write record at claimedOffset
 * }
 *
 * // Producer thread 2 (concurrent)
 * long claimedOffset = tail.claimAndAdvance(80);  // Claims next 80 bytes
 * </pre>
 */
public final class TailCoordinator {

    /**
     * Shared tail counter (padded to prevent false sharing).
     * Used in MULTI mode for atomic claims.
     */
    private final PaddedCounter sharedTail;

    /**
     * Local tail value for SINGLE mode.
     * Not volatile - only one thread writes, so no need for synchronization.
     */
    private long localTail;

    /**
     * Producer mode (SINGLE or MULTI).
     */
    private final ProducerMode mode;

    /**
     * Segment size in bytes (used for rollover detection).
     */
    private final long segmentSize;

    /**
     * Base position of the current segment.
     */
    private volatile long segmentBasePosition;

    /**
     * Creates a tail coordinator for the given producer mode.
     *
     * @param mode the producer mode (SINGLE or MULTI)
     * @param segmentSize the segment size in bytes
     */
    public TailCoordinator(ProducerMode mode, long segmentSize) {
        this.mode = mode;
        this.segmentSize = segmentSize;
        this.segmentBasePosition = 0;

        if (mode == ProducerMode.MULTI) {
            this.sharedTail = new PaddedCounter(0);
            this.localTail = -1;  // Unused in MULTI mode
        } else {
            this.sharedTail = null;  // Unused in SINGLE mode
            this.localTail = 0;
        }
    }

    /**
     * Claims space and advances the tail by the given delta.
     *
     * <p>In SINGLE mode, this uses a plain increment (no atomic operation).
     * In MULTI mode, this uses atomic getAndAdd for lock-free coordination.
     *
     * @param delta the number of bytes to claim
     * @return the offset within the current segment where the claim starts
     */
    public long claimAndAdvance(long delta) {
        if (mode == ProducerMode.MULTI) {
            return sharedTail.getAndAdd(delta);
        } else {
            long claimed = localTail;
            localTail += delta;
            return claimed;
        }
    }

    /**
     * Gets the current tail position without advancing it.
     *
     * @return the current tail offset within the segment
     */
    public long get() {
        if (mode == ProducerMode.MULTI) {
            return sharedTail.get();
        } else {
            return localTail;
        }
    }

    /**
     * Sets the tail to a new position (used during segment rollover).
     *
     * <p>In MULTI mode, this uses a volatile store to ensure visibility.
     * In SINGLE mode, this uses a plain store.
     *
     * @param newTail the new tail offset
     */
    public void set(long newTail) {
        if (mode == ProducerMode.MULTI) {
            sharedTail.set(newTail);
        } else {
            localTail = newTail;
        }
    }

    /**
     * Sets the base position of the current segment.
     *
     * <p>Used by the segment manager when rolling to a new segment.
     *
     * @param basePosition the absolute position of the segment's first byte
     */
    public void setSegmentBasePosition(long basePosition) {
        this.segmentBasePosition = basePosition;
    }

    /**
     * Gets the base position of the current segment.
     *
     * @return the segment's base position
     */
    public long getSegmentBasePosition() {
        return segmentBasePosition;
    }

    /**
     * Converts a segment-relative offset to an absolute position.
     *
     * @param offsetInSegment the offset within the current segment
     * @return the absolute position in the log
     */
    public long toAbsolutePosition(long offsetInSegment) {
        return segmentBasePosition + offsetInSegment;
    }

    /**
     * Gets the producer mode.
     *
     * @return the producer mode (SINGLE or MULTI)
     */
    public ProducerMode mode() {
        return mode;
    }
}
