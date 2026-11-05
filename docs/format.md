# File format

!!! info "Draft"
    Finalized in M1. All integers are little-endian. Format version: 1.

## Segment file

Name: `%020d.wly` (segment index, zero-padded). Size: fixed `segmentSize` (power of two).

### Segment header (64 B)

| Offset | Size | Field |
|---|---|---|
| 0 | 6 | magic `WALLY\0` |
| 6 | 2 | format version |
| 8 | 8 | segment size |
| 16 | 8 | base position (`segmentIndex * segmentSize`) |
| 24 | 36 | reserved (zero) |
| 60 | 4 | CRC32C of bytes 0–59 |

## Record (starts at a multiple of 8)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `frameLength`: header + payload bytes (unaligned); `0` = uncommitted. Written last, with release semantics. |
| 4 | 2 | `type`: `1` = DATA, `2` = PADDING |
| 6 | 2 | `flags` |
| 8 | 4 | CRC32C of the payload (0 when `ChecksumMode.NONE`) |
| 12 | 4 | reserved |
| 16 | `frameLength - 16` | payload |
| — | 0–7 | alignment padding to the next multiple of 8 |

A `PADDING` frame fills the remainder of a segment when the next record doesn't fit, and replaces aborted claims. It only uses `frameLength` and `type`, so it can be as short as 8 bytes. Because frames and segment ends are 8-aligned, the gap to fill is never smaller than that.

!!! note "Why the frame length includes the header"
    A payload-length field would make a header-only padding frame encode as `0`, which already means "uncommitted".
