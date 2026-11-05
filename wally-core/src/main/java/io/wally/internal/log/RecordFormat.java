package io.wally.internal.log;

import io.wally.internal.memory.Mem;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Record frame layout. Frames start on an 8-byte boundary:
 *
 * <pre>
 *  0  int   frameLength  header + payload bytes; 0 = not committed (written last, release)
 *  4  short type         DATA or PADDING
 *  6  short flags
 *  8  int   crc32c       payload checksum, 0 when disabled
 * 12  int   reserved
 * 16  ...   payload
 * </pre>
 *
 * A padding frame only uses {@code frameLength} and {@code type}, so it can be as short as 8 bytes.
 */
public final class RecordFormat {

    public static final int ALIGNMENT = 8;
    public static final int HEADER_LENGTH = 16;

    public static final int FRAME_LENGTH_OFFSET = 0;
    public static final int TYPE_OFFSET = 4;
    public static final int FLAGS_OFFSET = 6;
    public static final int CRC_OFFSET = 8;

    public static final short TYPE_DATA = 1;
    public static final short TYPE_PADDING = 2;

    private RecordFormat() {
    }

    public static short type(MemorySegment buffer, long frameOffset) {
        return buffer.get(ValueLayout.JAVA_SHORT, frameOffset + TYPE_OFFSET);
    }

    /** Publishes a frame: every other header and payload write must happen before this. */
    public static void publish(MemorySegment buffer, long frameOffset, int frameLength) {
        Mem.setIntRelease(buffer, frameOffset + FRAME_LENGTH_OFFSET, frameLength);
    }

    public static void writePadding(MemorySegment buffer, long frameOffset, int length) {
        buffer.set(ValueLayout.JAVA_SHORT, frameOffset + TYPE_OFFSET, TYPE_PADDING);
        publish(buffer, frameOffset, length);
    }
}
