package io.wally.api;

import io.wally.internal.segment.SegmentManager;

import java.nio.file.Path;

/**
 * Entry point: opens a log, creates appenders and tailers, and closes everything.
 *
 * <pre>
 * try (Wally wal = Wally.builder().directory(Path.of("data")).build().open()) {
 *     Appender appender = wal.appender();
 *     appender.append(bytes, 0, bytes.length);
 *
 *     Tailer tailer = wal.tailerFrom(0);
 *     tailer.poll((buf, off, len, pos) -> { ... }, 100);
 * }
 * </pre>
 */
public final class Wally implements AutoCloseable {

    private final WallyConfig config;
    private final SegmentManager segmentManager;

    private Wally(WallyConfig config) {
        this.config = config;
        this.segmentManager = new SegmentManager(config.directory(), config.segmentSize());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Appender appender() {
        return new Appender(segmentManager, config);
    }

    public Tailer tailerFrom(long position) {
        return new Tailer(segmentManager, position);
    }

    public WallyConfig config() {
        return config;
    }

    @Override
    public void close() {
        segmentManager.close();
    }

    public static final class Builder {
        Builder() {
        }

        private final WallyConfig.Builder configBuilder = WallyConfig.builder();

        public Builder directory(Path directory) {
            configBuilder.directory(directory);
            return this;
        }

        public Builder segmentSize(long segmentSize) {
            configBuilder.segmentSize(segmentSize);
            return this;
        }

        public Builder checksumMode(ChecksumMode mode) {
            configBuilder.checksumMode(mode);
            return this;
        }

        public Wally open() {
            return new Wally(configBuilder.build());
        }
    }
}
