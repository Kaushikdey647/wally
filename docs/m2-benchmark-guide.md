# M2 Benchmark Guide: Multi-Producer Testing

## Overview

M2 benchmarks validate Wally's **multi-producer, single-consumer (MPSC)** performance and correctness claims. Unlike the M0/M1 single-thread baselines, these benchmarks test realistic concurrent workloads.

## Benchmark Suite

### 1. **MPSCThroughputBenchmark** - Sustained Throughput

**What it measures:**
- Producer throughput (ops/sec) under contention
- Consumer throughput (records/sec)
- Scaling from 1→2→4→8 producers
- GC allocation rate under load

**Why it matters:**
- Validates lock-free claim (throughput scales with producers)
- Shows contention overhead vs single-producer baseline
- Compares against JCTools MPSC, Chronicle, ArrayBlockingQueue

**Run:**
```bash
./gradlew :wally-bench:jmh -Pjmh.includes="MPSCThroughputBenchmark" -Pjmh.profilers=gc
```

**Expected results:**
- **Wally:** ~0 B/op allocation, throughput scales near-linearly (once MPSC is implemented)
- **JCTools:** 48-4112 B/op allocation, high throughput (in-memory)
- **Chronicle:** Similar to Wally (both use mmap)
- **ArrayBlockingQueue:** Lock contention limits scaling

---

### 2. **MPSCLatencyBenchmark** - Tail Latency with HdrHistogram

**What it measures:**
- p50, p99, p99.9, p99.99 append latency
- Latency under contention (4 producers vs 1 producer)
- Max latency spikes (GC pauses, segment rollover)

**Why it matters:**
- Standard JMH averaging hides tail latency (coordinated omission)
- GC pauses show up in p99.99 (Wally should be flat, heap queues spike)
- Proves "zero GC eliminates tail latency" claim

**Coordinated Omission Protection:**
- Fixed-rate load generator (target ops/sec)
- Records actual latency including queueing time
- HdrHistogram captures full distribution

**Run:**
```bash
./gradlew :wally-bench:jmh -Pjmh.includes="MPSCLatencyBenchmark"
```

**Expected results:**
- **Wally p99.99:** < 1ms (no GC pauses)
- **JCTools p99.99:** 10-100ms spikes (GC pauses from allocation)
- **Latency distribution:** Wally should be tight (low variance), heap queues have long tail

---

### 3. **MPSCCorrectnessTest** - Concurrent Correctness Validation

**What it validates:**
- No lost records (8 producers × 100K records = 800K total)
- No duplicate records (position uniqueness)
- Position ordering maintained
- Data integrity under contention (producer ID + sequence preserved)

**Why it matters:**
- Lock-free algorithms are notoriously hard to get right
- Races in claim/commit can cause corruption or loss
- Before benchmarking performance, prove correctness

**Run:**
```bash
# Standalone test (not JMH)
cd wally-bench/src/jmh/java/io/wally/bench
javac -cp $CLASSPATH MPSCCorrectnessTest.java
java -cp $CLASSPATH MPSCCorrectnessTest
```

**Or via build system:**
```bash
./gradlew :wally-bench:jmhClasses
java -cp wally-bench/build/classes/java/jmh:wally-core/build/libs/* \
  io.wally.bench.MPSCCorrectnessTest
```

**Expected output:**
```
=== Wally MPSC Correctness Test ===
Producers: 8
Records per producer: 100,000
Total expected: 800,000

Producer 0: 100,000 records written
...
All producers finished
Waiting for consumer to drain queue...

=== Results ===
Expected records: 800,000
Consumed records: 800,000
Unique records:   800,000
✓ All records accounted for
✓ No duplicates
✓ Position ordering maintained

✅ PASS: All correctness checks passed
```

---

## Benchmark Methodology

### Thread Model

**Producers (N threads):**
- Each gets own `Appender` instance (not thread-safe)
- Write continuously until JMH signals stop
- Retry on `NO_SPACE` (segment rolling or backpressure)

**Consumer (1 thread):**
- Single `Tailer` polls in batches (100 records)
- Drains queue to prevent backpressure
- Uses `Blackhole` to prevent JVM optimization

**Why this matters:**
- Matches real MPSC usage (multiple writers, one reader)
- Tests contention on shared tail counter
- Validates segment rollover under load

### Avoiding Common Pitfalls

#### 1. **Coordinated Omission**
**Problem:** JMH averaging hides tail latency when operations block.

**Solution:** `MPSCLatencyBenchmark` uses fixed-rate pacing:
```java
long intervalNanos = TimeUnit.SECONDS.toNanos(1) / targetRate;
long nextSendTime = System.nanoTime();

// Send record
long start = System.nanoTime();
appender.append(...);
histogram.recordValue(System.nanoTime() - start);

// Wait until next scheduled time
nextSendTime += intervalNanos;
Thread.sleep(...);
```

#### 2. **Setup/Teardown Skew**
**Problem:** File creation, JIT compilation in measurements.

**Solution:**
- `@Setup(Level.Trial)` - once per entire benchmark
- Warmup iterations ensure JIT compilation
- Pre-allocate segments, pre-touch pages

