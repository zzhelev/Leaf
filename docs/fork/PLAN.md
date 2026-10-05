# Gitnuro Fork — Plan for Claude Code

Place this file at `docs/fork/PLAN.md` in the fork. Keep everything under `docs/fork/` out of upstream PRs.

## Goal

Turn Gitnuro into a git client that handles AI-agent workflows well, mainly Claude Code and OpenCode. These agents
create many git worktrees. The core idea: a worktree is a branch with its own folder. The client should show worktrees
as a property of branches, not as separate repositories, and support their whole lifecycle: see, review, merge or
squash back, discard.

Secondary goal: fix easy open upstream issues in the fork.

Every change should be upstreamable to `JetpackDuba/Gitnuro` where reasonable.

## Working rules (apply to every phase)

1. Plan before coding. For each work item, explore the relevant code, write a short plan, and wait for approval before
   implementing.
2. One branch per work item, created off `main` (see Phase 0). Keep commits small and focused.
3. Stay upstreamable. Follow the existing code style and module boundaries. No unrelated refactors, no mass
   reformatting, no renames of existing packages or classes.
4. Ask before adding dependencies, changing architecture across modules, or touching build or packaging config.
5. Never run destructive git commands on real repositories. All manual and automated testing of git operations uses
   temporary repos created by test fixtures or scripts.
6. Definition of done: the app builds, existing tests pass, new tests cover new logic, the feature has been run manually
   on macOS against a temp repo, and a short entry is added to `docs/fork/CHANGELOG.md`.
7. Isolate fork-only code (for example, the git CLI adapter) in its own package so rebasing onto upstream stays easy.
8. Keep the product name, package names and bundle IDs unchanged for now. Renaming creates permanent merge pain with
   upstream. If a rename is wanted later, do it as one isolated commit touching only display name and packaging.

## Phase 0 — Setup and orientation

1. Remotes: `origin` is the fork and `upstream` is `JetpackDuba/Gitnuro`. Verify with `git remote -v`.
2. Find the active development line. The 2.0 rewrite (2.0.0-beta tags) is where new work should go. Determine whether it
   lives on upstream `main` or another branch.
    * Create `fork/main` from it. That's our integration branch.
    * Keep the local `main` as a pristine mirror of upstream.
    * Superseded on 2026-10-05: `fork/main` became `main`, published as the default branch of `zzhelev/Leaf`, and
      the local mirror was dropped. Sync through `upstream/main` instead.
3. Build and run on macOS.
    * Read `DEVELOPMENT.md` and follow it exactly.
    * Note the toolchain requirements: JDK version (2.0 betas reportedly need Java 25), the Rust toolchain for the `rs/`
      module, and anything else.
    * Record every command that worked.
4. Create `CLAUDE.md` at the repo root, containing:
    * Build, run and test commands, as verified.
    * Module map: `app`, `common`, `data`, `domain`, `ui`, `rs`. One or two lines each on responsibility.
    * Where git operations live, how JGit is wrapped, and how use cases are structured.
    * How a repository tab is opened and what state it holds.
    * How the branch list and sidebar are rendered.
    * How refresh works (file watching, polling, the Rust side).
    * Code style and conventions observed (DI approach, coroutines and dispatchers, state management).
5. Write `docs/fork/architecture-notes.md` answering these questions with file references:
    * JGit version in use.
    * Opening a linked worktree: Can Gitnuro open a linked worktree folder (its `.git` is a file pointing to
      `<common>/.git/worktrees/<name>`)? Test it by creating a temp repo, running `git worktree add ../wt-test -b test`,
      and opening `wt-test` in Gitnuro.
    * Checkout guard: Does checkout refuse a branch that is already checked out in another worktree? The git CLI
      refuses; JGit may not. If JGit allows it, that's a bug to fix in Phase 1.
    * Branch deletion guard: Does branch deletion refuse a branch that is checked out in another worktree?
    * gc and prune: Does Gitnuro ever run JGit `gc` or prune? JGit may not consider other worktrees' HEADs and indexes
      as roots, which risks deleting reachable objects.
6. Report findings and stop for review before Phase 1.

## Phase 1 — Worktree awareness (read-only)

### 1.1 Git CLI adapter

