# Wally

**Wally** is a low-latency, lock-free Java library that implements a hybrid **Write-Ahead Log** and **Event Queue** entirely in off-heap memory, backed by memory-mapped files.

Standard Java queues (`ArrayBlockingQueue`, `ConcurrentLinkedQueue`) suffer from lock contention and GC pressure from per-message allocations. Wally avoids both:

- **Off-heap**: records live in mmap'd segment files accessed through the Java FFM API (`MemorySegment`).
- **Lock-free hot path**: producers claim space with a single atomic fetch-add; readers use acquire/release ordering.
- **Zero allocation in steady state**: flyweights and callback-based reads, so no per-message objects.
- **Hybrid**: one append-only log serves as a durable WAL (flush policies, crash recovery, checkpoints) and as an event queue (named consumer cursors, retention).

!!! warning "Status"
    Pre-alpha. The MVP is being built. See the [Roadmap](roadmap.md).

## Where to go next

- [Concepts](concepts.md): log, position, segment, claim/commit, cursor.
- [Blueprint](design/blueprint.md): the full design, problems to solve, and test strategy.
- [Roadmap](roadmap.md): MVP milestones and exit criteria.
- [ADRs](adr/0001-ffm-over-unsafe.md): why things are the way they are.
