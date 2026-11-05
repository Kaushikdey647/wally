# Blueprint

## 0. Core idea

Build one thing: an **append-only log addressed by position**. Both modes sit on top of it:

- **WAL** = log + durability policy + crash recovery + truncation at checkpoints.
- **Event queue** = log + named consumer cursors + retention (delete a segment once every cursor has passed it).

Prior art worth studying: Agrona and Aeron (log buffers), Chronicle Queue, the LMAX Disruptor.

**Foundational decision:** use the Java 22+ FFM API (`MemorySegment`, `Arena`, `VarHandle`) instead of `sun.misc.Unsafe`. See [ADR 0001](../adr/0001-ffm-over-unsafe.md).

- JDK 24+ warns on `Unsafe` memory access, and it is headed for removal.
- `FileChannel.map(mode, off, size, arena)` gives deterministic unmapping with `arena.close()`. That fixes the old `MappedByteBuffer` unmap problem.
- `Linker` allows calling `madvise`/`mlock` without JNI.

## 1. Problems to solve

| # | Problem | Approach |
|---|---|---|
| 1 | **Record framing** | 8-byte aligned header: `int length`, `short type`, `short flags`, `int crc32c`, then the payload. Little-endian, fixed. A `PADDING` record type fills the rest of a segment. |
| 2 | **Atomic publish** | Two-phase claim/commit. A producer reserves space, writes the payload, then writes `length` last with `setRelease`. Readers `getAcquire` the length; `0` means "not committed yet, stop here." |
| 3 | **Multi-producer contention** | `getAndAdd` on the tail counter (fetch-add, not a CAS loop, so there are no retries). 128-byte padding around hot counters to avoid false sharing. Single-writer first, then MPSC. |
| 4 | **Segment rollover** | Fixed-size segment files (e.g. 1 GiB). The producer whose claim crosses the boundary writes the padding and triggers the roll; other producers retry. Logical position is `segmentIdx * segSize + offset`, so seeking is O(1) with no index file. |
| 5 | **Page-fault stalls** | A background thread pre-allocates and pre-touches the next segment, plus `madvise(SEQUENTIAL/WILLNEED)`. Document THP and `vm.dirty_*` tuning. |
| 6 | **Durability** | mmap writes only reach the page cache. Flush policies `NONE`, `PERIODIC` (group commit every N ms) and `SYNC`, via `MemorySegment.force()`. macOS needs `F_FULLFSYNC` for real durability. Document what "acknowledged" means for each policy. |
| 7 | **Crash recovery** | On open, scan forward from the last checkpoint and validate each CRC. Truncate at the first torn or zero frame. File header: magic + format version + segment size. |
| 8 | **Stuck producer (hole)** | A producer thread that dies between claim and commit blocks readers forever. Fix (post-MVP): after a timeout, an "unblocker" writes padding over the hole (as Aeron does). |
| 9 | **Segment lifetime vs. readers** | Closing a shared `Arena` while a reader is using it throws. Reference counting or epoch-based reclamation before unmapping or deleting. |
| 10 | **Zero allocation** | `MemorySegment.asSlice()` allocates. Expose `(segment, offset, length)` instead of slices. Reused flyweights, non-capturing or pre-allocated handlers, return codes instead of exceptions. No iterators, boxing or varargs on the hot path. |
| 11 | **Backpressure / waiting** | `WaitStrategy`: busy-spin, then `Thread.onSpinWait`, then yield, then park. Queue mode returns `BACK_PRESSURED` when retention would be exceeded. |
| 12 | **Cross-process (post-MVP)** | Shared mmap'd metadata file holding the tail and cursors. Aligned CAS on mapped memory works across processes. |

!!! note "State the claims precisely"
    - "Zero-allocation" means steady state on the hot path.
    - "Lock-free" means the append and read paths; fsync is not lock-free.

## 2. Code organization

Gradle multi-module build. Core has no runtime dependencies. JPMS `module-info` exports only `io.wally.api`.

```
wally/
├─ wally-core/        # io.wally.api              public: Wally, Appender, Claim, Tailer, configs
│                     # io.wally.internal.memory  FFM, VarHandles, alignment, native madvise
│                     # io.wally.internal.log     framing, appender, reader
│                     # io.wally.internal.segment mapping, rolling, preallocation, refcount
│                     # io.wally.internal.recovery scan, CRC verify, truncate
│                     # io.wally.internal.concurrent padded counters, wait strategies, agents
│                     # io.wally.internal.queue   consumer groups, cursors, retention
├─ wally-bench/       # JMH
├─ wally-stress/      # jcstress + Lincheck
├─ wally-examples/    # event-sourcing journal, inter-thread pipeline
├─ docs/              # this site + ADRs
│  (post-MVP)
├─ wally-codec-sbe/   # optional SBE integration
├─ wally-metrics/     # optional Micrometer binding
└─ wally-tools/       # CLI: dump / verify / recover / stats
```

