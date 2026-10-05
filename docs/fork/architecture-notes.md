# Architecture notes: Gitnuro 2.0 and linked worktrees (Phase 0)

| | |
|---|---|
| Verified against | upstream `main` at `62442f26` (`2.0.0-beta03-4`) |
| Date | 2026-10-02 |
| Platform | macOS 26.6.2 arm64, JBR 25.0.4.1, git 2.54.0 (Apple Git-157) |
| Reproduce | `docs/fork/probes/worktree-probe.sh` |

**How to reproduce.** Every JGit result below comes from `docs/fork/probes/WorktreeProbe.java`, which calls JGit the
same way Gitnuro does, and is driven by `docs/fork/probes/worktree-probe.sh` against throwaway repos in `$TMPDIR`.
Re-run the script after a JGit upgrade or a Phase 1 fix. Line numbers are relative to `62442f26`.

Path prefixes: `A/` = `app/src/main/kotlin/com/jetpackduba/gitnuro/`,
`D/` = `domain/src/main/kotlin/com/jetpackduba/gitnuro/domain/`, `G/` = `data/src/main/kotlin/com/jetpackduba/gitnuro/data/git/`.

## Summary

| Question | Answer | Severity |
|---|---|---|
| JGit version | `7.7.0.202606012155-r`. It can read linked worktrees, but has no API that lists the worktrees of a repo. | — |
| Can Gitnuro open a linked worktree? | **No** for a sibling folder (`../wt-test`): the open fails silently. Yes, by accident, for a worktree nested inside the main working tree. **Fixed on `fix/open-linked-worktree`.** | High (blocks the whole plan) |
| Does checkout refuse a branch that is checked out in another worktree? | **No.** Both worktrees end up on the same branch. | High |
| Does branch deletion refuse a branch that is checked out in another worktree? | **No.** Deletion is always forced, has no confirmation, and leaves the other worktree on an unborn branch. | High |
| Does Gitnuro run gc or prune? | Not explicitly. JGit's auto-GC can run during merge, rebase, fetch and push. JGit GC **ignores other worktrees' HEADs, indexes, per-worktree refs and reflogs**. Objects only they reference are deleted on a later GC, once they are more than two weeks old. | Medium (rare, but data loss) |

## 1. JGit version

`gradle/libs.versions.toml:5` sets `jgit = "7.7.0.202606012155-r"`, covering `jgit-core`, `jgit-gpg` and `jgit-lfs`.

**What JGit 7.7 supports** (confirmed by `javap` on the jar and by the probe results below):
- Reading a linked worktree: `Repository.getCommonDirectory()`, `BaseRepositoryBuilder.setGitCommonDir()` and
  `setupCommonDir()`, plus `commondir` handling in `FS` and `Constants`.
- `Git.open(<linked worktree dir>)` and `Git.open(<common>/.git/worktrees/<name>)` both resolve the correct git dir,
  common dir, working tree, HEAD, branches and status (probe step 1c).

**What it lacks:** the string `worktrees` does not appear anywhere in the jar. JGit has no code that enumerates
`<common>/worktrees/*`, so no command can know which other worktrees exist or what they have checked out. This is why
worktree listing and lifecycle operations should go through the git CLI (Phase 1.1).

## 2. Opening a linked worktree

**Answer:** a sibling worktree folder cannot be opened. The failure is silent: no error dialog, only a stack trace on
stderr. A worktree nested inside the main working tree does open, by accident, through the submodule code path, and
then has knock-on problems.

### Why

The bug is in `G/repository/OpenRepositoryGitAction.kt`, which decides how to open a folder:
- `:34`: any directory whose `.git` is a **file** is treated as a submodule and sent to `openSubmoduleRepository`.
  But a linked worktree's `.git` is also a file (`gitdir: <common>/.git/worktrees/<name>`).
- `:84-92` (`getRepositoryParent`): walks up the parents looking for one that contains a `.git` **directory**.
- For a sibling worktree, no parent has a `.git` directory, so `:74` throws
  `InvalidDirectoryException("Submodule's parent repository not found")`.

That exception is never caught:
- It is thrown instead of returned as `Either.Err`, so it bypasses the error path in `D/usecases/OpenRepositoryUseCase.kt:25-35`.
- It propagates out of `A/viewmodels/RepositoryTabViewModel.kt:171-177`, whose scope is `SupervisorJob() + Dispatchers.Default`.
- Nothing in the app installs a `CoroutineExceptionHandler`, so the coroutine just dies.

A side note on the same code path: in `openRepository` (`:54-70`), `.findGitDir()` is a no-op because `setGitDir()`
was already called. The comment "scan up the file system tree" is misleading.

