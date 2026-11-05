# Record layout

Every record starts at an offset that is a **multiple of 8 bytes**. After a record ends, 0–7 padding bytes are added so the next record starts at the next 8-byte boundary.

## Header: 16 bytes

| Byte offset | Size (bytes) | Java type | Field name | Purpose |
|-------------|--------------|-----------|------------|---------|
| 0 | 4 | `int` | `frameLength` | Total frame size: header (16) + payload bytes. **0 means "not committed yet"**. Written last with release semantics. |
| 4 | 2 | `short` | `type` | `1` = DATA (your record), `2` = PADDING (fills gaps) |
| 6 | 2 | `short` | `flags` | Reserved for future use (currently unused) |
| 8 | 4 | `int` | `crc32c` | CRC32C checksum of the payload. `0` when checksums are disabled. |
| 12 | 4 | `int` | `reserved` | Reserved (currently unused) |

## Payload: variable length

| Byte offset | Size (bytes) | Contents |
|-------------|--------------|----------|
| 16 | `frameLength - 16` | Your data (the bytes you appended) |

## Alignment padding: 0–7 bytes

After the payload ends, the next record must start at a multiple of 8. So if your payload is 19 bytes:

- Header: 16 bytes
- Payload: 19 bytes
- Total so far: 35 bytes
- Next multiple of 8: 40 bytes
- **Padding added: 5 bytes**

The padding bytes are not part of the record. They are just wasted space to keep the next record aligned.

## Examples

### Example 1: 5-byte payload

```
Byte:  0    1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18   19   20   21   22   23
      ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
Field │  frameLength=21   │type│flg │   crc32c      │  reserved     │ payload (5 bytes)      │ pad (3 B)      │ next record...
      │   (16 + 5)        │ =1 │    │               │               │                        │                │
      └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
Offset  0                   4    6    8                12              16                       21               24
```

- `frameLength` = 16 + 5 = **21 bytes**
- The record occupies bytes 0–20
- Padding is 3 bytes (21 → 24, next multiple of 8)
- Next record starts at byte **24**

### Example 2: 64-byte payload (already aligned)

```
Byte:  0    ...    15   16   ...   79   80
      ┌───────────────┬──────────────┬────┐
Field │ 16 B header   │ 64 B payload │next│
      │frameLength=80 │              │rec │
      └───────────────┴──────────────┴────┘
```

- `frameLength` = 16 + 64 = **80 bytes**
- 80 is already a multiple of 8, so **no padding**
- Next record starts at byte **80**

### Example 3: PADDING frame (fills end of segment)

When a record doesn't fit in the remaining space, a `PADDING` frame is written:

```
Byte:  0    1    2    3    4    5    6    7
      ┌────┬────┬────┬────┬────┬────┬────┬────┐
      │frameLength=1000   │type│    │ (unused)  │
      │                   │ =2 │    │           │
      └────┴────┴────┴────┴────┴────┴────┴────┘
```

- `frameLength` = however many bytes remain (could be 8, could be 1000)
- `type` = 2 (PADDING)
- Everything else is ignored
- Readers skip padding frames and move to the next segment

## Why `frameLength` includes the header

If `frameLength` held only the payload length:

- A padding frame with no payload would be `frameLength = 0`
- But **0 already means "not committed"**

So `frameLength` = header + payload. A minimum-size padding frame is 8 bytes: `frameLength = 8`, `type = 2`, and that's it. The reader knows to skip the next 8 bytes and keep going.

## In the code

The layout is defined in `io.wally.internal.log.RecordFormat`:

```java
public static final int ALIGNMENT = 8;
public static final int HEADER_LENGTH = 16;

public static final int FRAME_LENGTH_OFFSET = 0;
public static final int TYPE_OFFSET = 4;
public static final int FLAGS_OFFSET = 6;
public static final int CRC_OFFSET = 8;

public static final short TYPE_DATA = 1;
public static final short TYPE_PADDING = 2;
```

Commit uses a release write:

```java
public static void publish(MemorySegment buffer, long frameOffset, int frameLength) {
    Mem.setIntRelease(buffer, frameOffset + FRAME_LENGTH_OFFSET, frameLength);
}
```

And the reader uses an acquire read:

```java
int frameLength = Mem.getIntAcquire(buffer, offset);
if (frameLength == 0) {
    break;   // not committed yet
}
```

See [How Wally works](how-it-works.md#5-varhandle-ordered-and-atomic-memory-access) for why this guarantees that once the reader sees the length, it sees the complete record.
