# ADR 0003: Two-phase claim/commit with length as the commit marker

**Status:** Accepted · **Date:** 2026-11-05

## Context
Producers must write records in place (zero copy) while readers scan concurrently, without locks. Readers must never observe a partially written record.

## Decision
1. **Claim:** reserve `align8(16 + len)` bytes at the tail. The `length` field is still 0.
2. **Write:** fill type, flags, CRC and payload with plain stores.
3. **Commit:** store `length` with `setRelease`.

Readers load `length` with `getAcquire`. `0` means "not committed", so stop. A positive value guarantees the rest of the record is visible (release/acquire happens-before).

Segments are zero-filled on creation, so unwritten space always reads as `length == 0`.

## Consequences
- **+** One release store per record; no locks, no CAS on the read path.
- **+** Torn writes after a crash show up as `length == 0` or a CRC mismatch, which makes recovery simple.
- **−** A producer that dies between claim and commit leaves a hole that blocks readers. Mitigation (post-MVP): an unblocker that pads over holes after a timeout.
- **−** Max payload is bounded by `int` and by segment size minus headers.
