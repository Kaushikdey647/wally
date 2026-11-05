package io.wally.internal.memory;

public final class Align {

    private Align() {
    }

    /** Rounds {@code value} up to a multiple of {@code alignment}, which must be a power of two. */
    public static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    public static long align(long value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    public static boolean isAligned(long value, int alignment) {
        return (value & (alignment - 1)) == 0;
    }

    public static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
