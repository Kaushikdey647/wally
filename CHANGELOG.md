# Changelog

All notable changes to Wally will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### M1: Memory-mapped segments, rollover, checksums and public API (IN PROGRESS)

#### Added
- `SegmentFile`: 64-byte header with magic, version, segment size, base position, and CRC validation
- `SegmentManager`: segment pool with reference counting and automatic rollover
- `SegmentDescriptor`: tracks open segments with mapped memory and refcounts
- Public API: `Wally` (builder, open, close), `Appender`, `Tailer`, `WallyConfig`, `ChecksumMode`
- Multi-segment integration tests with debug harness
- ADR 0005: documents all segment coordination challenges and solutions

#### Fixed
- **Critical:** Pre-sliced buffer offset confusion - `SegmentDescriptor.data` is already sliced past header
- **Critical:** Appender rollover position calculation - was reusing old segment index, causing position 0 for all new segments
- **Critical:** Tailer rollover - was re-acquiring same segment instead of advancing to next one
- Class name typo: `NowAppender` → `Appender`

#### Changed
- Minimum segment size: 4096 bytes (enforced in `WallyConfig` validation)
- All integration tests updated to use 4096-byte minimum segments

#### Known Limitations
- Tail recovery not implemented: reopening a log overwrites existing data (test disabled)
- No preallocator agent yet (segment N+1 created on-demand, not proactively)
- CRC32C not implemented (ChecksumMode exists but not wired up)

### M0: Gradle build, off-heap memory layer and single-segment log (COMPLETED)

#### Added
- Gradle 9.7.1 multi-module build with JDK 25 toolchain (foojay resolver)
- JPMS module `io.wally.core` exporting only `io.wally.api`
- `Mem`: acquire/release and atomic memory access via FFM `VarHandle`s
- `Align`: alignment utilities for off-heap memory
- `RecordFormat`: 16-byte header (frameLength, type, flags, crc32c, reserved), 8-byte alignment
- `SegmentWriter`: single-writer appender, doubles as reusable `Claim` flyweight
- `SegmentReader`: callback-based reader, no allocations
- API stubs: `RecordHandler`, `Claim`
- Zero-allocation proof: ThreadMXBean check, Epsilon GC test (50M records), JMH gc profiler
- JMH benchmarks vs `ArrayBlockingQueue` (single-thread baseline)
- CI: GitHub Actions workflow for Linux and macOS
- Comprehensive documentation: beginner-friendly home, glossary (65 terms), how-it-works (11 Java concepts), record layout, 4 ADRs
- `RULES.md`: issue → branch → PR workflow, documentation discipline

#### Results
- **Zero allocation**: ≈10⁻⁴ B/op measured by JMH, < 1KB per 1M operations
- **Performance**: 5.95 ns per 32B zero-copy append+read (vs 10.1 ns for ArrayBlockingQueue)
- **Tests**: 13 tests passing, including cross-thread ordering test (1M records)

## Roadmap Changes

### Competitor Benchmarking (moved from M6 to M2)
Based on feasibility analysis, comprehensive competitor benchmarks now part of M2:
- **JCTools MPSC**: ~1 day effort, in-memory baseline
- **Chronicle Queue**: ~2-3 days effort, persistent queue competitor
- Adds ~4 days total to M2 but provides essential context for MPSC results

## Migration Notes

### From M0 to M1
- Minimum segment size is now 4096 bytes (was unlimited in concept)
- Position arithmetic: `position = segmentIndex * segmentSize + offset`
- Tests requiring small segments must write more records (50+ records per 4096-byte segment)

## Links
- GitHub Issues: https://github.com/Kaushikdey647/wally/issues
- Pull Requests: https://github.com/Kaushikdey647/wally/pulls
- Documentation: https://kaushikdey647.github.io/wally/ (once deployed)
