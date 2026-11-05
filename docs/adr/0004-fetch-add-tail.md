# ADR 0004: Claim tail space with fetch-add, not a CAS loop

**Status:** Accepted · **Date:** 2026-11-05

## Context
In multi-producer mode, producers contend on the tail counter. A CAS loop retries under contention; fetch-add (`LOCK XADD` on x86, `LDADD` on ARMv8.1+) always succeeds in one instruction.

## Decision
Producers claim with `getAndAdd(tail, recordLength)`. If the returned range crosses the segment end:

- The claimant whose range covers the segment end writes a `PADDING` record from its start to the end, then triggers the roll.
- Claimants whose range starts at or past the end don't write anything. They wait for the next segment to become active and retry there.

Single-producer mode skips the atomic and uses a plain store plus release.

## Consequences
- **+** Wait-free claim in the common case; throughput scales better than a CAS loop under contention.
- **−** The tail can overshoot the segment end, so the logical tail must be re-based on roll. This needs careful jcstress coverage.
- **−** Overshooting claimants waste a small amount of address space (no disk space).