#### 3. **Cache Effects**
**Problem:** Single-thread benchmarks measure hot-cache latency.

**Solution:**
- Cross-thread benchmarks have real cache misses
- Use realistic payload sizes (256B, not 8B)
- Consumer runs on separate thread (cache contention)

#### 4. **Allocation Measurement**
**Problem:** `-prof gc` can miss off-heap allocation.

**Solution:**
- Wally uses mmap (off-heap, not tracked by GC)
- Measure: `gc.alloc.rate.norm` (bytes allocated per op)
- Expect: Wally ≈ 0 B/op, JCTools/ABQ = payload + 16 B/op

---

## Running the Full Suite

### Quick smoke test (5 minutes)
```bash
./gradlew :wally-bench:jmh \
  -Pjmh.includes="MPSC.*" \
  -Pjmh.params="payloadSize=256;producers=1,4"
```

### Full benchmark (30 minutes)
```bash
./gradlew :wally-bench:jmh \
  -Pjmh.includes="MPSC.*" \
  -Pjmh.profilers=gc \
  -Pjmh.params="payloadSize=32,256,4096;producers=1,2,4,8"
```

### Latency-focused (with HdrHistogram output)
```bash
./gradlew :wally-bench:jmh \
  -Pjmh.includes="MPSCLatencyBenchmark" \
  -Pjmh.params="producers=4;targetRate=100000"
```

---

## Interpreting Results

### Throughput Scaling

**Good:**
```
1 producer:  10M ops/sec
2 producers: 18M ops/sec  (90% scaling)
4 producers: 32M ops/sec  (80% scaling)
8 producers: 50M ops/sec  (62% scaling)
```

**Bad (lock contention):**
```
1 producer:  10M ops/sec
2 producers: 12M ops/sec  (60% scaling - contention!)
4 producers: 14M ops/sec  (35% scaling - lock serialization)
```

### Allocation Rate

**Goal:** Wally ≈ 0 B/op across all producer counts

**Red flag:** Allocation increases with producers (indicates per-claim allocation)

### Latency Percentiles

**Target (Wally):**
```
p50:    100 ns
p99:    500 ns
p99.9:  5 µs
p99.99: 50 µs   <-- No GC pauses
max:    1 ms    <-- Segment rollover
```

**Heap queues (JCTools/ABQ) will show:**
```
p99.99: 10-100 ms   <-- GC pauses from allocation
max:    500 ms      <-- Full GC pause
```

---

## What These Benchmarks DON'T Test

**Out of scope for M2:**
- ❌ Cross-process IPC (M5)
- ❌ Flush policy comparison (`none` vs `sync`) - M3
- ❌ Crash recovery performance - M3
- ❌ Segment rollover edge cases at exact boundaries - M1 tests cover this
- ❌ Disruptor comparison (requires separate ring buffer benchmark)

**Why:** M2 focuses on **multi-producer coordination**. Durability (M3), cross-process (M5), and other features have separate milestones.

---

## Next Steps

1. **Implement PaddedCounter** - lock-free shared tail
2. **Implement ProducerMode** - SINGLE vs MULTI
3. **Update SegmentWriter** - `getAndAdd` claim for MPSC
4. **Run correctness test first** - validate before benchmarking
5. **Run throughput benchmark** - measure scaling
6. **Run latency benchmark** - measure tail latency
7. **Compare vs Chronicle Queue** - direct competitor
8. **Document results** - update `docs/benchmarks.md`

---

## Reference: Competitor Configurations

### JCTools MPSC
```java
MpscArrayQueue<byte[]> queue = new MpscArrayQueue<>(1024 * 1024);
queue.offer(payload.clone());  // Allocates!
```

### Chronicle Queue
```java
ChronicleQueue chronicle = SingleChronicleQueueBuilder
    .binary(dir).build();
try (DocumentContext dc = appender.writingDocument()) {
    dc.wire().bytes().write(payload);  // Zero-copy
}
```

### Wally (future MPSC mode)
```java
Wally wally = Wally.builder()
    .directory(dir)
    .producerMode(ProducerMode.MULTI)  // Enable MPSC
    .build();
Appender appender = wally.appender();  // Thread-local
appender.append(payload);  // Lock-free getAndAdd
```

---

## Troubleshooting

**"Producer fails with NO_SPACE"**
- Segment size too small → increase to 256 MB
- Consumer not keeping up → check consumer thread is running
- Backpressure working correctly → expected under overload

**"Consumer sees zero records"**
- Check consumer started before producers
- Verify tailer starts at position 0
- Ensure consumer thread keeps running (check `consumerRunning`)

**"Latency benchmark shows no GC but high p99.99"**
- Likely segment rollover (check max latency timing)
- OS page faults (run with pre-touched pages)
- Thread scheduling jitter (pin cores with `taskset`)

**"Correctness test fails with missing records"**
- Appender retry logic bug (NO_SPACE should retry, not drop)
- Consumer exit race (drain after producers finish)
- Segment file corruption (check disk space, permissions)
