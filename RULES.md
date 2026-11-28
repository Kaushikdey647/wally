# Development workflow

## For each significant task

1. **Create a GitHub issue** describing the work.
   - Title: short summary (e.g., "P0: SPSC correctness and 1P1C benchmarks")
   - Body: scope, exit criteria, what's in/out

2. **Create a branch** named after the issue.
   - Format: `<priority>-<short-name>` (e.g., `p0-spsc-correctness`)
   - Associate it with the issue in the PR later

3. **Do the work** on that branch.
   - Commit often, with clear messages
   - Reference the issue number in commits where relevant: `fixes #123` or `part of #123`
   - **Update documentation with each change**: when you add/modify code, update the relevant docs in the same commit or immediately after
     - New API → update `docs/quickstart.md` and `README.md`
     - Changed behavior → update affected doc pages
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

- `fix: never write wrap sentinel over unread frames`
- `test: jcstress publication tests for the SPSC ring`
- `docs: record 1P1C benchmark results`

## Documentation discipline

**Every code change must be documented.**

When you write code, update the docs in the same commit or the very next one:

| You changed... | Update these docs |
|---|---|
| Public API (`io.wally.spsc`) | `docs/quickstart.md`, `README.md` API section |
| Internal behavior (framing, wrapping, cursors) or memory ordering | `docs/implementation.md` |
| Performance | Re-run benchmarks, commit `wally-bench/results/`, update `docs/benchmarks.md` and `README.md` |
| Design decision | Add or update an ADR in `docs/adr/` |
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
  - Edge cases: wrap boundary, ring full, mixed message sizes
- **Measure what matters**
  - Tail latency (p99, p99.9, p99.99), not just averages
  - Use HdrHistogram + fixed-rate drivers to avoid coordinated omission
  - GC pauses, allocation rate, thread contention
- **Fair comparisons**
  - Match concurrency models (1P1C against SPSC queues, never single-threaded)
  - Document what is different (payload copy vs reference passing, Unsafe vs FFM)
- **Reproducible**
  - Pin versions, document environment (CPU, OS, JDK)
  - Publish raw data, not just summaries
  - CI regression gates (coarse) + dedicated hardware (detailed)

**For code:**

- **Validate assumptions** with randomized multi-threaded tests, memory-ordering tests (jcstress), layout checks (JOL)
- **Handle edge cases** that will happen in production: wrap with a full ring, a consumer that stops reading, handlers that throw
- **Instrument for debugging**: expose internal state (`available()`, `sequence()`) for troubleshooting

**Why:** A half-baked feature shipped is worse than waiting for the complete version. Wally's value proposition (zero-allocation, lock-free, correct under concurrency) requires proof, not claims. Build to convince a skeptical engineer reviewing your code, not to check off a roadmap item.

**When to compromise:** Pre-alpha/MVP scope cuts are fine IF documented. Mark as "Known limitation: SPSC only" or "TODO: batched head publication". But within the chosen scope, go deep.