### Tested

Steps:
1. `git init main-repo`
2. `git worktree add ../wt-test -b test`
3. `git worktree add .claude/worktrees/agent1 -b agent1`

| Case | Result |
|---|---|
| Probe 1a: Gitnuro's open flow on `../wt-test` | `InvalidDirectoryException("Submodule's parent repository not found")` |
| Real app: `./gradlew :app:run --args="<tmp>/wt-test"` | stderr: `Exception in thread "DefaultDispatcher-worker-11" ...InvalidDirectoryException: Submodule's parent repository not found at ...OpenRepositoryGitAction$openSubmoduleRepository$2.invokeSuspend(OpenRepositoryGitAction.kt:74)`. No error dialog. |
| Probe 1b: Gitnuro's open flow on nested `.claude/worktrees/agent1` | Opens. `SubmoduleWalk.getSubmoduleRepository` builds from the work tree, and JGit resolves the `gitdir:` file and `commondir`. The tab's `repositoryPath` becomes `<common>/.git/worktrees/agent1`. |
| Probe 1c: plain `Git.open(wt-test)` | Opens correctly (see the JGit section above). |

### Knock-on problems for the nested case

These follow from the tab's path being `<common>/.git/worktrees/<name>` instead of `<worktree>/.git`. I read them
from the code; they were not exercised in the app.
- **Tab subtitle and persisted tab path** are derived by stripping `/.git` from the path
  (`A/viewmodels/RepositoryTabViewModel.kt:136-151`, `D/extensions/StringExtensions.kt:70-72`). A worktree path has
  no `/.git` suffix, so both show the internal admin path.
  - The tab *name* is the admin dir's basename, which usually equals the folder name.
  - The recent-repos entry is correct, because it uses the working-tree path from `GetWorktreeUseCase`.
- **"Open in terminal"** uses `File(repositoryPath).parentFile` (`A/terminal/OpenRepositoryInTerminalGitAction.kt:23`),
  which is `<common>/.git/worktrees`.
- **Tab replacement** in `A/ui/AppViewModel.kt:53-58` compares paths in two different forms, so it never matches.
- **The file watcher** watches `<gitdir>/refs` and `<gitdir>/modules` (`D/usecases/ObserveRepositoryToRefreshUseCase.kt:95-98`).
  For a linked worktree these don't exist (`add_watch` errors are ignored), and the common dir's `refs/`,
  `packed-refs` and `config` are never watched. Branch changes made from other worktrees or the CLI won't refresh the tab.

### Fixed (branch `fix/open-linked-worktree`)

- `OpenRepositoryGitAction` now detects a linked worktree before it considers the submodule path: the `gitdir:`
  target contains a `commondir` file.
  - It opens the worktree with `FileRepositoryBuilder().setGitDir(adminDir).setWorkTree(dir)`.
  - It returns every failure as `Either.Err` instead of throwing.
- "Open in terminal" now uses the working tree.
- Still open:
  - The watcher should also watch `<common>/refs`, `<common>/packed-refs` and `<common>/worktrees/` (Phase 1.5).
  - The tab subtitle and persisted path still show the admin dir (Phase 2b).

## 3. Checkout guard

**Answer:** no guard. JGit's `CheckoutCommand` knows nothing about other worktrees, and Gitnuro adds no check.

**Code:**
- `G/branches/CheckoutBranchGitAction.kt:12-25` is a plain `git.checkout().setName(branch.name).call()`.
- There is no check in `D/usecases/CheckoutBranchUseCase.kt` or `RepositoryOpenViewModel.checkoutBranch`.
- The only UI guard hides "Checkout" for the *current* branch (`A/ui/context_menu/BranchContextMenu.kt:26`).
  Double-click has no guard at all (`A/ui/SidePanel.kt:211`).

**Tested (probe 2):** from the main worktree, ask both tools to check out `test`, which is checked out in `wt-test`.
- git CLI: `fatal: 'test' is already used by worktree at '.../wt-test'`
- JGit: `checkout OK, HEAD now: refs/heads/test`
- `git worktree list` then shows both worktrees on `[test]`. After that, a commit in either worktree makes the other
  one's index and working tree look like they hold the reverse of that commit as uncommitted changes.

This is the Phase 1.3 checkout bug.

## 4. Branch deletion guard

**Answer:** no guard against other worktrees. Deletion is also always forced and never confirmed.

**Code:**
- `G/branches/DeleteBranchGitAction.kt:9-17` uses `setForce(true) // TODO Should it be forced?` (`:13`), so JGit's
  "not fully merged" check is bypassed as well.
