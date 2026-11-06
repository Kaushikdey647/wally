package io.wally.api;

import io.wally.internal.segment.SegmentManager;

import java.nio.file.Path;

/**
 * Main entry point for the Wally write-ahead log.
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Build configuration via {@link Builder}
 *   <li>Call {@link Builder#open()} to create the Wally instance
 *   <li>Create appenders via {@link #appender()} for writing
 *   <li>Create tailers via {@link #tailerFrom(long)} for reading
 *   <li>Close the instance to unmap all segments and release resources
 * </ol>
 *
 * <p><b>Thread Safety:</b> The Wally instance itself is thread-safe and can be shared across
 * threads. However, {@link Appender} and {@link Tailer} instances are not thread-safe and should
 * be confined to a single thread each.
 *
 * <p><b>Resource Management:</b> Wally is {@link AutoCloseable}, so use it with try-with-resources
 * to ensure proper cleanup:
 * <pre>
 * try (Wally wal = Wally.builder()
 *         .directory(Path.of("data"))
 *         .segmentSize(64 * 1024 * 1024)  // 64 MB segments
 *         .checksumMode(ChecksumMode.CRC32C)
 *         .open()) {
 *
 *     // Producer thread
 *     Appender appender = wal.appender();
 *     byte[] data = "hello".getBytes();
 *     long position = appender.append(data, 0, data.length);
 *
 *     // Consumer thread
 *     Tailer tailer = wal.tailerFrom(0);  // Start from position 0
 *     tailer.poll((buf, off, len, pos) -> {
 *         // Process record
 *         System.out.println("Record at " + pos + ": " + len + " bytes");
 *     }, 100);  // Read up to 100 records
 * }
 * </pre>
 *
 * <p><b>Configuration Options:</b>
 * <ul>
 *   <li><b>directory:</b> Where segment files are stored (required)
 *   <li><b>segmentSize:</b> Size of each segment file in bytes (default: 64 MB)
 *   <li><b>checksumMode:</b> Payload checksum mode (default: NONE, M2 will add CRC32C)
 * </ul>
 *
 * <p><b>Milestone Status:</b> This is the M1 implementation with:
 * <ul>
 *   <li>✓ Memory-mapped segments with proper rollover
 *   <li>✓ Single-writer, multi-reader support
 *   <li>✓ Segment header validation with CRC32C
 *   <li>✗ Tail recovery on reopen (M1 deferred)
 *   <li>✗ Payload checksums (M2)
 *   <li>✗ Multi-writer coordination (M3)
 *   <li>✗ Named consumer groups with offset tracking (M4)
 * </ul>
 *
 * @see Appender
 * @see Tailer
 * @see WallyConfig
 */
public final class Wally implements AutoCloseable {

    /** The WAL configuration (directory, segment size, checksum mode). */
    private final WallyConfig config;

    /** Manages the lifecycle of segment files (pooling, refcounting, rolling). */
    private final SegmentManager segmentManager;

    /**
     * Private constructor called by {@link Builder#open()}.
     *
     * @param config the validated configuration
     */
    private Wally(WallyConfig config) {
        this.config = config;
        // Create the segment manager (it will create the directory if needed)
        this.segmentManager = new SegmentManager(config.directory(), config.segmentSize());
    }

    /**
     * Creates a new builder for configuring a Wally instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new appender for writing records to the log.
     *
     * <p><b>Thread Safety:</b> Each appender is confined to a single thread. If you have multiple
     * producer threads, create one appender per thread.
     *
     * <p><b>Lifecycle:</b> Appenders don't need to be closed (they don't hold resources). The
     * segment manager tracks refcounts and cleans up segments automatically.
     *
     * @return a new appender starting at the current tail position
     */
    public Appender appender() {
        return new Appender(segmentManager, config);
    }

    /**
     * Creates a new tailer for reading records from the log, starting at the given position.
     *
     * <p><b>Thread Safety:</b> Each tailer is confined to a single thread. If you have multiple
     * consumer threads, create one tailer per thread.
     *
     * <p><b>Position:</b> The position can be:
     * <ul>
     *   <li>0 to read from the beginning
     *   <li>A position returned by {@link Appender#append} or {@link Tailer#position()} to resume
     *   <li>Mid-segment (the tailer will start reading from that offset within the segment)
     * </ul>
     *
     * <p><b>Lifecycle:</b> Tailers don't need to be closed (they don't hold resources). The
     * segment manager tracks refcounts and cleans up segments automatically.
     *
     * @param position the absolute position to start reading from
     * @return a new tailer starting at the specified position
     */
    public Tailer tailerFrom(long position) {
        return new Tailer(segmentManager, position);
    }

