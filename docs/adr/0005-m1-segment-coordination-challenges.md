# ADR 0005: M1 Segment Coordination Challenges

**Status:** Draft · **Date:** 2026-11-05

## Context

M1 introduces memory-mapped segment files and the public `Wally`/`Appender`/`Tailer` API. The log now spans multiple files, and producers/consumers must coordinate segment boundaries correctly. Development revealed subtle bugs in how offsets and positions interact with pre-sliced segment buffers.

## Problems encountered

### 1. Pre-sliced buffer confusion

**The setup:**
- `SegmentFile.mapped()` returns the full mmap'd file, including the 64 B header
- `SegmentDescriptor.data` is `file.mapped().asSlice(SegmentFile.HEADER_SIZE)` — pre-sliced past the header
- `SegmentWriter` and `SegmentReader` work on a `MemorySegment` and expect offsets relative to that buffer

**The bug:**
Initial implementation of `Appender` and `Tailer` passed `SegmentFile.HEADER_SIZE` as the start offset when wrapping on a new segment:

```java
// WRONG:
writer.wrap(current.data, SegmentFile.HEADER_SIZE, current.file.basePosition());
```

But `current.data` is **already sliced past the header**, so adding `HEADER_SIZE` again meant starting 128 bytes into the file (double-counting the header). Records were written/read at the wrong offsets.

**The fix:**
Since `data` is pre-sliced, the offset within it should be 0 for a fresh segment:

```java
// CORRECT:
writer.wrap(current.data, 0, current.file.basePosition());
```

For a tailer resuming mid-segment, the offset is just the remainder after subtracting the segment's base position:

```java
long offsetInSegment = startPosition - current.file.basePosition();
reader.wrap(current.data, offsetInSegment, current.file.basePosition());
```

**Lesson:** When a buffer is pre-sliced, all offsets passed to methods working on that buffer must be relative to the slice, not the original file. Document this invariant clearly.

---

### 2. Class name corruption in IDE

During development, `Appender.java` had its class declaration changed to `public final class NowAppender` (likely an accidental edit or autocomplete), while the constructor remained `Appender(...)`. This caused a compilation error: "invalid method declaration; return type required".

**The fix:**
Restored the class name to `Appender`.

**Lesson:** Even minor typos in class names can produce confusing compiler errors. If a constructor suddenly "needs a return type," check the class name.

---

### 3. Tailer stops reading prematurely across segments

**The symptom:**
`ActualMultiSegmentTest` writes 200 records across 4 segments (50 records per 4096 B segment). The tailer reads some records but stops early, even though all records are committed and visible.

**Hypothesis:**
The issue is in `Tailer.poll()` rollover logic:

```java
public int poll(RecordHandler handler, int recordLimit) {
    int count = reader.read(handler, recordLimit);
    if (reader.isAtEnd() && reader.position() > current.file.basePosition()) {
        rollToNextSegment();
    }
    return count;
}
```

`reader.isAtEnd()` returns true when `offset >= limit`, meaning the reader has consumed all bytes in the current segment, **including the padding frame** at the end. But if the reader stops at an uncommitted frame (a normal stopping point when reading up to the tail), `isAtEnd()` is false and the tailer never rolls to the next segment.

When the tailer is called again, it re-reads from the same position, sees `frameLength == 0` again, and returns 0. The test loop sees `read == 0` and breaks, even though there are committed records in the next segment.

**Root cause (unconfirmed):**
The condition `reader.isAtEnd() && reader.position() > current.file.basePosition()` doesn't distinguish between:
1. "I'm at the padding frame at the end of this segment, roll to the next one"
2. "I'm waiting for the writer to commit the next record in this segment, don't roll"

If a segment ends exactly at a padding frame and the next segment exists with committed records, the tailer should roll. But if the reader stopped mid-segment at an uncommitted frame (waiting for the writer), it should not roll.

**Next steps to fix:**
1. Add logging to `Tailer.poll` and `SegmentReader.read` to trace `offset`, `limit`, `position`, `frameLength` on every call
2. Identify the exact scenario: does the reader stop at a padding frame, or mid-segment at `frameLength == 0`?
3. Revise the rollover condition to correctly detect "end of segment with more segments available"

Possible fix:
- If `reader.read()` returns 0 and `reader.isAtEnd()` is true, roll
- OR: have `SegmentReader` distinguish between "stopped at segment end" vs "stopped at uncommitted frame" with a return code or flag

---

### 4. Segment size validation: minimum 4096 bytes

`WallyConfig.Builder.build()` validates:

```java
if (segmentSize < 4096 || !Align.isPowerOfTwo(segmentSize))
```

