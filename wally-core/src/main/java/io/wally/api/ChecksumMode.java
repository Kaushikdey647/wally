package io.wally.api;

/** Whether to compute and verify CRC32C checksums. */
public enum ChecksumMode {
    /** No checksums. Fastest, but corruption is not detected. */
    NONE,
    /** CRC32C checksum per record. Adds a few nanoseconds per append and read. */
    CRC32C
}
