# ADR 0003: Claim/commit publication with acquire/release and cached cursors

**Status:** Accepted · **Date:** 2026-11-28

## Context
With exactly one producer and one consumer, each cursor has a single writer, so no CAS or locks are needed. What remains is making a frame visible only once it is complete, and keeping cross-core cursor traffic low.

## Decision
- **Claim, write, commit.** `tryClaim(length)` reserves a frame and writes its length header; the caller writes the payload in place; `commit()` publishes it with a single release store of `tail`. The consumer acquire-loads `tail` and only reads frames below it.
- **Owner keeps a local copy.** The writer holds its own `tail` in a field and never re-reads it from shared memory; the reader does the same for `head`.
- **Cache the other side's cursor.** The writer acquire-loads `head` only when its cached value says the ring is full; the reader acquire-loads `tail` only when its cached value is exhausted.
- **Non-blocking backpressure.** `tryClaim` returns `false` when the ring is full and `read` returns 0 when it is empty. Waiting strategy (spin, yield, drop) is the caller's choice.
- **Consumption on callback.** A frame counts as consumed once its handler is invoked, even if the handler throws; the head is published once per `read` call.

## Consequences
- **+** One release store per message on the producer side, no fences beyond acquire/release, no CAS.
- **+** Every operation completes in a bounded number of steps.
- **−** Single-threaded use per side is a contract the API cannot enforce.
- **−** `read(handler, 1)` publishes `head` on every message, which bounces that cache line between cores. This is the main reason JCTools `SpscArrayQueue` is faster in the per-message throughput benchmark; larger read batches amortize it.
