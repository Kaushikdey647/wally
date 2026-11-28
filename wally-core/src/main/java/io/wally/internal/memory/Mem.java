package io.wally.internal.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Acquire/release access to off-heap memory. Offsets must be naturally aligned.
 *
 * <p>Frames use native-order layouts and Wally only supports little-endian platforms (x86-64, AArch64).
 */
public final class Mem {

    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

    static {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new ExceptionInInitializerError("Wally requires a little-endian platform");
        }
    }

    private Mem() {
    }

    public static long getLongAcquire(MemorySegment segment, long offset) {
        return (long) LONG.getAcquire(segment, offset);
    }

    public static void setLongRelease(MemorySegment segment, long offset, long value) {
        LONG.setRelease(segment, offset, value);
    }
}