### API shape (sketch)

```java
try (Wally wal = Wally.builder().dir(p).segmentSize(1 << 30)
        .flush(FlushPolicy.periodic(Duration.ofMillis(5))).open()) {
    Appender a = wal.appender();                  // one per thread, reused
    long pos = a.append(src, off, len);           // copy path
    Claim c = a.tryClaim(64);                     // zero-copy path, reused flyweight
    if (c != null) { c.segment().set(JAVA_LONG, c.offset(), 42L); c.commit(); }

    Tailer t = wal.tailer("billing");             // named, persisted cursor
    t.poll((seg, off, len, position) -> { ... }, 256);
}
```

Methods return `long` positions, with negative constants for `BACK_PRESSURED`, `ROLLING` and `CLOSED` (the Aeron style).

## 3. Patterns

- **Flyweight**: `Claim` and record views over raw memory, with no per-message objects.
- **Two-phase claim/commit**: the publish protocol.
- **Single Writer Principle**: the default mode. MPSC is opt-in.
- **Sequence/cursor (Disruptor)**: consumers are just positions.
- **Strategy**: `WaitStrategy`, `FlushPolicy`, `RetentionPolicy`.
- **Builder + immutable config**.
- **Agent / duty cycle (Agrona)**: background flusher, preallocator and retention cleaner on one optional thread.
- **ADRs**: every non-obvious decision is recorded in `docs/adr/`.

## 4. Utility

- **Target use cases**, each shipped as an example: event-sourcing journal, local durable outbox in front of Kafka, inter-thread pipeline, replication log for a KV store.
- **Zero dependencies** in core, published to Maven Central.
- **Explicit guarantees table**: what survives a process crash vs. a power loss under each `FlushPolicy`. See [Guarantees](../guarantees.md).
- **CLI tooling** to inspect and repair files.
- **Stability**: file-format versioning, API compatibility checks with japicmp, semver.
- **Honest benchmarks** against `ArrayBlockingQueue`, JCTools MPSC, Disruptor and Chronicle Queue, with reproducible scripts.

## 5. Testing

### Functional

| Layer | Tool | What it covers |
|---|---|---|
| Unit | JUnit 5 | Framing, alignment, padding, rollover at exact boundaries, CRC |
| Model-based | jqwik | Random append/poll/roll/reopen sequences checked against an `ArrayList` oracle |
| Memory ordering | jcstress | Claim → commit → read visibility; no torn length/payload observed |
| Linearizability | Lincheck | MPSC append + concurrent tailers |
| Crash | Child JVM + `kill -9` mid-write, then reopen | Prefix property: every durable-acknowledged record is present and no corrupt record is surfaced |
| Power loss | LazyFS (FUSE that drops unsynced writes), Linux CI | Validates `FlushPolicy` guarantees for real |
| Corruption | Jazzer fuzzing of the recovery scanner; random byte flips and truncation | Recovery never crashes or returns garbage |
| Soak | Multi-hour run with rollover + retention | Leaks, file-descriptor growth, mapping leaks |

### Non-functional

- **Throughput and latency**: JMH across 1P1C and NP1C, payloads from 32 B to 4 KB. HdrHistogram with a fixed-rate load generator so coordinated omission doesn't hide tail latency. Report p50/p99/p99.99/max.
- **Zero-allocation proof**:
    - JMH `-prof gc`: assert `gc.alloc.rate.norm == 0`.
    - Steady-state run under `-XX:+UseEpsilonGC` with a small heap. Any allocation eventually exhausts the heap and fails the run.
    - JFR allocation events as a cross-check.
- **Profiling**: async-profiler and JMH `-prof perfasm`.
- **Durability cost**: throughput vs. flush interval curve.
- **Environment**: real numbers on dedicated Linux with pinned cores and `isolcpus`. CI perf checks only as coarse regression gates.
- **Matrix**: JDK 25 LTS × Linux x86/ARM × macOS.

## 6. Documentation

- **Javadoc** for `io.wally.api` only, hosted on javadoc.io once published to Central.
- **This site**: MkDocs Material in `/docs`, deployed to GitHub Pages by GitHub Actions, versioned with `mike`.
- Sections: Getting Started, Concepts, File format, Guarantees, Tuning, Benchmarks, ADRs, CLI reference.
