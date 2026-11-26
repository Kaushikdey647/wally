# Documentation Guide

## Documentation Structure

Wally's documentation is organized into layers, from beginner-friendly to deep technical details.

### 1. Entry Points

**[README.md](README.md)** - Main entry point
- API overview and reference
- Quick start example
- Architecture diagrams
- Performance characteristics
- Usage patterns
- Troubleshooting
- Comparisons with alternatives

**[docs/quickstart.md](docs/quickstart.md)** - Hands-on tutorial
- 5-minute getting started guide
- Complete working examples
- Common patterns (zero-copy, batch processing)
- Performance tips

### 2. Technical Deep Dive

**[docs/implementation.md](docs/implementation.md)** - Internal implementation
- Memory layout (cursors, frames, alignment)
- Write/read algorithms with pseudocode
- Synchronization protocol (acquire/release)
- Wrapping mechanics (sentinel, virtual cursors)
- Performance analysis (critical path, bottlenecks)
- Correctness properties (safety, liveness)
- Design trade-offs with rationale

### 3. Reference Materials

**[docs/glossary.md](docs/glossary.md)** - Terminology reference
- Ring buffer concepts
- Memory and concurrency terms
- Performance terminology
- Trading domain terms
- Comparisons with other libraries
- Acronyms

**[MIGRATION.md](MIGRATION.md)** - Migration guide
- What changed from WAL to SPSC
- API differences
- Design changes
- Performance comparison

**[docs/README.md](docs/README.md)** - Documentation index
- Navigation hub for all documentation
- Quick reference links

## Documentation Philosophy

### Technical Precision

Documentation is written for experienced Java developers and system programmers who need to understand **how and why** things work, not just what to call.

**Characteristics:**
- Assumes knowledge of Java concurrency, memory models, FFM API
- Explains trade-offs and design decisions
- Provides implementation details (memory layout, algorithms)
- Includes performance analysis with measurements
- References authoritative sources (JLS, JMM, JEPs)

### Practical Examples

Every concept illustrated with:
- Working code snippets (compilable)
- Realistic use cases (trading systems)
- Performance measurements (actual hardware)
- Troubleshooting guidance (common issues)

### Layered Complexity

Three levels of detail:

1. **Quick Start** - Get working code in 5 minutes
2. **API Reference** - Understand what each class/method does
3. **Implementation** - Understand how it works internally

Readers can stop at the level they need.

## Target Audience

### Primary Audience

**High-frequency trading developers** who need:
- Sub-microsecond latencies
- Zero GC pause guarantee
- Full control over memory layout
- Understanding of synchronization costs

**Expected background:**
- Java 17+ experience
- Understanding of concurrency (threads, synchronization)
- Familiarity with memory models (volatile, happens-before)
- Performance tuning experience

### Secondary Audience

**Systems programmers** learning:
- Lock-free algorithms
- Off-heap memory management
- FFM API usage
- Cache optimization techniques

## Writing Style

### Code Examples

**Always**:
- Use realistic variable names (`orderId`, not `x`)
- Include types (`long orderId`, not `var`)
- Show full context (imports, setup)
- Compilable snippets when possible

**Never**:
- Pseudo-code where real code fits
- Oversimplified examples that hide complexity
- Magic numbers without explanation

### Technical Explanations

**Structure**:
1. What (brief description)
2. Why (motivation, problem solved)
3. How (algorithm, implementation)
4. Trade-offs (alternatives considered)
5. Verification (how correctness is ensured)

**Example** (from implementation.md):
```
## Virtual Cursors

**What**: Monotonically increasing 64-bit counters tracking byte position.

**Why**: Avoids complex wrap-around arithmetic. Natural sequence numbers.

**How**: Index computed via masking: `int index = (int) (cursor & mask);`

**Trade-off**: 64-bit arithmetic vs 32-bit. Negligible on 64-bit CPUs.

**Verification**: Tested with 100K messages across multiple wraps.
```

### Diagrams

Use ASCII art for:
- Memory layouts
- State transitions
- Sequence diagrams
- Data structures

**Benefits**:
- Render everywhere (GitHub, IDE, terminal)
- Searchable text
- Easy to update
- Version control friendly

### Performance Claims

**Always cite**:
- Hardware (CPU model, frequency, cores)
- Measurement method (JMH, profiler, manual timing)
- Workload (message size, batch size)
- Conditions (pinned cores, isolation, warmup)