Worktree operations go through the real `git` CLI, not JGit. JGit stays in use for everything else.

* Runner: A small runner in the data layer that does the following:
    * Uses `ProcessBuilder` with a working directory, timeout and cancellation (coroutine-friendly).
    * Captures stdout and stderr and returns typed results or errors.
    * Sets `GIT_TERMINAL_PROMPT=0` and `LC_ALL=C` so output is parseable and never blocks on prompts.
* Locating git: macOS apps launched from Finder or the Dock get a minimal `PATH` (`/usr/bin:/bin:...`), so Homebrew git
  is not found.
    * Detect in order: a user-configured path in settings, then `/opt/homebrew/bin/git`, then `/usr/local/bin/git`,
      then `/usr/bin/git`.
    * Show the detected git path and version in settings.
* Minimum version: Require git ≥ 2.36 for `git worktree list --porcelain -z`. Show a clear message if older.

### 1.2 Domain model and parsing

* Model: `Worktree(path, headSha, branch: String?, isMain, isCurrent, isDetached, isBare, locked: String?, prunable: String?)`.
* Parser: For `git worktree list --porcelain -z`. Unit-test it with fixture outputs covering main, linked, detached,
  locked, prunable and bare cases.
* Status per worktree: Run `git -C <path> status --porcelain=v2 --branch` to get dirty state (staged, unstaged and
  untracked counts) and ahead/behind versus upstream.
* Ahead/behind versus a base branch (default `main` or `master`, configurable per repo):
  `git rev-list --left-right --count <base>...<branch>`.
* Integration tests: Create temp repos with multiple worktrees via the CLI and assert the parsed model.

### 1.3 Guards (if Phase 0 found JGit gaps)

* Checkout: If the user tries to check out a branch that lives in another worktree, block it. Show a "Switch to that
  worktree" action instead (wired up in Phase 2).
* Branch deletion: Block deleting a branch checked out in a worktree. Explain why, and offer "Remove worktree and delete
  branch" (wired up in Phase 3).

### 1.4 UI

* Branch list: Branches checked out in a worktree other than the current one get a badge (folder icon plus worktree
  name). The tooltip shows the full path, dirty state and ahead/behind versus base.
* Sidebar: A "Worktrees" section listing all worktrees with branch, dirty indicator, ahead/behind and last-commit age.
  The main and current worktrees are marked.
* Prunable worktrees (folder deleted manually) are shown greyed out with a "Prune" hint. The action itself comes in
  Phase 3.

### 1.5 Refresh

* Why polling is needed: The existing file watcher likely covers only the open worktree. Changes made by agents in
  other worktrees must still appear.
* Watch the shared directory: Watch `<common-dir>/worktrees/` for worktrees being added or removed.
* Poll worktree status on window focus and on a modest interval (for example every 10 s, configurable). Only poll while
  the sidebar section is visible. Must not cause the UI freezes described in upstream issue #335.

Acceptance: With a repo where Claude Code has created two worktrees, Gitnuro shows both in the sidebar and as badges in
the branch list. It updates within seconds when an agent commits or leaves uncommitted changes, without freezing the
UI.

## Phase 2 — Switching to worktrees

This is the key piece of UX: opening a worktree should feel like switching branches.

### 2a. Quick version

* Double-clicking a badged branch or a sidebar worktree opens that worktree's folder in a new repository tab.
* If a tab for that path already exists, focus it instead.

### 2b. Target version

* Tab model: A tab represents the repository (the common git dir). A worktree selector in the tab header shows the
  active worktree.
* Switching: Selecting another worktree switches the tab's working directory, status, staging area and diff views to
  that folder. History, branches, tags and stashes are shared anyway.
* What it should feel like: Double-clicking a branch that lives in another worktree switches the tab to that worktree,
  exactly like checkout feels for a normal branch.
* Plan first: 2b likely touches how tab state is scoped. Produce a design note in `docs/fork/` and get approval before
  implementing.

## Phase 3 — Worktree lifecycle actions

Every destructive action requires a confirmation dialog that shows the exact git commands to be run.

