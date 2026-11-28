# Benchmarks

All numbers are 1P1C: one producer thread and one consumer thread running concurrently. Raw outputs are checked in under [`wally-bench/results/`](https://github.com/kaushikdey647/wally/tree/main/wally-bench/results).

## Environment

| | |
|---|---|
| CPU | Apple M4 Pro, 14 cores (10 performance + 4 efficiency) |
| Memory | 24 GB |
| OS | macOS 26.4.1, no core pinning or isolation (macOS has no thread affinity API) |
| JDK | OpenJDK 25.0.5+8-LTS (Temurin), default flags; latency runs add `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| JMH | 1.37 |

## Throughput

`SpscThroughputBenchmark`: JMH `@Group` with one `offer` thread and one `poll` thread, both non-blocking, one message per call. Score is messages handed from producer to consumer per second (`polled` counter). 3 forks × 5 × 1 s measurement after 5 × 1 s warmup.

| Queue | Payload | Throughput (msgs/s) | ns per message | Allocation (B/op) |
|---|---|---|---|---|
| Wally `RingBuffer` (1 MB ring) | 32-byte frame payload, written into the ring | 73.5M ± 3.2M | 13.6 | ≈ 0 |
| JCTools `SpscArrayQueue` (32K slots) | preallocated object reference | 399.4M ± 17.5M | 2.5 | ≈ 0 |
| `ArrayBlockingQueue` (32K slots) | preallocated object reference | 19.7M ± 8.0M | 50.7 | 2.8 ± 3.1 |

Wally is about 3.7x faster than `ArrayBlockingQueue` and about 5.4x slower than JCTools in this per-message test. The comparison is not like for like: JCTools moves a 4-byte reference and signals through the slot itself (null/non-null), so producer and consumer never write a shared cursor. Wally copies a payload and, with `read(handler, 1)`, publishes the head cursor with a release store on every message, which moves that cache line to the consumer core and back each time. Larger read batches amortize the head store.

## Latency

`SpscLatency`: the producer offers 1M msgs/s at a fixed schedule and stamps each message with its *intended* send time, so stalls and full-queue waits count as latency (no coordinated omission). The consumer busy-polls and records `System.nanoTime() - stamp` into an HdrHistogram. 5 s warmup, then 20 s (20M messages) measured.

| Queue | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| Wally | 83 ns | 458 ns | 24.2 µs | 737 µs | 1.78 ms |
| JCTools `SpscArrayQueue` | 84 ns | 334 ns | 13.1 µs | 572 µs | 1.66 ms |

Read these with care:

- `System.nanoTime()` on Apple Silicon ticks at 24 MHz, about 42 ns per tick, so the p50 values carry ±42 ns quantization.
- Everything above p99 is dominated by the OS scheduler on an unpinned macOS laptop. Neither number above p99 should be cited as a property of the queue.

## Allocation and correctness checks

- `ZeroAllocationTest` runs 50M write/read cycles under Epsilon GC with a 128 MB heap as part of `./gradlew check`; any per-op allocation fails it with `OutOfMemoryError`.
- jcstress (`./gradlew :wally-stress:jcstress -Pjcstress.args="-m default"`): 68/68 configurations passed for the plain and wrap-sentinel publication tests.

## Reproduce

```bash
./gradlew :wally-bench:jmh -Pjmh.profilers=gc   # wally-bench/results/jmh-throughput.json
./gradlew :wally-bench:latency                  # wally-bench/results/latency-*.hgrm
```