This prevents tests from using tiny segments like 256 bytes for easy multi-segment testing.

**Rationale for the limit:**
- 4096 is a common page size
- A segment smaller than one page makes no sense for mmap'd files
- The 64 B header leaves at least 4032 usable bytes, enough for meaningful records

**Impact on testing:**
Tests wanting to force rollover with small record counts must use 4096 B segments and write enough records (50+) to span multiple files. This is acceptable.

**Alternative considered:**
Allow smaller segments in test mode (e.g., via a `@VisibleForTesting` relaxed config). Rejected: the validation is correct, and tests can just write more records.

---

## Decisions

1. **`SegmentDescriptor.data` is the contract boundary.** It is always a slice starting at the first usable byte (past the header). All code using it works with offsets relative to that slice.

2. **Document the offset convention.** Add javadoc to `SegmentWriter.wrap` and `SegmentReader.wrap` stating: "startOffset is relative to the buffer passed, which may itself be a slice."

3. **Tailer rollover needs a state machine or clearer signal from SegmentReader.** The current `isAtEnd()` check is ambiguous. Consider:
   - Return an enum from `read()`: `STOPPED_UNCOMMITTED`, `STOPPED_AT_END`, `LIMIT_REACHED`
   - OR: a boolean `SegmentReader.stoppedAtSegmentEnd()`

4. **Integration tests are essential.** The single-segment M0 tests passed, but multi-segment bugs only appeared with real segment rolling. Always test the integration, not just the units.

---

## Current status (2026-11-05)

- ✅ `SegmentFile` with header, mmap, validation
- ✅ `SegmentManager` with refcounting and rolling
- ✅ Public API: `Wally`, `Appender`, `Tailer`, `WallyConfig`
- ✅ Single-segment reads/writes work correctly
- ❌ **Multi-segment tailer stops prematurely** — blocking M1 completion
- ⏸️ Preallocator, CRC32C implementation, jqwik property test, docs updates — deferred until core bug is fixed

## Next actions

1. Add detailed logging to `Tailer` and `SegmentReader`
2. Run `ActualMultiSegmentTest`, capture exact trace of positions/offsets
3. Fix the rollover condition based on findings
4. Verify fix with 200-record test and the original `WallyIntegrationTest`
5. Complete remaining M1 tasks (preallocator, checksums, property test, docs)
6. Update this ADR's status to Accepted once the fix is verified

---

## Update 2026-11-05 (later)

### Additional fix applied: Appender rollover position calculation

**Bug identified from test output:**
```
Writing 200 records...
  Record 0 at position 0
  Record 50 at position 0    <- Every new segment showed position 0!
  Record 100 at position 0
  Record 150 at position 0
```

**Root cause:**
In `Appender.roll()`:
```java
writer.padToEnd();                      // fills rest of current segment
long nextPosition = writer.tailPosition();  // WRONG: still in old segment!
current = manager.acquire(nextPosition);    // rounds down to same segment index
```

After `padToEnd()`, `tailPosition()` returns a position still inside the old segment (e.g., 4000 for a 4096-byte segment). When `SegmentManager.acquire()` divides this by `segmentSize`, it gets index 0 again instead of index 1.

**Fix:**
```java
long nextSegmentIndex = manager.rollToNext();
long nextPosition = nextSegmentIndex * manager.segmentSize();
current = manager.acquire(nextPosition);
```

This ensures we always acquire the next segment by its index, not by trying to derive the index from the old segment's tail position.

**Result:** Positions now increment correctly across segments (verified in test output).

---

### Remaining issue: Tailer still stops at segment boundaries

Even with correct positions, `ActualMultiSegmentTest` reads only 50 out of 200 records - it stops at the end of segment 0.

The problem is in `Tailer.poll()` rollover logic. More debugging needed to determine exact condition for "roll to next segment vs. wait for more records."

### Reopen issue discovered

`reopensLogAndContinuesReading()` fails because when `Wally` reopens, the `Appender` starts writing at position 0, overwriting existing data.

**Missing:** Tail recovery on open. Options:
1. Scan all segments to find the actual tail (like recovery does for readers)
2. Store tail position in a metadata file
3. Make appender read-only on reopen (WAL mode only appends in one session)

Deferred to post-M1.

---

## Status summary (2026-11-05 end of session)

| Issue | Status |
|---|---|
| Pre-sliced buffer offset confusion | ✅ FIXED |
| Class name typo | ✅ FIXED |
| Appender rollover position calculation | ✅ FIXED |
| Tailer stops at segment boundaries | 🚧 IN PROGRESS - needs more debugging |
| Reopen overwrites existing data | 📋 DOCUMENTED - deferred to post-M1 |

