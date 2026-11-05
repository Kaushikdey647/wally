package io.wally.internal.segment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;

/**
 * One memory-mapped segment file. The file starts with a 64-byte header, then records.
 *
 * <pre>
 * Offset | Size | Field
 * -------+------+------
 *   0    |  6   | magic "WALLY\0"
 *   6    |  2   | format version (1)
 *   8    |  8   | segment size (must match the file size)
 *  16    |  8   | base position (segmentIndex * segmentSize)
 *  24    | 36   | reserved (zero)
 *  60    |  4   | CRC32C of header bytes 0-59
 * </pre>
 */
public final class SegmentFile implements AutoCloseable {

    public static final int HEADER_SIZE = 64;
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'W', 'A', 'L', 'L', 'Y', 0};

    private final Path path;
    private final long segmentSize;
    private final long basePosition;
    private final Arena arena;
    private final MemorySegment mapped;

    private SegmentFile(Path path, long segmentSize, long basePosition, Arena arena, MemorySegment mapped) {
        this.path = path;
        this.segmentSize = segmentSize;
        this.basePosition = basePosition;
        this.arena = arena;
        this.mapped = mapped;
    }

    /** Opens or creates a segment file, maps it, and validates (or writes) the header. */
    public static SegmentFile open(Path path, long segmentSize, long basePosition) {
        try {
            boolean exists = Files.exists(path);
            if (!exists) {
                Files.createDirectories(path.getParent());
            }

            Arena arena = Arena.ofShared();
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                if (!exists || channel.size() == 0) {
                    channel.truncate(segmentSize);
                }
                MemorySegment mapped = channel.map(READ_WRITE, 0, segmentSize, arena);

                if (!exists || mapped.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0) == 0) {
                    writeHeader(mapped, segmentSize, basePosition);
                } else {
                    validateHeader(mapped, segmentSize, basePosition);
                }

                return new SegmentFile(path, segmentSize, basePosition, arena, mapped);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open " + path, e);
        }
    }

    private static void writeHeader(MemorySegment buffer, long segmentSize, long basePosition) {
        MemorySegment.copy(MAGIC, 0, buffer, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, MAGIC.length);
        buffer.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 6, (short) FORMAT_VERSION);
        buffer.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, segmentSize);
        buffer.set(java.lang.foreign.ValueLayout.JAVA_LONG, 16, basePosition);
        buffer.asSlice(24, 36).fill((byte) 0);

        CRC32C crc = new CRC32C();
        crc.update(buffer.asSlice(0, 60).asByteBuffer());
        buffer.set(java.lang.foreign.ValueLayout.JAVA_INT, 60, (int) crc.getValue());
    }

    private static void validateHeader(MemorySegment buffer, long segmentSize, long basePosition) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (buffer.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i) != MAGIC[i]) {
                throw new IllegalStateException("Bad magic at offset " + i);
            }
        }
        short version = buffer.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 6);
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported format version " + version);
        }
        long storedSize = buffer.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8);
        if (storedSize != segmentSize) {
            throw new IllegalStateException("Segment size mismatch: expected " + segmentSize + ", got " + storedSize);
        }
        long storedBase = buffer.get(java.lang.foreign.ValueLayout.JAVA_LONG, 16);
        if (storedBase != basePosition) {
            throw new IllegalStateException("Base position mismatch: expected " + basePosition + ", got " + storedBase);
        }

        CRC32C crc = new CRC32C();
        crc.update(buffer.asSlice(0, 60).asByteBuffer());
        int expected = (int) crc.getValue();
        int actual = buffer.get(java.lang.foreign.ValueLayout.JAVA_INT, 60);
        if (expected != actual) {
            throw new IllegalStateException("Header CRC mismatch");
        }
    }

    public MemorySegment mapped() {
        return mapped;
    }

    public long segmentSize() {
        return segmentSize;
    }

    public long basePosition() {
        return basePosition;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
