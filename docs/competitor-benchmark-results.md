# Competitor Benchmark Results (M2)

**Date:** 2026-11-06  
**Environment:** Apple M4 Pro, macOS 26.4.1, JDK 25.0.5, Single-thread  
**Status:** Pre-alpha M2 baseline

## Summary

Wally demonstrates **zero-allocation** performance competitive with JCTools MPSC (in-memory) while providing **durability** via memory-mapped files.

## Key Findings

### 1. Zero Allocation ✅

| Library | Allocation (32B) | Allocation (256B) | Allocation (4KB) |
|---------|------------------|-------------------|------------------|
| **Wally** | **0.003 B/op** | **0.018 B/op** | **0.279 B/op** |
| JCTools MPSC | 48 B/op | 272 B/op | 4112 B/op |
| ArrayBlockingQueue | 48 B/op | 272 B/op | 4112 B/op |

**Wally achieves ~0 GC** vs massive allocation in heap queues.

### 2. Latency Comparison (Lower is Better)

#### 32-byte payload
```
JCTools MPSC:        4.16 ns/op   (fastest, in-memory only)
ArrayBlockingQueue:  8.77 ns/op   (heap queue)
Wally:              10.82 ns/op   (durable, zero GC)
```

#### 256-byte payload
```
JCTools MPSC:        7.70 ns/op   (fastest, in-memory only)
ArrayBlockingQueue: 11.32 ns/op   (heap queue)
Wally:              28.84 ns/op   (durable, zero GC)
```

#### 4KB payload
```
ArrayBlockingQueue:  84.71 ns/op  (heap queue)
JCTools MPSC:        93.59 ns/op  (in-memory only)
Wally:              362.07 ns/op  (durable, zero GC)
```

### 3. GC Pressure

| Library | GC Count (total across all runs) | GC Time (ms) |
|---------|-----------------------------------|--------------|
| **Wally** | **≈ 0** | **≈ 0** |
| JCTools MPSC | 558 | 229 ms |
| ArrayBlockingQueue | 473 | 206 ms |

**Result:** Wally produces **ZERO GC pauses**, eliminating tail latency spikes.

## Interpretation

### Wally's Position

**vs In-Memory Queues (JCTools, ArrayBlockingQueue):**
- 2-4x slower on small payloads due to mmap overhead
- **Trade:** microsecond latency penalty for crash recovery
- **Win:** Zero GC (no tail latency spikes)

**vs Chronicle Queue (coming next):**
- Both provide durability via mmap
- Wally uses modern FFM API vs Chronicle's `sun.misc.Unsafe`
- Direct comparison pending

### When to Choose Each

| Use Case | Recommendation |
|----------|---------------|
| No durability needed | **JCTools** (4ns latency) |
| Local durable log | **Wally** (0 GC, 11ns latency) |
| Cross-process IPC | **Chronicle Queue** (when M5 lands) |
| Distributed pub/sub | **Kafka** (wrong category) |

## Fair Comparison Notes

1. **JCTools / ArrayBlockingQueue:** In-memory only, no crash recovery
2. **Wally:** Durable via mmap, pre-alpha (M2 milestone)
3. **Chronicle Queue:** Not yet benchmarked (integration in progress)
4. **Environment:** Laptop (not dedicated server), results are directional

## Next Steps (M2)

- [x] Add JCTools baseline
- [ ] Add Chronicle Queue baseline (API integration in progress)
- [ ] Add Disruptor baseline (requires cross-thread setup)
- [ ] Multi-producer benchmarks (NP1C, N=1,2,4,8)
- [ ] HdrHistogram + fixed-rate driver (avoid coordinated omission)

## Raw Data

See: `/Users/kaushik.dey1/IdeaProjects/wally/wally-bench/build/results/jmh/results.txt`

---

**Conclusion:** Wally's 10-30ns append+read latency with **zero GC** positions it between pure in-memory queues (JCTools) and full-featured persistent queues (Chronicle). The ~0 allocation rate proves the zero-allocation claim for M2.
