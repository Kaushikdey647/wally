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
 * Represents a single memory-mapped segment file in the write-ahead log.
 *
 * <p><b>File Structure:</b> Each segment file consists of a 64-byte header followed by a data
 * region where records are written:
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
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>File is created and pre-allocated to {@code segmentSize} bytes
 *   <li>Header is written and checksummed (for new files)
 *   <li>Header is validated (for existing files)
 *   <li>File is memory-mapped using {@link FileChannel#map}
 *   <li>Mapped region is managed by a {@link Arena} for automatic cleanup
 *   <li>File is unmapped when {@link #close()} is called (or when Arena scope ends)
 * </ol>
 *
 * <p><b>Memory Mapping:</b> Uses {@link Arena#ofShared()} to allow multi-threaded access to the
 * mapped memory. The arena's lifecycle is tied to this object.
 *
 * <p><b>Header Validation:</b> On open, the header is validated to ensure:
 * <ul>
 *   <li>Magic bytes match "WALLY\0"
 *   <li>Format version is 1 (only version currently supported)
 *   <li>Segment size matches the expected size
 *   <li>Base position matches the expected position
 *   <li>CRC32C checksum is correct
 * </ul>
 *
 * <p><b>Zero-Initialization:</b> New files are pre-allocated by the OS, which typically zero-fills
 * them. The data region (bytes 64+) starts with all zeros, making frameLength == 0 (uncommitted).
 *
 * @see SegmentDescriptor
 * @see SegmentManager
 */
public final class SegmentFile implements AutoCloseable {

    /** Size of the segment header in bytes. */
    public static final int HEADER_SIZE = 64;

    /** Current format version (only version 1 is supported). */
    public static final int FORMAT_VERSION = 1;

    /** Magic bytes identifying a Wally segment file. */
    private static final byte[] MAGIC = {'W', 'A', 'L', 'L', 'Y', 0};

    /** Path to the segment file on disk. */
    private final Path path;

    /** Total size of the segment file (header + data). */
    private final long segmentSize;

    /** Absolute position of this segment's first byte in the log. */
    private final long basePosition;

    /** Arena managing the memory-mapped region's lifecycle. */
    private final Arena arena;

    /** The memory-mapped region (entire file, including header). */
    private final MemorySegment mapped;

    /**
     * Private constructor called by {@link #open}.
     *
     * @param path the segment file's path
     * @param segmentSize the segment size in bytes
     * @param basePosition the absolute position of this segment in the log
     * @param arena the arena managing the mapped memory's lifecycle
     * @param mapped the memory-mapped region (entire file)
     */
    private SegmentFile(Path path, long segmentSize, long basePosition, Arena arena, MemorySegment mapped) {
        this.path = path;
        this.segmentSize = segmentSize;
        this.basePosition = basePosition;
        this.arena = arena;
        this.mapped = mapped;
    }

    /**
     * Opens or creates a segment file, maps it into memory, and validates the header.
     *
     * <p><b>New File Flow:</b>
     * <ol>
     *   <li>Create parent directories if needed
     *   <li>Open the file with CREATE flag
     *   <li>Pre-allocate to {@code segmentSize} bytes via {@link FileChannel#truncate}
     *   <li>Memory-map the file
     *   <li>Write and checksum the header
     * </ol>
     *
     * <p><b>Existing File Flow:</b>
     * <ol>
     *   <li>Open the file
     *   <li>Memory-map the file
     *   <li>Validate the header (magic, version, size, position, CRC)
     *   <li>If validation fails, throw IllegalStateException
     * </ol>
     *
     * <p><b>Memory Mapping:</b> Uses {@link Arena#ofShared()} to allow multi-threaded access.
     * The mapping mode is {@link java.nio.channels.FileChannel.MapMode#READ_WRITE}.
     *
     * <p><b>Zero-Detection:</b> If the first byte is 0, the header is considered uninitialized
     * and will be written even if the file exists (handles crashed/partial writes).
     *
     * @param path the segment file's path (e.g., "data/00000000000000000001.wly")
     * @param segmentSize the total size of the segment file in bytes
     * @param basePosition the absolute position of this segment in the log (index * segmentSize)
     * @return the opened and mapped segment file
     * @throws UncheckedIOException if the file cannot be opened or mapped
     * @throws IllegalStateException if header validation fails
     */
    public static SegmentFile open(Path path, long segmentSize, long basePosition) {
        try {
            boolean exists = Files.exists(path);
            if (!exists) {
                // Create parent directories if needed
                Files.createDirectories(path.getParent());
            }

            Arena arena = Arena.ofShared();
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                if (!exists || channel.size() == 0) {
                    // Pre-allocate the file to segmentSize
                    channel.truncate(segmentSize);
                }
                // Map the entire file (header + data)
                MemorySegment mapped = channel.map(READ_WRITE, 0, segmentSize, arena);

                if (!exists || mapped.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0) == 0) {
                    // New or uninitialized file: write header
                    writeHeader(mapped, segmentSize, basePosition);
                } else {
                    // Existing file: validate header
                    validateHeader(mapped, segmentSize, basePosition);
                }

                return new SegmentFile(path, segmentSize, basePosition, arena, mapped);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open " + path, e);
        }
    }

    /**
     * Writes the segment header at the beginning of the mapped region.
     *
     * <p><b>Header Layout:</b>
     * <ul>
     *   <li>0-5: magic bytes "WALLY\0"
     *   <li>6-7: format version (1)
     *   <li>8-15: segment size
     *   <li>16-23: base position
     *   <li>24-59: reserved (zero-filled)
     *   <li>60-63: CRC32C of bytes 0-59
     * </ul>
     *
     * <p><b>Checksum:</b> The CRC32C is computed over the first 60 bytes and stored at offset 60.
     * This allows header integrity validation on open.
     *
     * @param buffer the memory-mapped region
     * @param segmentSize the segment size to write
     * @param basePosition the base position to write
     */
    private static void writeHeader(MemorySegment buffer, long segmentSize, long basePosition) {
        // Write magic bytes
        MemorySegment.copy(MAGIC, 0, buffer, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, MAGIC.length);
        // Write format version
        buffer.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 6, (short) FORMAT_VERSION);
        // Write segment size
        buffer.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, segmentSize);
        // Write base position
        buffer.set(java.lang.foreign.ValueLayout.JAVA_LONG, 16, basePosition);
        // Zero-fill reserved region
        buffer.asSlice(24, 36).fill((byte) 0);

        // Compute CRC32C of first 60 bytes
        CRC32C crc = new CRC32C();
        crc.update(buffer.asSlice(0, 60).asByteBuffer());
        // Write CRC at offset 60
        buffer.set(java.lang.foreign.ValueLayout.JAVA_INT, 60, (int) crc.getValue());
    }

    /**
     * Validates the segment header against expected values.
     *
     * <p><b>Validation Checks:</b>
     * <ol>
     *   <li>Magic bytes match "WALLY\0"
     *   <li>Format version is 1 (only version currently supported)
     *   <li>Segment size matches the expected size
     *   <li>Base position matches the expected position
     *   <li>CRC32C checksum is correct
     * </ol>
     *
     * <p><b>Failure Handling:</b> Any validation failure throws {@link IllegalStateException},
     * preventing the segment from being used. This protects against:
     * <ul>
     *   <li>Corrupt files (disk errors, partial writes)
     *   <li>Version mismatches (old format, future format)
     *   <li>Misplaced files (wrong directory, manual copy/move)
     *   <li>Configuration changes (segment size changed)
     * </ul>
     *
     * @param buffer the memory-mapped region
     * @param segmentSize the expected segment size
     * @param basePosition the expected base position
     * @throws IllegalStateException if any validation check fails
     */
    private static void validateHeader(MemorySegment buffer, long segmentSize, long basePosition) {
        // Validate magic bytes
        for (int i = 0; i < MAGIC.length; i++) {
            if (buffer.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i) != MAGIC[i]) {
                throw new IllegalStateException("Bad magic at offset " + i);
            }
        }
        // Validate format version
        short version = buffer.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 6);
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported format version " + version);
        }
        // Validate segment size
        long storedSize = buffer.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8);
        if (storedSize != segmentSize) {
            throw new IllegalStateException("Segment size mismatch: expected " + segmentSize + ", got " + storedSize);
        }
        // Validate base position
        long storedBase = buffer.get(java.lang.foreign.ValueLayout.JAVA_LONG, 16);
        if (storedBase != basePosition) {
            throw new IllegalStateException("Base position mismatch: expected " + basePosition + ", got " + storedBase);
        }

        // Validate CRC32C checksum
        CRC32C crc = new CRC32C();
        crc.update(buffer.asSlice(0, 60).asByteBuffer());
        int expected = (int) crc.getValue();
        int actual = buffer.get(java.lang.foreign.ValueLayout.JAVA_INT, 60);
        if (expected != actual) {
            throw new IllegalStateException("Header CRC mismatch");
        }
    }

    /**
     * Returns the memory-mapped region (entire file, including the 64-byte header).
     *
     * <p><b>Usage:</b> Typically sliced to skip the header:
     * {@code mapped().asSlice(HEADER_SIZE)} for the data region.
     *
     * @return the memory-mapped segment
     */
    public MemorySegment mapped() {
        return mapped;
    }

    /**
     * Returns the segment size (header + data).
     *
     * @return segment size in bytes
     */
    public long segmentSize() {
        return segmentSize;
    }

    /**
     * Returns the absolute position of this segment in the log.
     *
     * @return base position (segmentIndex * segmentSize)
     */
    public long basePosition() {
        return basePosition;
    }

    /**
     * Returns the path to the segment file.
     *
     * @return the file path (e.g., "data/00000000000000000001.wly")
     */
    public Path path() {
        return path;
    }

    /**
     * Unmaps the segment and closes the arena.
     *
     * <p><b>Lifecycle:</b> Called by {@link SegmentManager} when the segment is no longer in use
     * (refcount reaches 0 and the segment is old). Closing the arena unmaps the memory and
     * releases the file descriptor.
     *
     * <p><b>Idempotency:</b> Safe to call multiple times (checks if the arena is still alive).
     */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();  // Unmaps the memory and closes the file
        }
    }
}
