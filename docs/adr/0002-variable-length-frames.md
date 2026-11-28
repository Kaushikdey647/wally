# ADR 0002: Variable-length frames addressed by virtual byte cursors

**Status:** Accepted · **Date:** 2026-11-28

## Context
Typical payloads (orders, quotes, market-data ticks) vary in size. Fixed-size slots either waste space or cap the message size, and slot-of-references queues force a heap object per message.

## Decision
- Messages are stored inline as frames: an 8-byte header (4-byte length + 4 bytes padding) followed by the payload, padded so every frame is a multiple of 8 bytes. Payloads are therefore always 8-byte aligned for `ValueLayout` access.
- `head` and `tail` are monotonically increasing 64-bit byte positions ("virtual cursors"). The ring index is `cursor & (capacity - 1)`, so capacity must be a power of two.
- A length of 0 is reserved as the wrap sentinel (ADR 0004), so zero-length messages are rejected.

## Consequences
- **+** No per-message objects; the producer writes straight into the ring (zero copy).
- **+** Empty (`head == tail`) and full (`tail - head == capacity`) are unambiguous without a spare slot; 64-bit cursors do not overflow in practice.
- **−** `Reader.sequence()` and the handler's `sequence` argument are byte positions, not message counts, and `available()` is in bytes.
- **−** 8 bytes of header plus up to 7 bytes of padding per message, and a power-of-two capacity.
