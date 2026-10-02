# Fork changelog

This file covers fork-only changes on `fork/main`. Upstream history is in git.

## Open linked worktrees (branch `fix/open-linked-worktree`)

- **Linked worktrees now open.** `OpenRepositoryGitAction` used to treat every `.git` file as a submodule. Now, when
  the `gitdir:` target contains a `commondir` file, it opens the folder directly as a linked worktree. Absolute and
  relative `gitdir:` paths both work. This covers sibling worktrees (`../repo-feature`), which used to fail silently,
  and nested ones (`.claude/worktrees/<name>`), which used to work only by accident.
- **Failures surface.** Opening a repository no longer throws. Failures come back as `OpenRepoError`, so the error
  dialog appears instead of the open dying silently.
- **"Open in terminal"** now uses the working tree instead of the parent of the git dir, which for a linked worktree
  was `<common>/.git/worktrees`.
- **Tests:** `OpenRepositoryGitActionTest` covers seven cases: a regular repo, sibling, nested and relative-path
  worktrees, a submodule, a non-repo folder, and a broken `.git` file.
  - Against the old code, the sibling, relative-path and broken-file cases fail, and the other four pass. So the fix
    is what makes them pass, and existing behaviour is unchanged.
- **Shared test helpers:** `IsolatedSystemReader`, plus `TestGitCli`, which runs git with the developer's global and
  system config ignored.
- **Manual check:** on macOS, both a sibling and a nested temp worktree open in the app, with no errors, and both are
  added to recent repos.
- **Still open:**
  - Tab subtitle and persisted path show `<common>/.git/worktrees/<name>` (Phase 2b).
  - The watcher doesn't cover the common `refs/` (Phase 1.5).

## 1.0: Turn off JGit auto-GC (branch `fix/jgit-autogc`)

- JGit's automatic gc is now off by default. JGit runs it after merges, rebases, fetches and pushes, and it is not
  worktree-aware: objects referenced only by another worktree's detached HEAD, index or reflog get pruned.
- How it works: `NoAutoGcSystemReader` (in `data/git/`) injects `gc.auto=0` and `gc.autoPackLimit=0` underneath
  every config file. It is installed in `main.kt` before any repository is opened.
  - Explicit user or repository settings still take precedence.
  - Nothing is written to disk.
  - The git CLI's own `gc --auto` is unaffected.
- Tests: the first in the codebase, `NoAutoGcSystemReaderTest`. They run against temp repos with isolated config files
  and cover:
  - the effective values;
  - that explicit config takes precedence;
  - that nothing is written to disk;
  - that auto-gc is skipped, with a control case showing it would otherwise run.
- Manual check: the app started and opened a temp repo on macOS, with no errors in the log.
- The fix commit contains no fork-only files, so it can be cherry-picked onto upstream.

## Phase 0: setup and orientation (2026-10-02)

- Created `fork/main` from `upstream/main` (`62442f26`, `2.0.0-beta03-4`).
- Added `CLAUDE.md`, which covers the verified toolchain and commands, the module map, the architecture, and the
  conventions.
- Added `docs/fork/PLAN.md`, the fork plan and working rules.
- Added `docs/fork/architecture-notes.md`, which records:
  - the JGit version;
  - linked-worktree opening;
  - the checkout and branch-deletion guards;
  - the gc risk.
- Added `docs/fork/probes/`, a reproducible JGit-versus-git-CLI probe for those findings. It runs against temp repos
  only.
