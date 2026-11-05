# Benchmarks

## Methodology

- **Harness**: JMH for throughput; a fixed-rate driver + HdrHistogram for latency (from M2), to avoid coordinated omission.
- **Scenarios**: 1P1C and NP1C (N = 1, 2, 4, 8); payloads 32 B, 256 B, 4 KB; flush policies `none`, `periodic(1ms/5ms)`, `sync`.
- **Baselines**: `ArrayBlockingQueue`, JCTools MPSC, LMAX Disruptor, Chronicle Queue.
- **Allocation**: JMH `-prof gc` (`gc.alloc.rate.norm`) and an Epsilon GC run.
- **Environment**: record CPU, kernel, JDK, JVM flags, core pinning. Publish raw data and the script that produced it.

## Reproduce

```bash
./gradlew :wally-bench:jmh -Pjmh.profilers=gc
```

## M0: single-thread append + read

`SegmentLogBenchmark`: one thread appends a record and reads it back. Measures the raw cost of framing, the release/acquire publish and the copy, plus allocation.

**Environment:** Apple M4 Pro, macOS 26.4.1, Temurin 25.0.5, JMH 1.37, 1 fork, 3×1 s warmup, 5×1 s measurement, laptop (no core pinning).

| Benchmark | 32 B | 256 B | 4 KB | Allocation |
|---|---|---|---|---|
| `wallyClaimCommitRead` (zero copy) | 5.95 ns | 8.52 ns | 42.3 ns | ≈ 0 B/op |
| `wallyAppendRead` (copy in) | 7.79 ns | 12.5 ns | 103 ns | ≈ 0 B/op |
| `arrayBlockingQueueOfferPoll` (new `byte[]` per message) | 10.1 ns | 11.6 ns | 91.2 ns | payload + 16 B/op |

!!! warning "How to read these numbers"
    - Single-threaded, so there is no lock contention. `ArrayBlockingQueue`'s real cost appears under contention and as GC pressure over time. Those effects show up in the cross-thread benchmarks (M2).
    - The 1 MB benchmark buffer is re-zeroed when full. At 4 KB that happens every ~255 records and inflates the Wally numbers. In the real log, new segments are zeroed ahead of time by the preallocator (M1).
    - Laptop numbers. Reference results will come from dedicated Linux hardware.
