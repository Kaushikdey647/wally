# Benchmarks

!!! info "Pending"
    First results land with M0 (1P1C) and M2 (NP1C).

## Methodology

- **Harness**: JMH for throughput; a fixed-rate driver + HdrHistogram for latency, to avoid coordinated omission.
- **Scenarios**: 1P1C and NP1C (N = 1, 2, 4, 8); payloads 32 B, 256 B, 4 KB; flush policies `none`, `periodic(1ms/5ms)`, `sync`.
- **Baselines**: `ArrayBlockingQueue`, JCTools MPSC, LMAX Disruptor, Chronicle Queue.
- **Allocation**: JMH `-prof gc` (`gc.alloc.rate.norm`) and an Epsilon GC run.
- **Environment**: record CPU, kernel, JDK, JVM flags, core pinning. Publish raw data and the script that produced it.

## Reproduce

```bash
./gradlew :wally-bench:jmh -Pjmh.profilers=gc
```
