package io.wally.api;

import io.wally.internal.memory.Align;

import java.nio.file.Path;

/** Configuration for a Wally log. Immutable once built. */
public final class WallyConfig {

    private final Path directory;
    private final long segmentSize;
    private final ChecksumMode checksumMode;

    private WallyConfig(Path directory, long segmentSize, ChecksumMode checksumMode) {
        this.directory = directory;
        this.segmentSize = segmentSize;
        this.checksumMode = checksumMode;
    }

    public Path directory() {
        return directory;
    }

    public long segmentSize() {
        return segmentSize;
    }

    public ChecksumMode checksumMode() {
        return checksumMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        Builder() {
        }

        private Path directory;
        private long segmentSize = 1L << 30;  // 1 GiB default
        private ChecksumMode checksumMode = ChecksumMode.CRC32C;

        public Builder directory(Path directory) {
            this.directory = directory;
            return this;
        }

        public Builder segmentSize(long segmentSize) {
            this.segmentSize = segmentSize;
            return this;
        }

        public Builder checksumMode(ChecksumMode mode) {
            this.checksumMode = mode;
            return this;
        }

        public WallyConfig build() {
            if (directory == null) {
                throw new IllegalArgumentException("directory is required");
            }
            if (segmentSize < 4096 || !Align.isPowerOfTwo(segmentSize)) {
                throw new IllegalArgumentException("segmentSize must be >= 4096 and a power of two, got " + segmentSize);
            }
            return new WallyConfig(directory, segmentSize, checksumMode);
        }
    }
}