    /**
     * Returns the configuration used to create this instance.
     *
     * @return the configuration
     */
    public WallyConfig config() {
        return config;
    }

    /**
     * Closes the WAL, unmapping all segments and releasing resources.
     *
     * <p><b>Cleanup:</b>
     * <ul>
     *   <li>Unmaps all cached segments (closes arenas)
     *   <li>Releases file descriptors
     *   <li>Clears the segment pool
     * </ul>
     *
     * <p><b>Post-Close Behavior:</b> Creating new appenders or tailers after close is undefined.
     * Existing appenders/tailers may fail with segmentation faults if they try to access unmapped
     * memory.
     */
    @Override
    public void close() {
        segmentManager.close();
    }

    /**
     * Builder for configuring and opening a Wally instance.
     *
     * <p><b>Usage:</b>
     * <pre>
     * Wally wal = Wally.builder()
     *     .directory(Path.of("data"))
     *     .segmentSize(64 * 1024 * 1024)  // 64 MB
     *     .checksumMode(ChecksumMode.CRC32C)
     *     .open();
     * </pre>
     */
    public static final class Builder {
        /** Private constructor (use {@link Wally#builder()}). */
        Builder() {
        }

        /** Delegates configuration to WallyConfig.Builder. */
        private final WallyConfig.Builder configBuilder = WallyConfig.builder();

        /**
         * Sets the directory where segment files will be stored.
         *
         * <p><b>Required:</b> This is the only required configuration option.
         *
         * <p><b>Directory Creation:</b> The directory (and its parents) will be created if it
         * doesn't exist when the first segment is opened.
         *
         * @param directory the directory path
         * @return this builder
         */
        public Builder directory(Path directory) {
            configBuilder.directory(directory);
            return this;
        }

        /**
         * Sets the segment size in bytes.
         *
         * <p><b>Default:</b> 64 MB (67,108,864 bytes).
         *
         * <p><b>Alignment:</b> Should be a multiple of the page size (typically 4 KB) for optimal
         * mmap performance.
         *
         * <p><b>Trade-offs:</b>
         * <ul>
         *   <li>Larger segments: fewer file descriptors, less rollover overhead, but slower recovery
         *   <li>Smaller segments: faster recovery, more file descriptors, more rollover overhead
         * </ul>
         *
         * @param segmentSize the segment size in bytes
         * @return this builder
         */
        public Builder segmentSize(long segmentSize) {
            configBuilder.segmentSize(segmentSize);
            return this;
        }

        /**
         * Sets the checksum mode for payload validation.
         *
         * <p><b>Default:</b> {@link ChecksumMode#NONE} (M1 milestone).
         *
         * <p><b>M2 Milestone:</b> Will add CRC32C payload checksums when enabled. The checksum is
         * computed over the payload and stored in the frame header. On read, the checksum is
         * validated to detect corruption.
         *
         * @param mode the checksum mode
         * @return this builder
         */
        public Builder checksumMode(ChecksumMode mode) {
            configBuilder.checksumMode(mode);
            return this;
        }

        /**
         * Sets the producer concurrency mode.
         *
         * <p><b>Default:</b> {@link ProducerMode#SINGLE} (M1 compatible).
         *
         * <p><b>Modes:</b>
         * <ul>
         *   <li>{@link ProducerMode#SINGLE}: One writer thread, no atomic operations (fastest)
         *   <li>{@link ProducerMode#MULTI}: Multiple writer threads, lock-free coordination (M2)
         * </ul>
         *
         * <p><b>Example:</b>
         * <pre>
         * // Multi-producer setup
         * Wally wal = Wally.builder()
         *     .directory(path)
         *     .producerMode(ProducerMode.MULTI)
         *     .open();
         *
         * // Thread 1
         * Appender a1 = wal.appender();
         * a1.append(...);
         *
         * // Thread 2
         * Appender a2 = wal.appender();
         * a2.append(...);
         * </pre>
         *
         * @param mode the producer mode
         * @return this builder
         */
        public Builder producerMode(ProducerMode mode) {
            configBuilder.producerMode(mode);
            return this;
        }

        /**
         * Builds the configuration and opens the WAL.
         *
         * <p><b>Side Effects:</b>
         * <ul>
         *   <li>Creates the segment manager
         *   <li>May create the directory if it doesn't exist (on first segment open)
         * </ul>
         *
         * @return the opened Wally instance
         * @throws IllegalArgumentException if the directory is not set
         */
        public Wally open() {
            return new Wally(configBuilder.build());
        }
    }
}