1. Create worktree from a branch, commit or new branch.
    * The dialog takes a path and a branch name. The default path pattern is configurable per repo (for example a
      sibling folder `../<repo>-<branch>`).
    * Copy ignored files option: A per-repo list of gitignored files to copy into new worktrees, for example
      `local.properties` and `.env`.
    * Off by default, with a clear explanation of why it exists: Android builds fail in fresh worktrees without
      `local.properties`.
2. Review worktree changes: Show a diff of `<base>...<branch>` (merge-base diff) with a file list. Reuse the existing
   diff UI. Check whether "diff between two commits" (upstream issue #206) already exists in 2.0 and build on it.
3. Squash-merge into base:
    * Runs `git merge --squash <branch>` in the worktree where `<base>` is checked out, usually main, and opens the
      commit dialog prefilled with a message built from the branch's commit subjects.
    * If `<base>` is not checked out anywhere, offer to check it out in the current worktree first.
4. Regular merge into base: Same, without `--squash`.
5. Rebase branch onto base:
    * Runs in the branch's own worktree.
    * Warn if that worktree has uncommitted changes, or was modified within the last few minutes (an agent may still be
      working).
6. Conflicts:
    * Reuse Gitnuro's existing conflict UI.
    * Add an "Open in external merge tool" action that runs `git mergetool` in the correct worktree, respecting the
      user's configured tool (for example Meld).
7. Remove worktree:
    * Runs `git worktree remove <path>`, refusing when the worktree is dirty unless the user explicitly chooses force.
    * A checkbox also deletes the branch, with a warning if the branch is not merged into base.
8. Discard: Combines removing the worktree and deleting the branch (force-delete, after an explicit second confirmation
   if unmerged).
9. Prune, lock and unlock: `git worktree prune`, `git worktree lock` and `git worktree unlock`, available from the
   sidebar context menu.

Acceptance: On a temp repo with three agent worktrees, the full flow works from the UI alone for each one: review,
squash-merge one, rebase one, discard one. Afterwards no stale entries remain in `git worktree list`.

## Phase 4 — Agent awareness (nice to have)

1. Origin labels: Label worktrees by origin using configurable path patterns.
    * Claude Code: Find out where it places worktrees by default (check the current docs; likely under
      `.claude/worktrees/`).
    * OpenCode: Find out from its docs or config where it places worktrees. Do not assume a location.
    * Show a small label like "Claude Code" or "OpenCode" on the badge.
2. Avoid duplicating harness features: Before building the copy-ignored-files feature from Phase 3, check whether the
   harnesses already offer something similar. If they do, document it in `docs/fork/` instead of competing with it.
3. Stale indicators: Mark worktrees whose branch is already merged into base, and worktrees untouched for N days. Offer
   a "Clean up merged worktrees" action that lists candidates and asks for confirmation.

## Phase 5 — Easy upstream issues

1. List open issues: `gh issue list -R JetpackDuba/Gitnuro --state open --limit 200 --json number,title,labels,body,createdAt`.
2. Triage into `docs/fork/issue-triage.md` with columns: number, title, still reproducible on 2.0 (yes/no/untested),
   estimated effort (S/M/L), notes.
3. Starting candidates to verify first, judged only by their titles, so the size estimates are unconfirmed:
    * #345 separate untracked and modified files in the unstaged area: possibly S.
    * #349 macOS tab-switch shortcut conflicting with text editing: possibly S.
    * #343 show a commit's file changes in a separate panel: possibly M.
    * #347 better handling of moved files: unknown.
    * Defer the large ones: #331 system SSH client, #336 sparse checkout, #186 conditional gitconfig includes (a JGit
      limitation).
4. Fixing: Fix S items one branch each, with tests where feasible. Before preparing an upstream PR, check upstream
   Discussions, because issue creation upstream is restricted and feature PRs are expected to be discussed first.

## Keeping in sync with upstream

* Weekly: Fetch `upstream` and merge `upstream/main` into `main`, resolving conflicts in the fork-only code first.
  `main` is published, so merge instead of rebasing it.
* After each sync: Run the full test suite, then launch the app and smoke-test the worktree sidebar.
* Upstreaming: For each feature, keep a clean branch rebased on upstream's development line so it can become a PR
  without fork-only scaffolding.

## Kick-off prompt

Read `docs/fork/PLAN.md`. Execute Phase 0 only. Follow the working rules. Stop and report when Phase 0 is done.
