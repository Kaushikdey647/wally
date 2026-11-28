# ADR 0004: Write the wrap sentinel only after the gap is proven free

**Status:** Accepted · **Date:** 2026-11-28

## Context
Frames never straddle the end of the ring. When a frame does not fit in the remaining bytes of the current lap, the writer marks the rest of the lap as unused with a sentinel (length 0) and places the frame at index 0 of the next lap.

An earlier implementation wrote the sentinel first and checked free space afterwards. When the ring was full, the sentinel landed on the length header of an unread frame; the reader then took that frame for a wrap and silently skipped the rest of the lap. Uniform message sizes hid the bug because the overwritten header was already a sentinel.

## Decision
1. If the frame does not fit before the end of the lap, compute `nextLap = (tail & ~mask) + capacity` and check that `[tail, nextLap)` is free (`nextLap - head <= capacity`). If not, return `false` without writing anything.
2. Write the sentinel, set `tail = nextLap`, and publish it with a release store immediately.
3. Check space for the frame at index 0. If there is none, return `false`; the published skip stays valid.
4. Write the length header only after the frame's space check succeeds.

## Consequences
- **+** The writer never writes to memory the reader has not consumed.
- **+** Publishing the skip separately fixes a liveness bug: a frame larger than both the remaining gap and the current index could otherwise never be placed, because it needed the reader to pass a tail that had not moved.
- **−** A wrap costs one extra release store.
- Verified by `RingBufferWrapTest` (deterministic reproduction that fails on the old ordering, plus 2M random-size messages across two threads) and the jcstress `SpscWrapPublishTest`.
