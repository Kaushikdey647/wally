package io.wally.spsc;

import io.wally.internal.memory.Align;

import java.lang.foreign.Arena;

/**
 * Configuration for a SPSC ring buffer.
 */
public final class RingBufferConfig {

    private int capacity = 1024 * 1024;  // 1 MB default
    private int alignment = 64;           // cache-line alignment
    private Arena arena = Arena.ofAuto();

    public RingBufferConfig() {
    }

    public RingBufferConfig capacity(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (!Align.isPowerOfTwo(bytes)) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        this.capacity = bytes;
        return this;
    }

    public RingBufferConfig alignment(int bytes) {
        if (!Align.isPowerOfTwo(bytes)) {
            throw new IllegalArgumentException("alignment must be a power of two");
        }
        this.alignment = bytes;
        return this;
    }

    public RingBufferConfig arena(Arena arena) {
        this.arena = arena;
        return this;
    }

    public int capacity() {
        return capacity;
    }

    public int alignment() {
        return alignment;
    }

    public Arena arena() {
        return arena;
    }

    /**
     * @return capacity - 1, used as a mask for fast modulo
     */
    int mask() {
        return capacity - 1;
    }
}
