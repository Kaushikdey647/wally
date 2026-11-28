# ADR 0001: Use the FFM API instead of Unsafe

**Status:** Accepted · **Date:** 2026-11-28

## Context
The ring buffer lives off-heap so messages create no garbage, and its cursors need acquire/release access on raw memory. Historically that meant `sun.misc.Unsafe` or direct `ByteBuffer`s.

## Decision
Use the final Foreign Function & Memory API (JEP 454): one `MemorySegment` per ring allocated from an `Arena`, `ValueLayout.JAVA_LONG.varHandle()` for acquire/release cursor access, and plain `ValueLayout` accessors for frame headers and payloads. Bytecode targets Java 22 (`--release 22`); the build uses JDK 25.

## Consequences
- **+** Supported API: `Unsafe` memory access warns from JDK 24 and is slated for removal.
- **+** Bounds and liveness checks that the JIT removes on hot paths.
- **+** Lifetime is explicit: the default `Arena.ofAuto()` frees the ring when unreachable, `Arena.ofShared()` frees it on `close()`.
- **−** Producer and consumer are different threads, so `Arena.ofConfined()` cannot be used: its segments throw `WrongThreadException` off the owner thread.
- **−** Requires Java 22+. Excludes users on 17/21.
- **−** `asSlice()` allocates, so slicing happens once at construction and the hot-path API passes `(segment, offset, length)` instead of per-message slices.
