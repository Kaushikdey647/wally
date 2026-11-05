package io.wally.internal.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Ordered and atomic access to off-heap memory. Offsets must be naturally aligned.
 *
 * <p>Wally's on-disk format is little-endian and uses native-order layouts, so it only runs on
 * little-endian platforms (x86-64, AArch64).
 */
public final class Mem {

    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

    static {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new ExceptionInInitializerError("Wally requires a little-endian platform");
        }
    }

    private Mem() {
    }

    public static int getIntAcquire(MemorySegment segment, long offset) {
        return (int) INT.getAcquire(segment, offset);
    }

    public static void setIntRelease(MemorySegment segment, long offset, int value) {
        INT.setRelease(segment, offset, value);
    }

    public static long getLongAcquire(MemorySegment segment, long offset) {
        return (long) LONG.getAcquire(segment, offset);
    }

    public static void setLongRelease(MemorySegment segment, long offset, long value) {
        LONG.setRelease(segment, offset, value);
    }

    public static long getLongVolatile(MemorySegment segment, long offset) {
        return (long) LONG.getVolatile(segment, offset);
    }

    public static long getAndAddLong(MemorySegment segment, long offset, long delta) {
        return (long) LONG.getAndAdd(segment, offset, delta);
    }

    public static boolean compareAndSetLong(MemorySegment segment, long offset, long expected, long value) {
        return LONG.compareAndSet(segment, offset, expected, value);
    }
}
