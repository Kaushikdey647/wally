# Guarantees

!!! info "Draft"
    Finalized and verified in M3.

## Durability per flush policy

| Policy | Survives process crash | Survives OS crash / power loss | Cost |
|---|---|---|---|
| `none()` | Yes, once committed (page cache) | No guarantee | Lowest latency |
| `periodic(d)` | Yes | Records at or below `flushedPosition()`; up to `d` of data may be lost | Group commit |
| `sync()` | Yes | Yes, once `append` returns | One `force()` per append |

!!! warning "macOS"
    `msync`/`fsync` on macOS does not guarantee data reaches stable storage. True durability needs `F_FULLFSYNC`. Treat macOS as a development platform.

## Visibility and ordering

- A record is visible to tailers once its `length` is published with release semantics.
- Tailers see records in position order. They never skip an uncommitted record; they wait at it.
- With multiple producers, position order is claim order, not commit order.

## Recovery

On open, Wally scans from the last checkpoint and truncates at the first uncommitted or CRC-invalid record. Recovery never surfaces a corrupt record.