- No confirmation dialog: `A/ui/SidePanel.kt:215` calls `viewModel.deleteBranch(branch)` directly.
- The UI hides "Delete" only for the current branch (`A/ui/context_menu/BranchContextMenu.kt:95`).
- JGit's `DeleteBranchCommand` refuses only the branch checked out in the repository it was opened on. Probe check:
  deleting `test` from inside `wt-test` throws `CannotDeleteCurrentBranchException`.
- The same forced delete is reused by `G/branches/DeleteLocallyRemoteBranchesGitAction.kt` and
  `G/remote_operations/DeleteRemoteBranchGitAction.kt`.

**Tested (probe 3):** from the main worktree, ask both tools to delete `agent1`, which is checked out in
`.claude/worktrees/agent1`.
- git CLI: `error: cannot delete branch 'agent1' used by worktree at '...'`
- JGit: `delete returned: [refs/heads/agent1]`
- `git worktree list` then shows `agent1` at `0000000`.
- `git status` inside that worktree reports `No commits yet`, with every file staged as new.

**Recovery is manual**, through `git reflog` in that worktree. Worse, the worktree's HEAD reflog is exactly the kind of
root that JGit GC ignores (section 5), so a later JGit GC can make the loss permanent.

## 5. gc and prune

### Explicit calls: none

There are no calls to `GarbageCollectCommand`, `Git.gc()`, `GC`, `autoGC` or `gc.*` config in the app code. Two
near misses that are not git GC:
- `G/remote_operations/FetchAllRemotesGitAction.kt:44` uses `setRemoveDeletedRefs(true)`, which is fetch `--prune`
  of remote refs.
- `A/ui/AppViewModel.kt:123` calls `System.gc()`, which is JVM garbage collection.

### Implicit calls: JGit auto-GC

In the jar, `Repository.autoGC(...)` is called from:
- `api/MergeCommand`
- `api/RebaseCommand`
- `transport/Transport`, i.e. fetch, pull and push
- `transport/ReceivePack`

Gitnuro uses all of these except `ReceivePack`. `FileRepository.autoGC` runs `GC` with `setAuto(true)`, which only does
work when a threshold is exceeded:
- `gc.auto`: default 6700 loose objects, estimated by sampling.
- `gc.autoPackLimit`: the pack count must exceed `limit + 1`; the default limit is 50.
- `gc.auto=0` disables auto-GC.

It runs in a background thread by default (`gc.autoDetach`).

### JGit GC is not worktree-aware

JGit GC treats only refs, this repository's own HEAD and this repository's own index as live roots. It never reads,
for other worktrees:
- their `HEAD`, such as a detached HEAD,
- their `index`, such as staged but uncommitted content,
- their per-worktree refs (`refs/worktree/*`, `refs/bisect/*`),
- their reflogs.

The test setup is a linked worktree with a detached-HEAD commit and a staged-only blob, i.e. objects that only that
worktree references.

| Probe | git CLI | JGit |
|---|---|---|
| 4a. gc with prune expiry "now", from the main worktree | `git gc --prune=now`: both objects kept | `gc().setExpire(now)`: **both deleted**. `git fsck` in the worktree: `HEAD: invalid sha1 pointer`, `missing blob`. |
| 4b. Default settings, all objects packed and aged 21 days | — | 1st GC: kept. JGit repacks only what it thinks is reachable and **loosens the rest with a fresh mtime**. |
| 4b, continued: loosened objects aged 15 days, then GC again | — | 2nd GC: **both deleted** (older than the default `gc.pruneExpire` of 2 weeks). |

**What this means in practice:**
- Commits on branches are safe, because `refs/heads/*` is shared.
- At risk: anything reachable only from another worktree's detached HEAD, its index, its per-worktree refs, or its
  reflog. That includes the branch-deletion case in section 4, a rebase in progress in another worktree, and
  `git checkout --detach` sessions.
- The risk needs two JGit GC runs, at least two weeks apart, triggered from a Gitnuro tab on the same repository. The
  chance is low, but the loss is silent and permanent.

**Decision (1.0, branch `fix/jgit-autogc`):** the option chosen is none of (a)–(c) below. A JGit
`SystemReader.Delegate` (`G/NoAutoGcSystemReader.kt`, installed in `A/main.kt`) injects `gc.auto=0` and
`gc.autoPackLimit=0` underneath every config file.
- Because the values are injected in memory and are never part of any file's own content, JGit's config reloads don't
  lose them.
- Explicit settings still win, and nothing is written to disk.
- Covered by `NoAutoGcSystemReaderTest`.

