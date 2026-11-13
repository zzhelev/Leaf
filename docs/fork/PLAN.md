# Leaf fork — Plan for Claude Code

Place this file at `docs/fork/PLAN.md` in the fork.

## Goal

Turn the upstream git client into one that handles AI-agent workflows well, mainly Claude Code and OpenCode. These
agents create many git worktrees. The core idea: a worktree is a branch with its own folder. The client should show
worktrees as a property of branches, not as separate repositories, and support their whole lifecycle: see, review, merge
or squash back, discard.

Secondary goal: fix open upstream issues in the fork (Phase 5).

Leaf sends no pull requests upstream (decided on 2026-10-09). It keeps merging `upstream/main` to take
upstream's fixes, so changes to the files that come from upstream stay small, which keeps those merges cheap.

## Working rules (apply to every phase)

1. Plan before coding. For each work item, explore the relevant code, write a short plan, and wait for approval before
   implementing.
2. One branch per work item, created off `main` (see Phase 0). Keep commits small and focused.
3. Keep upstream merges cheap. Follow the existing code style and module boundaries. In the files that come from
   upstream, change what the work item needs: no unrelated refactors, no mass reformatting, no cleanup of dead code or
   hardcoded strings. A rewritten file turns every later upstream change to it into a hand port. Replace one only for
   a reason that a targeted fix can't meet, such as the hunk and line actions moving to `git apply`.
4. Ask before adding dependencies, changing architecture across modules, or touching build or packaging config.
5. Never run destructive git commands on real repositories. All manual and automated testing of git operations uses
   temporary repos created by test fixtures or scripts.
6. Definition of done: the app builds, existing tests pass, new tests cover new logic, the feature has been run manually
   on macOS against a temp repo, and a short entry is added to `docs/fork/CHANGELOG.md`.
7. Isolate fork-only code (for example, the git CLI adapter) in its own package so merging upstream stays easy.
8. Superseded: the fork ships as Leaf, with its own name, packages, bundle ID and storage (see `CLAUDE.md`, Name). How
   upstream merges handle the package rename is in `CLAUDE.md`, Branches and remotes.

## Phase 0 — Setup and orientation

1. Remotes: `origin` is the fork and `upstream` is the original project's repository. Verify with `git remote -v`.
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
    * Opening a linked worktree: Can the original app open a linked worktree folder (its `.git` is a file pointing to
      `<common>/.git/worktrees/<name>`)? Test it by creating a temp repo, running `git worktree add ../wt-test -b test`,
      and opening `wt-test` in the original app.
    * Checkout guard: Does checkout refuse a branch that is already checked out in another worktree? The git CLI
      refuses; JGit may not. If JGit allows it, that's a bug to fix in Phase 1.
    * Branch deletion guard: Does branch deletion refuse a branch that is checked out in another worktree?
    * gc and prune: Does the original app ever run JGit `gc` or prune? JGit may not consider other worktrees' HEADs and
      indexes as roots, which risks deleting reachable objects.
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

Acceptance: With a repo where Claude Code has created two worktrees, Leaf shows both in the sidebar and as badges in
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
    * Reuse upstream's existing conflict UI.
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

## Phase 5 — Upstream issues

Status on 2026-11-13.

1. Triage, done on 2026-10-16: upstream's 89 open issues, read against Leaf's `main` (still reproducible, effort S, M
   or L for Leaf's code, notes from the code and from probes with JGit 7.7 and git 2.54). 60 still applied (18 S,
   27 M, 15 L), 21 were already fixed or replaced in Leaf, and 8 need a platform that this machine lacks. The triage
   is kept with the sessions' notes, outside the repository. Issues opened since aren't in it.
