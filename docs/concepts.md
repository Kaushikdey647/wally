# Concepts

!!! info "Draft"
    Filled in as milestones land.

| Term | Meaning |
|---|---|
| **Log** | Append-only sequence of records spread across fixed-size segment files. |
| **Position** | 64-bit logical byte address of a record: `segmentIndex * segmentSize + offset`. Monotonic, never reused. |
| **Segment** | One pre-sized, memory-mapped file (`%020d.wly`). Starts with a 64 B header. |
| **Record** | 16 B header + payload, 8 B aligned. `length == 0` means not yet committed. |
| **Claim / commit** | Two-phase write: reserve space, write the payload in place, publish by writing `length` with release semantics. |
| **Appender** | Per-thread write handle. Reuses one `Claim` flyweight. |
| **Tailer** | Read handle. Polls records via a callback `(segment, offset, length, position)`. |
| **Consumer group** | Named tailer whose position is persisted in the cursor file. |
| **Flush policy** | When page-cache data is forced to disk: `none`, `periodic`, `sync`. |
| **Flushed position** | Highest position guaranteed durable under the current flush policy. |
| **Checkpoint** | WAL-mode marker: everything below it may be deleted. |
| **Retention** | Deletes segments wholly below the minimum of all group cursors and the checkpoint. |
