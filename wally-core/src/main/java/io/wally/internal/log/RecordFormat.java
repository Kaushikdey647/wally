package io.wally.internal.log;

import io.wally.internal.memory.Mem;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Defines the binary layout of record frames in the write-ahead log.
 *
 * <p><b>Frame Layout:</b> All frames start on an 8-byte boundary and have a 16-byte header:
 * <pre>
 * Offset | Size | Field
 * -------+------+------
 *   0    |  4   | frameLength (header + payload bytes; 0 = uncommitted)
 *   4    |  2   | type (DATA or PADDING)
 *   6    |  2   | flags (reserved for future use)
 *   8    |  4   | crc32c (payload checksum, 0 when disabled)
 *  12    |  4   | reserved
 *  16    | ...  | payload (up to frameLength - 16 bytes)
 * </pre>
 *
 * <p><b>Frame Types:</b>
 * <ul>
 *   <li><b>DATA (1):</b> A user record with a payload. Header + payload must fit in one segment.
 *   <li><b>PADDING (2):</b> Fills unused space (e.g., end of segment). Only uses frameLength and
 *       type fields, so can be as short as 8 bytes.
 * </ul>
 *
 * <p><b>Commit Protocol:</b>
 * <ul>
 *   <li>Writer reserves space, writes type/payload, then writes frameLength with release fence
 *   <li>Reader reads frameLength with acquire fence, then reads type/payload
 *   <li>If frameLength is 0, the frame is uncommitted (writer hasn't published it yet)
 * </ul>
 *
 * <p><b>Alignment:</b> Frames are aligned to 8-byte boundaries. If a frame's natural length is
 * not a multiple of 8, the writer rounds up when advancing the tail pointer, leaving zero-filled
 * padding bytes between frames.
 *
 * <p><b>Checksums:</b> Currently unused (M1 milestone). M2 will implement CRC32C payload checksums
 * when {@link io.wally.api.ChecksumMode} is enabled.
 *
 * @see SegmentWriter
 * @see SegmentReader
 */
public final class RecordFormat {

    /** Frame alignment boundary in bytes (all frames start on an 8-byte boundary). */
    public static final int ALIGNMENT = 8;

    /** Header size in bytes (includes all header fields). */
    public static final int HEADER_LENGTH = 16;

    // Header field offsets
    /** Offset of the frameLength field (4 bytes). */
    public static final int FRAME_LENGTH_OFFSET = 0;

    /** Offset of the type field (2 bytes). */
    public static final int TYPE_OFFSET = 4;

    /** Offset of the flags field (2 bytes, reserved). */
    public static final int FLAGS_OFFSET = 6;

    /** Offset of the CRC32C field (4 bytes, currently unused). */
    public static final int CRC_OFFSET = 8;

    // Frame types
    /** Data record (user payload). */
    public static final short TYPE_DATA = 1;

    /** Padding frame (fills unused space). */
    public static final short TYPE_PADDING = 2;

    /** Private constructor (static utility class). */
    private RecordFormat() {
    }

    /**
     * Reads the type field from a frame header.
     *
     * <p><b>Usage:</b> Called by the reader after reading frameLength to determine if the frame
     * is DATA or PADDING.
     *
     * @param buffer the segment buffer
     * @param frameOffset the frame's starting offset
     * @return the type (TYPE_DATA or TYPE_PADDING)
     */
    public static short type(MemorySegment buffer, long frameOffset) {
        return buffer.get(ValueLayout.JAVA_SHORT, frameOffset + TYPE_OFFSET);
    }

    /**
     * Publishes a frame by writing the frameLength field with release semantics.
     *
     * <p><b>Memory Ordering:</b> The release fence ensures that all prior header and payload
     * writes are visible before the frame becomes committed (frameLength > 0).
     *
     * <p><b>Protocol:</b> The writer MUST write all other header fields and the payload BEFORE
     * calling this method. Readers rely on the acquire fence in {@link io.wally.internal.memory.Mem#getIntAcquire}
     * to see those writes.
     *
     * @param buffer the segment buffer
     * @param frameOffset the frame's starting offset
     * @param frameLength the total frame length (header + payload)
     */
    public static void publish(MemorySegment buffer, long frameOffset, int frameLength) {
        Mem.setIntRelease(buffer, frameOffset + FRAME_LENGTH_OFFSET, frameLength);
    }

    /**
     * Writes a padding frame at the given offset.
     *
     * <p><b>Usage:</b> Called when the writer reaches the end of a segment or aborts a claim.
     * Padding frames signal to readers that there's no data in this region and they should
     * skip to the next frame or segment.
     *
     * <p><b>Minimal Format:</b> Padding frames only need frameLength and type, so the minimum
     * size is 8 bytes (frameLength + type fields).
     *
     * @param buffer the segment buffer
     * @param frameOffset the frame's starting offset
     * @param length the padding length (usually aligned to 8 bytes)
     */
    public static void writePadding(MemorySegment buffer, long frameOffset, int length) {
        // Write type field (plain store, no fence needed since publish does the release)
        buffer.set(ValueLayout.JAVA_SHORT, frameOffset + TYPE_OFFSET, TYPE_PADDING);
        // Publish with release fence
        publish(buffer, frameOffset, length);
    }
}