**Example**:
> Write latency: ~25ns (p50), measured with JMH on Intel Xeon Platinum 8259CL @ 2.5GHz, isolated cores, 10K warmup iterations, 32-byte messages.

**Never**:
> Fast writes (< 50ns)  ← Vague, unverifiable

## Maintenance

### When Code Changes

Update documentation in this order:

1. **README.md** - API changes, new features
2. **docs/quickstart.md** - If usage patterns change
3. **docs/implementation.md** - If algorithms change
4. **docs/glossary.md** - New terms introduced
5. **MIGRATION.md** - Breaking changes

### Keeping Performance Data Current

Re-run benchmarks when:
- Algorithm changes
- Memory layout changes
- Java version upgrade
- Hardware upgrade

Update all performance tables with:
- New numbers
- Hardware spec
- Date of measurement

### Version-Specific Documentation

For breaking changes:
- Keep old version in `docs/archive/v1/`
- Add migration notes in MIGRATION.md
- Link from README to archived docs

## Documentation Quality Checklist

### New Feature Documentation

- [ ] README.md updated with API example
- [ ] quickstart.md includes usage example
- [ ] implementation.md explains internals
- [ ] glossary.md defines new terms
- [ ] All code examples compile and run
- [ ] Performance claims measured and cited
- [ ] Comparisons with alternatives (if applicable)

### Pull Request Review

- [ ] Technical accuracy verified
- [ ] Code examples tested
- [ ] Performance claims reproducible
- [ ] Links work (internal and external)
- [ ] Formatting consistent (Markdown lint)
- [ ] Grammar/spelling checked

## Common Documentation Patterns

### Introducing a Concept

```markdown
## Concept Name

Brief one-line description.

### Why It Matters

Problem this solves or benefit it provides.

### How It Works

Algorithm or implementation details.

**Example:**
```java
// Working code illustrating the concept
```

### Trade-offs

What alternatives exist and why this was chosen.
```

### API Documentation

```markdown
### ClassName

Brief description of purpose.

```java
public interface ClassName {
    ReturnType methodName(ArgType arg)
}
```

**Usage:**
```java
// Working example
```

**Parameters:**
- `arg` - Description and constraints

**Returns:**
- Description of return value and meaning

**Guarantees:**
- Thread safety
- Memory ordering
- Failure modes
```

### Performance Section

```markdown
## Performance

### Latency

| Operation | p50 | p99 | p999 |
|-----------|-----|-----|------|
| Write     | 25ns| 40ns| 100ns|

**Measurement**: JMH, Intel Xeon, isolated cores, 32-byte messages.

### Throughput

20M msgs/sec sustained (25M burst).

**Bottleneck**: Memory bandwidth (payload > 128B).

### Optimization Tips

1. Pin producer/consumer to same socket
2. Use huge pages for TLB efficiency
3. ...
```

## External References

Prefer authoritative sources:
- **JLS** (Java Language Specification) for language semantics
- **JVM Specification** for memory model
- **JEPs** for new features (e.g., JEP 454 for FFM)
- **Intel/ARM manuals** for hardware memory models
- **Academic papers** for algorithms (cite author, year, conference)

Link format: `[Title](URL) (Author, Year)`

Example:
> [The Art of Multiprocessor Programming](https://doi.org/10.1016/B978-0-12-397337-5.00001-4) (Herlihy & Shavit, 2012)

## Tools

### Documentation Build

```bash
# Local preview (if using MkDocs)
mkdocs serve

# Check markdown lint
markdownlint docs/
```

### Code Example Validation

Extract and compile all code examples:

```bash
# Extract code blocks
grep -A 20 '```java' docs/*.md > examples.java

# Compile
javac examples.java
```

### Link Checking

```bash
# Check for broken links
find docs -name "*.md" -exec grep -H '\[.*\](.*)' {} \; | check-links.sh
```

## Related Files

- `mkdocs.yml` - Documentation site configuration
- `docs/` - All documentation source files
- `README.md` - Main project README
- `MIGRATION.md` - Migration guide
- `CLEANUP_STATUS.md` - Restructure details

## Questions?

For documentation issues or suggestions:
- Open an issue: [GitHub Issues](https://github.com/wallylabs/wally/issues)
- Discuss improvements: [GitHub Discussions](https://github.com/wallylabs/wally/discussions)
