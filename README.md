# Wally

High-throughput, zero-allocation off-heap hybrid **Write-Ahead Log** and **Event Queue** for Java.

- Off-heap records in memory-mapped segment files (Java FFM API, JDK 25)
- Lock-free claim/commit hot path
- Zero allocation in steady state
- One log, two modes: durable WAL (flush policies, crash recovery) and event queue (consumer groups, retention)

> **Status:** pre-alpha. See [docs/roadmap.md](docs/roadmap.md).

## Quick Start (Development)

```bash
# Build and test
./gradlew check

# Run benchmarks
./gradlew :wally-bench:jmh

# View documentation
pip install -r docs/requirements.txt
mkdocs serve  # http://127.0.0.1:8000
```

## For New Contributors / Next Session

**START HERE:**
1. **[SESSION_HANDOFF.md](SESSION_HANDOFF.md)** ⭐ - Current state, what works, what needs work
2. **[CHANGELOG.md](CHANGELOG.md)** - All changes by milestone
3. **[docs/adr/0005-m1-segment-coordination-challenges.md](docs/adr/0005-m1-segment-coordination-challenges.md)** - Key learnings

## Documentation

- **[Blueprint](docs/design/blueprint.md)** - Full system design
- **[Roadmap](docs/roadmap.md)** - M0-M6 milestones
- **[How it works](docs/how-it-works.md)** - Java concepts explained
- **[Glossary](docs/glossary.md)** - 65 terms defined
- **[ADRs](docs/adr/)** - Design decisions
- **[RULES.md](RULES.md)** - Development workflow
