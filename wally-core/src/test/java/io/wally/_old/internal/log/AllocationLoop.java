package io.wally.internal.log;

import io.wally.api.RecordHandler;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Steady-state append/read loop shared by the allocation tests. */
final class AllocationLoop implements RecordHandler {

    private static final long START = 64;

    private final MemorySegment buffer = Arena.ofAuto().allocate(1 << 20, 4096);
    private final MemorySegment src = Arena.ofAuto().allocate(64);
    private final SegmentWriter writer = new SegmentWriter();
    private final SegmentReader reader = new SegmentReader();
    long checksum;

    AllocationLoop() {
        reset();
    }

    void run(long iterations) {
        for (long i = 0; i < iterations; i++) {
            src.set(ValueLayout.JAVA_LONG, 0, i);
            if (writer.append(src, 0, 64) == SegmentWriter.NO_SPACE) {
                reset();
                writer.append(src, 0, 64);
            }
            reader.read(this, 1);
        }
    }

    private void reset() {
        buffer.fill((byte) 0);
        writer.wrap(buffer, START, 0);
        reader.wrap(buffer, START, 0);
    }

    @Override
    public void onRecord(MemorySegment buffer, long offset, int length, long position) {
        checksum += buffer.get(ValueLayout.JAVA_LONG, offset);
    }
}
