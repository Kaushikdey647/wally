# Development workflow

## For each milestone or significant task

1. **Create a GitHub issue** describing the work.
   - Title: short summary (e.g., "M1: mmap'd segments, rollover, checksums")
   - Body: scope, exit criteria, what's in/out
   - Label it with the milestone tag if one exists

2. **Create a branch** named after the issue or milestone.
   - Format: `<milestone>-<short-name>` (e.g., `m1-mmap-segments`)
   - Associate it with the issue in the PR later

3. **Do the work** on that branch.
   - Commit often, with clear messages
   - Reference the issue number in commits where relevant: `fixes #123` or `part of #123`
   - **Update documentation with each change**: when you add/modify code, update the relevant docs in the same commit or immediately after
     - New API → update `docs/concepts.md` or create new guide pages
     - Changed behavior → update affected doc pages
     - New milestone features → update `docs/roadmap.md` to mark progress
     - Design decisions → add or update ADRs in `docs/adr/`
     - Keep `README.md` current with the latest status

4. **Open a pull request** when ready.
   - Base: `main` (or another branch if building on unreleased work)
   - Title: same as the issue, or a summary of what changed
   - Body: link to the issue (`Closes #123`), summarize what was built, paste verification results
   - The PR triggers CI (tests + build on Linux and macOS)

5. **Review and merge.**
   - Wait for CI to pass
   - Squash or merge as appropriate
   - Delete the branch after merging

## Commit message format

```
<type>: <short summary>

<optional longer explanation, wrapped at 72 columns>

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```

**Types:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`, `build`

**Examples:**

- `feat(m1): add SegmentFile with mmap and refcounting`
- `test: add jqwik model test for rollover`
- `docs: update roadmap with M1 results`

## Documentation discipline

**Every code change must be documented.**

When you write code, update the docs in the same commit or the very next one:

| You changed... | Update these docs |
|---|---|
| Public API (`io.wally.api`) | `docs/concepts.md`, add usage examples, update quickstart if it exists |
| Internal behavior (framing, segment rolling, recovery) | Relevant pages in `docs/`, ensure accuracy |
| File format (header, record layout) | `docs/format.md` and `docs/record-layout.md` |
| Durability or concurrency semantics | `docs/guarantees.md`, `docs/how-it-works.md` |
| Design decision | Add or update an ADR in `docs/adr/` |
| Milestone progress | Mark checkboxes or update status in `docs/roadmap.md` |
| Overall status or quick-start | `README.md` |

**Why:** Docs that lag behind code become wrong, and wrong docs are worse than no docs. Keeping them in sync as you go is cheaper than a big docs sprint later.

## What stays on `main`

- Only merged, tested, **documented** work
- The `main` branch is always in a working state: `./gradlew check` passes
- Docs on `main` match the code on `main`

## Branch protection (recommended once the repo is public)

- Require CI to pass before merging
- Require at least one review (if working with others)
- No direct pushes to `main`

## Quality principles

### Completeness over box-ticking

**When building a feature or benchmark, prioritize production-grade completeness over "done" checkmarks.**

**For benchmarks specifically:**

- **Test realistic workloads**, not synthetic best-cases
  - Multi-threaded benchmarks for concurrent data structures
  - Cold starts AND steady-state
  - Edge cases: segment boundaries, queue full, etc.
- **Measure what matters**
  - Tail latency (p99, p99.9, p99.99), not just averages
  - Use HdrHistogram + fixed-rate drivers to avoid coordinated omission
  - GC pauses, allocation rate, thread contention
- **Fair comparisons**
  - Match durability guarantees (flush policies)
  - Match concurrency models (MPSC vs MPSC, not single-thread)
  - Document what's different (API maturity, Unsafe vs FFM)
- **Reproducible**
  - Pin versions, document environment (CPU, OS, JDK)
  - Publish raw data, not just summaries
  - CI regression gates (coarse) + dedicated hardware (detailed)

**For code:**

- **Validate assumptions** with property tests (jqwik), concurrency tests (jcstress/Lincheck)
- **Handle edge cases** that will happen in production: segment rollover, stuck producers, disk full
- **Instrument for debugging**: expose internal state (tail position, flushed position) for troubleshooting

**Why:** A half-baked feature shipped is worse than waiting for the complete version. Wally's value proposition (zero-allocation, lock-free, durable) requires proof, not claims. Build to convince a skeptical engineer reviewing your code, not to check off a roadmap item.

**When to compromise:** Pre-alpha/MVP scope cuts are fine IF documented. Mark as "TODO: cross-process IPC (M5)" or "Known limitation: no stuck-producer recovery yet." But within the chosen scope, go deep.
