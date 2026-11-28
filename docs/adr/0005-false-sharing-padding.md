# ADR 0005: Pad both off-heap cursors and on-heap writer/reader state

**Status:** Accepted · **Date:** 2026-11-28

## Context
The producer writes `tail` and its private claim state on every message; the consumer writes `head` and its cached tail. If any of these share a cache line, each write invalidates the other core's copy.

Padding only the off-heap cursors is not enough. The writer and reader objects are allocated back to back (JOL: 40 and 32 bytes), so their hot fields can sit in the same heap cache line.

## Decision
- **Off-heap:** `head` and `tail` each sit in their own 128-byte block (two 64-byte lines, covering adjacent-line prefetch), followed by the data region.
- **On-heap:** inheritance padding. A superclass with 16 `long` fields (128 bytes), a middle class with the hot fields, and a subclass with 16 more `long` fields. HotSpot lays out superclass fields first, so field reordering cannot move hot fields into the padding.
- **No `int` hot fields.** HotSpot fills the 4-byte gap after the compressed object header with a small field from any class in the hierarchy, which put `int claimedLength` in front of the padding. The claimed length is read from the frame header instead.
- `RingBufferLayoutTest` asserts with JOL that at least 128 bytes precede and follow the hot fields of both objects.

## Consequences
- **+** Producer and consumer state live in separate cache lines regardless of allocation order.
- **+** The layout is checked in CI, so a JVM or field change that breaks it fails the build.
- **−** About 300 bytes per writer/reader object.
- **−** `@Contended` would be shorter but needs `-XX:-RestrictContended` for non-JDK code, which a library cannot require.