**Options considered:**
- **(a)** When the repository has linked worktrees, disable auto-GC for Gitnuro's JGit instances by overriding
  `gc.auto` / `gc.autoPackLimit` in the in-memory config only. This is fragile: `FileRepository.getConfig()` reloads
  the config when the file changes.
- **(b)** Prevent the commands Gitnuro calls from triggering auto-GC. Needs investigation: `autoGC` is called
  unconditionally at the end of `MergeCommand` and `RebaseCommand`.
- **(c)** Document the risk and leave gc to the git CLI. Never write `gc.auto` into the user's config without asking.

## Other findings relevant to later phases

### Repository and branches

- **Active development line.**
  - The 2.0 rewrite is on `upstream/main`. `2.0.0-beta03` is an ancestor of it, and HEAD is `2.0.0-beta03-4-g62442f26`.
  - The other upstream branches are stale: `devel_extend_terminal_button` and `devel_multifile_selection` (2024-12),
    `persist_graph_padding` (2023-11), and `re2` (one unmerged commit from 2026-06-03, "Added progress logs to push").
- **Local branches.** `fork/main` was created from `upstream/main` with no tracking set. Local `main` already tracks
  `upstream/main`.

### State, refresh and concurrency

- **Tab persistence drops tabs.**
  - Only tabs in the `Open` state are persisted (`A/ui/AppViewModel.kt:126-135`), and restored tabs open lazily, only
    when selected. So closing the app forgets tabs that were never visited. Observed: one dev run wiped a
    two-entry `latestRepositoriesTabsOpened`.
  - `persistTabSelected` (`:88-90`) writes an index into the unfiltered tab list, while `:134` writes an index into
    the filtered one.
- **No duplicate-tab focusing.** Opening the same path twice creates two tabs, each with its own watcher, sharing one
  cached `Git`. This is relevant to Phase 2a.
- **No polling and no refresh on window focus.** File watching is the only automatic refresh (Phase 1.5).
- **#335 (UI freezes), a hypothesis I have not tested.**
  - Each opened tab runs the Rust `FileWatcher.watch()` blocking loop inside a `callbackFlow` on `Dispatchers.Default`
    (`G/FileChangesWatcher.kt`, `D/usecases/ObserveRepositoryToRefreshUseCase.kt:42-47`). That ties up one Default
    thread per tab, and the view models and refreshes share the same pool.
  - Each working-tree change refreshes STATUS, LOG and REPO_STATE, and the log refresh walks up to 2000 commits.
  - Overlapping refreshes are neither merged nor cancelled.
- **No serialisation of git operations.** There is no mutex around them, and the `JGit` cache is a plain
  `mutableMapOf` (`G/JGit.kt:18`).

### Bugs to fix or report upstream (Phase 5)

All verified in source.
- `A/ui/dialogs/settings/SettingsDialog.kt:297`: the proxy **Login** field saves `AppConfig.ProxyHostPassword`.
- `A/ui/dialogs/settings/SettingsDialog.kt:406`: the "Do not verify SSL" toggle saves `AppConfig.CacheCredentialsInMemory`.
- `D/usecases/ObserveRepositoryToRefreshUseCase.kt:74`: `startsWith(repositoryPath)` without a separator also matches
  `.gitignore`, `.gitattributes` and `.github/`, so editing them triggers a full refresh.
- `D/usecases/ObserveRepositoryToRefreshUseCase.kt:113`: strips the git-dir prefix instead of the working-tree prefix,
  so that ignore check never matches.
- `A/repositoryopen/RepositoryOpenViewModel.kt:1037-1046`: `openSubmodule` builds `"$repositoryPath/$path"` from
  the git dir, which gives `/repo/.git/<sub>`. It should use the working tree, and its own TODO says so.
- `app/build.gradle.kts`: Rust build failures are ignored (`isIgnoreExitValue = true`).
- `app/build.gradle.kts` (from upstream `a9a0f318`): inside `macOS { }`, `bundleID = packageName` read the DSL's null
  `packageName`, and `sign.set(true)` blocked the `compose.desktop.mac.sign` opt-out. In the fork, the Leaf rename's
  literal bundle ID fixes the first and `fix/macos-bundle-id` fixes the second. Both are candidates for an upstream PR.
- `D/TempFilesManager.kt` (`AppFilesManager`): the macOS app folder is `~/Library/Application/gitnuro`, which is
  missing "Support". The fork fixed it as part of `feature/leaf-own-storage`.
- `DEVELOPMENT.md` is outdated: it says JDK 17+ (25 is needed) and that `cargo-kotars` is required (uniffi is used now).
  It also doesn't mention Git LFS, which the fonts need.
