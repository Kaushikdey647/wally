# ADR 0001: Use the FFM API instead of Unsafe

**Status:** Accepted · **Date:** 2026-11-05

## Context
Wally needs off-heap memory, memory-mapped files, atomic operations on raw memory, and deterministic unmapping. Historically this meant `sun.misc.Unsafe` + `MappedByteBuffer`.

## Decision
Target JDK 25 LTS and use the final Foreign Function & Memory API: `MemorySegment`, `Arena`, `ValueLayout.varHandle()` for atomics, `FileChannel.map(..., arena)` for mapping, and `Linker` for `madvise`.

## Consequences
- **+** Supported API: `Unsafe` memory access warns from JDK 24 and is slated for removal.
- **+** `arena.close()` unmaps deterministically. No more relying on GC to unmap.
- **+** Bounds and liveness checks, which the JIT removes on hot paths.
- **−** Requires JDK 22+ (we pick 25 LTS). Excludes users stuck on 17/21.
- **−** `asSlice()` allocates, so the API exposes `(segment, offset, length)` triples.
- **−** Closing a shared arena while another thread is using it throws, so segment lifetime needs refcounting.
