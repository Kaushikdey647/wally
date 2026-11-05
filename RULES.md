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

## What stays on `main`

- Only merged, tested, documented work
- The `main` branch is always in a working state: `./gradlew check` passes
- Docs on `main` match the code on `main`

## Branch protection (recommended once the repo is public)

- Require CI to pass before merging
- Require at least one review (if working with others)
- No direct pushes to `main`
