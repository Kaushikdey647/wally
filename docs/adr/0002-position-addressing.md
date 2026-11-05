# ADR 0002: Address records by logical byte position

**Status:** Accepted · **Date:** 2026-11-05

## Context
Consumers and checkpoints need a stable, seekable identifier for a record. Options: a sequence number (needs an index file) or a byte position.

## Decision
Identify records by a 64-bit logical position = `segmentIndex * segmentSize + offsetInSegment`. `segmentSize` is a power of two and fixed per log.

## Consequences
- **+** O(1) seek: `segmentIndex = pos >>> log2(segmentSize)`, `offset = pos & (segmentSize - 1)`. No index file.
- **+** The tail counter, cursors and checkpoints are all plain `long`s, so they are cheap to CAS and persist.
- **−** Positions are not dense: users can't compute "the Nth record". A sparse sequence index can come later if needed.
- **−** Segment size can't change for an existing log.