2. Fixed, one branch each, with tests and a `docs/fork/CHANGELOG.md` entry that names the issue:
    * Before the triage: #186 (git's identity, signing, hooks and config, `includeIf` included, 2026-10-15), #331 and
      #294 (SSH through OpenSSH), #194 and #293 (signing with gpg itself), #236, #321 and #298 (hooks get the login
      shell's environment). #349 was fixed upstream, and came with a sync.
    * 2026-10-19: #120 and #124 (Merge, Commit and Continue with unstaged files, and why a button is disabled), #347's
      commit and review lists (renames), #175 (Ignore from Unstaged), #208 (Terminal on macOS), #224 (Discard all),
      #209 (clone a branch or a tag, and Choose…).
    * 2026-10-20: #309 (the System theme), #149 and #146 (file history's row menu), #278 (the log search wraps around),
      #270 (parents in the commit details), #231 and #255 (an avatar colour per person, HEAD's row), #344 (the commit
      message's title row).
    * 2026-10-21: #343, #136 and #128 (the diff above the log), #138, #139 and #140 (the diff's View menu: whitespace
      and wrapping), #206 and #283 (compare two commits, branches or tags).
    * 2026-10-22 to 2026-10-26: #136 again (fold the branches pane away), #116 (delete several branches, local and a
      remote's), #73 (squash, fix up and reword; the commit editor).
    * 2026-11-02 and 2026-11-03: #277 (the log search covers the whole history), #336's bug (Leaf no longer widens a
      sparse checkout), #260 (Amend lists the last commit's files), #345 (Unstaged's untracked files in a group),
      #207 (the log's branch filter), #242 (tab names and colours).
    * 2026-11-13: #39 (a discard can be undone).
3. Partly done:
    * #347: Staged still lists a moved file as a deletion and an addition. L: a rename status type, and every stage,
      unstage, discard and diff action keyed by path.
    * #336: no UI to set up or change the sparse set (`git sparse-checkout set`, `add`, `disable`). M.
    * #128: the font and avatar sizes, and Staged and Unstaged in one pane.
4. Still open from the triage:
    * S: #197 (`--version` and `--help`, and a path argument that selects a tab which has it), #251 (a setting that
      turns off the file watcher), #330 (the identity dialog writes `~/.gitconfig` where only an XDG config exists),
      #364 (a winget manifest: a pull request to Microsoft's repository, an outward step).
    * M: #24 (colour branches by remote), #71 (blame and history from a file picker), #98 (push
      to a new remote branch name), #105 (a repository settings screen), #164 (stage a range of lines by dragging),
      #202 (push after each commit, as a setting), #226 (credentials per repository), #230 (open in an editor), #261
      (find the repositories in the default folder), #272 (blame marks one commit's lines), #275 with #312 (search,
      select all and go to line in the diff and blame), #282 (an external diff tool, `git difftool`), #292 (an avatar
      URL template).
    * L: #30 (error messages, an umbrella), #31 (keys that can be remapped), #40 (move the panes), #199 (revert part
      of a commit), #225 with #356 (translations), #252 (Windows on arm), #329 (SHA-256 repositories, which JGit can't
      read).
    * Out of scope: #70 (Gitflow), #117 and #205 (hosting services' accounts and pull requests).
    * Untested, as they need another platform: #151 with #357 (Windows' dark title bar), #159 (scaling on Linux), #180
      (Pageant), #184 (Unicode names on Linux), #241 (Flatpak on Wayland), #311 (Shift-click in text), #333 (Sway).
5. Taking one: the working rules apply. One that changes what the user sees is proposed with options and a mockup, and
   the user picks. Changes to upstream's files stay small (rule 3): upstream may fix the same issue, and the next sync
   then has to merge both. Leaf posts nothing upstream: no pull requests, comments or reports.

## Keeping in sync with upstream

* Weekly: Fetch `upstream` and merge `upstream/main` into `main`, resolving conflicts in the fork-only code first.
  `main` is published, so merge instead of rebasing it.
* After each sync: Run the full test suite, then launch the app and smoke-test the worktree sidebar.

## Kick-off prompt

Read `docs/fork/PLAN.md`. Execute Phase 0 only. Follow the working rules. Stop and report when Phase 0 is done.
