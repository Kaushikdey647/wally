package io.wally.spsc.internal;

import java.lang.foreign.MemorySegment;

/**
 * Cache-line padded head and tail cursors for SPSC coordination.
 *
 * <p>Layout (128 bytes each to prevent false sharing on any platform):
 * <pre>
 *   [pad: 56 bytes] [head: 8 bytes] [pad: 64 bytes]
 *   [pad: 56 bytes] [tail: 8 bytes] [pad: 64 bytes]
 * </pre>
 *
 * <p>Writer owns tail (read-write), reads head (read-only).
 * Reader owns head (read-write), reads tail (read-only).
 */
public final class Cursors {

    private static final int CACHE_LINE = 64;
    private static final int CURSOR_SIZE = CACHE_LINE * 2;  // 128 bytes per cursor
    private static final int CURSOR_OFFSET = 56;            // offset to the actual long value

    public static final int HEAD_BASE = 0;
    public static final int TAIL_BASE = CURSOR_SIZE;
    public static final int TOTAL_SIZE = CURSOR_SIZE * 2;

    private Cursors() {
    }

    public static MemorySegment allocate(long capacity, java.lang.foreign.Arena arena) {
        // Allocate cursors + ring buffer in one contiguous block
        long totalSize = TOTAL_SIZE + capacity;
        return arena.allocate(totalSize, 64);
    }

    public static long headOffset() {
        return HEAD_BASE + CURSOR_OFFSET;
    }

    public static long tailOffset() {
        return TAIL_BASE + CURSOR_OFFSET;
    }

    /**
     * @return offset where ring buffer data starts (after cursor metadata), 8-byte aligned
     */
    public static long dataOffset() {
        // Ensure data section starts on 8-byte boundary
        return ((TOTAL_SIZE + 7) / 8) * 8;
    }
}
