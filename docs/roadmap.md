# Roadmap: MVP (M0–M4)

**Target:** JDK 25 LTS (final FFM API), Gradle Kotlin DSL, zero runtime dependencies in `wally-core`.

```mermaid
flowchart LR
  M0[M0 memory layer] --> M1[M1 mmap segments] --> M2[M2 MPSC] --> M3[M3 durability + recovery] --> M4[M4 consumer groups + retention]
```

## M0: memory layer + in-memory log ✅

- `Mem` (static aligned `VarHandle`s: acquire/release, volatile, getAndAdd, CAS) and `Align`.
- `RecordFormat`: `int frameLength | short type | short flags | int crc | int reserved` (16 B header, 8 B alignment). `frameLength == 0` means uncommitted. `TYPE_PADDING` fills the end of a segment and replaces aborted claims.
- `SegmentWriter` (single writer; doubles as the `Claim` flyweight) and `SegmentReader` over one off-heap segment.
- Read API: `RecordHandler.onRecord(MemorySegment seg, long offset, int length, long position)`, so there are no slices.
- JMH single-thread append + read vs `ArrayBlockingQueue` (payloads 32 B, 256 B, 4 KB). Cross-thread benchmarks moved to M2, since a single fixed segment can't sustain a long 1P1C run.

**Exit (met):** unit tests pass, including a cross-thread ordering test; JMH `-prof gc` shows `gc.alloc.rate.norm ≈ 10⁻⁴ B/op`; `ThreadMXBean` check shows < 1 KB over 1M records; Epsilon GC test passes (`-Xmx128m`, 50M records).

## M1: mmap'd segments, single writer

- 64 B segment header: magic `WALLY\0`, format version, segment size, base position, header CRC.
- `SegmentFile` maps with `FileChannel.map(READ_WRITE, 0, size, arena)`. Unmap is `arena.close()`, gated by a refcount.
- Position = `segmentIndex * segmentSize + offset` ([ADR 0002](adr/0002-position-addressing.md)).
- Rollover writes `PADDING`, then maps the next segment. A `Preallocator` agent creates and pre-touches segment N+1.
- `Appender.append(...)` and `tryClaim(len)` → reused `Claim` flyweight (`segment()`, `offset()`, `commit()`, `abort()`).
- `ChecksumMode {NONE, CRC32C}`. **Risk:** `java.util.zip.CRC32C` over `asByteBuffer()` may allocate; fall back to a slice-by-8 implementation reading from the `MemorySegment`.

**Exit:** jqwik model test (random append/poll/reopen/segment sizes vs `ArrayList` oracle) passes; rollover tested at exact boundaries; [File format](format.md) written.

## M2: multi-producer claim/commit

- `PaddedCounter` (value isolated by 128 B padding) for the shared tail.
- Tail claim with `getAndAdd` ([ADR 0004](adr/0004-fetch-add-tail.md)). The claimant whose range covers the end of a segment writes padding and triggers the roll; claimants landing past the end retry.
- Commit: payload first, then `setRelease(length)`. Reader: `getAcquire(length)` ([ADR 0003](adr/0003-claim-commit-protocol.md)).
- `producerMode(SINGLE | MULTI)`. Single mode uses a plain store.
- jcstress (commit visibility, no torn header/payload) and Lincheck (MPSC append + tailer).
- JMH NP1C (N = 1, 2, 4, 8) with HdrHistogram and a fixed-rate driver.
- **Competitor benchmarks** (moved from M6): JCTools MPSC, Chronicle Queue
  - Adds ~4 days to M2 but provides essential context for multi-producer results
  - See issue #4 for full benchmarking plan

**Exit:** no forbidden jcstress outcomes; Lincheck passes; still zero allocation; competitor benchmarks complete.

## M3: durability + crash recovery

- `FlushPolicy.none() | periodic(Duration) | sync()`. Periodic = flusher agent `force()`s dirty ranges (group commit).
- `Appender.flushedPosition()` exposes the durable watermark.
- `Recovery` on open: validate headers → scan from checkpoint → stop at first zero length / bad CRC → zero the tail.
- Crash harness: child JVM appends with `sync()` and reports acked positions; parent `kill -9`s it at random times, reopens, and asserts the prefix property.
- Corruption tests: random byte flips and truncation.

**Exit:** 1,000 crash iterations green locally and in Linux CI; [Guarantees](guarantees.md) written.

## M4: consumer groups + retention

- `CursorFile`: small mmap'd file with fixed slots (name hash + 64 B name + padded position).
- `wal.tailer("group")` resumes from the persisted cursor; `commit()`, `seek(position)`. Ad-hoc `wal.tailerFrom(position)` is not persisted.
- Retention agent deletes segments wholly below `min(group cursors, checkpoint)` after the refcount drains.
- `RetentionPolicy.wal()` (truncate on `checkpoint(position)`) vs `RetentionPolicy.queue(maxSegments)` (appender returns `BACK_PRESSURED`).
- `WaitStrategy`: busy-spin / yield / park.
- Examples: event-sourcing journal, inter-thread pipeline.

**Exit:** model test extended with groups, restart and retention; 30-minute soak shows no fd or mapping leaks; Concepts and quickstart docs complete.

## Out of scope for MVP

CLI tools, SBE codec, Micrometer metrics, cross-process IPC, stuck-producer unblocker, LazyFS power-loss tests, Jazzer fuzzing, Maven Central publishing.

## Verification commands

| Command | Checks |
|---|---|
| `./gradlew build` | Unit, jqwik and Lincheck tests |
| `./gradlew :wally-stress:jcstress` | Memory ordering |
| `./gradlew :wally-core:crashTest` | kill -9 recovery harness |
| `./gradlew :wally-bench:jmh -Pjmh.profilers=gc` | `gc.alloc.rate.norm ≈ 0` |
| `./gradlew :wally-core:epsilonTest` | Zero allocation under Epsilon GC |
| `mkdocs serve` | Docs render locally |
