# Wally

High-throughput, zero-allocation off-heap hybrid **Write-Ahead Log** and **Event Queue** for Java.

- Off-heap records in memory-mapped segment files (Java FFM API, JDK 25)
- Lock-free claim/commit hot path
- Zero allocation in steady state
- One log, two modes: durable WAL (flush policies, crash recovery) and event queue (consumer groups, retention)

> **Status:** pre-alpha. See [docs/roadmap.md](docs/roadmap.md).

## Docs

- [Blueprint](docs/design/blueprint.md)
- [Roadmap](docs/roadmap.md)
- [ADRs](docs/adr/)

Run the docs site locally:

```bash
pip install -r docs/requirements.txt
mkdocs serve
```
