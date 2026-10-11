# Switching worktrees in a tab (Phase 2b)

| | |
|---|---|
| Question | How can a tab stand for a repository and switch between its worktrees the way a checkout switches branches? |
| Verified against | `main` at `4bb714d7`; JGit 7.7.0 |
| Date | 2026-10-09 |
| Status | Done, approved 2026-10-10 with every recommendation in section 5 |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

## Summary

- **Recommendation: switch in place.** The tab keeps its Dagger component and view models. Only the git dir it points
  at changes (`RepositorySelectionState.Open.path`). Then the file watcher starts again and everything is refreshed.
  There is no new scope, and the tab's state isn't split.
- **Why that works:** every git action and every refresh reads the git dir from the selection state when it runs
  (`D/UseCaseExecutor.executeTask`), and none keeps it. Switching the path re-targets them all.
- **What holds on to the worktree** and has to change (section 3):
  - the file watcher, which can't be restarted;
  - refreshes already running, which would write the old worktree's data;
  - the commit message, which is saved a second after typing, to whichever worktree is open by then;
  - some view state: the message field, the open diff, blame and file history;
  - the tab's name, saved path and JGit cache.
- **UI:**
  - Double-clicking a worktree, or a branch that another worktree has, switches the current tab. GitKraken does this
    (from 10.5), and so does Tower.
  - "Open in new tab" keeps Phase 2a's behaviour, as GitKraken's "Open worktree in a new tab" does.
  - A worktree picker in the toolbar shows the tab's worktree, and the tab is named `repository · worktree`.

## 1. What a tab holds today

| Piece | Where | Per worktree? |
|---|---|---|
| Git dir | `RepositorySelectionState.Open.path` in `DA/repositories/InMemoryRepositoryDataRepository` | The tab's worktree. Read per call, never kept. |
| Status, current branch, repository state (merging, rebasing), interactive rebase, draft commit messages, submodules | The same repository's flows | Yes |
| Local branches, tags, remotes, stashes, ref dates, worktree list | The same | Shared, the same in every worktree |
| Log | The same | The commits are shared, but the log marks HEAD |
| File watcher | `DA/git/FileChangesWatcher` (`@TabScope`), started by `D/usecases/ObserveRepositoryToRefreshUseCase` | Yes: it watches the working tree and the git dir |
| Draft commit message | `<git dir>/COMMIT_EDITMSG` and `MERGE_MSG` | Yes |
| View state | `A/repositoryopen/RepositoryOpenViewModel`, `StatusViewModelExtender` | Partly (section 3.4) |
| Tab name, tooltip, saved path | `A/viewmodels/RepositoryTabViewModel`, `A/ui/AppViewModel` | Yes |

Things that would break with a plain path change:

- **File watcher:** `ObserveRepositoryToRefreshUseCase` reads the paths once, in a job in the tab's scope. When the job
  ends, it calls `FileChangesWatcher.close()`, and that destroys the Rust `FileWatcher` (uniffi's `close()` is
  `destroy()`). The wrapper creates the Rust object once, in its constructor, so it can't watch again.
- **Refreshes:** `RefreshDataUseCase` launches each refresh in the tab's scope. A status refresh that starts before a
  switch and ends after it would show worktree A's changes in B.
- **Commit message:**
  - `StatusViewModelExtender.persistMessage` saves a typed message after 1 s, through `PersistCommitMessageUseCase`,
    which reads the git dir when it runs. A switch within that second would write A's message into B's
    `COMMIT_EDITMSG`.
  - The field only takes the saved message while it's empty (`persistedCommitMessageFlow`), so B's message wouldn't
    replace A's.
- **Saving tabs:** `App.kt` saves the tab list when the selected tab or its name changes
  (`LaunchedEffect(currentTab, tabName)`). A switch that keeps the name would leave the old path saved.

## 2. Options

**A. Switch in place (recommended).** It's the closest to a checkout:
- no loading screen;
- the log, its scroll position, the selected commit, the side panel's sections and its filter stay;
- HEAD's mark moves once the log refreshes, and the status pane shows the other worktree's changes.

The cost is section 3: a handful of places that keep the worktree have to follow the switch.

**B. Replace the tab's contents.** A new `TabComponent` takes the tab's place, as "Open another repository" does
(`AppViewModel.replaceTab`).
- For: simple, and nothing can be stale.
- Against: the tab goes through the loading screen and loses its scroll position, selection, open sections and filter.
  It feels like opening another repository, not like a checkout.

**C. Split the tab into a repository part and a worktree part** (a Dagger subcomponent per worktree under the tab).
Rejected:
- Most of what looks shared isn't: the log marks HEAD, the branch list marks the current branch, and submodules belong
  to each worktree.
- It means splitting `RepositoryDataRepository` and the 1,462-line `RepositoryOpenViewModel`, which are upstream code.
- All it saves is reloading tags, remotes and stashes.

## 3. Design (option A)

### 3.1 The switch

`SwitchWorktreeUseCase(folder)`, new and fork-only, does the following:

1. Opens the folder with `IOpenRepositoryGitAction`, which returns its git dir. On failure, the tab stays where it
   was and `ErrorDialog` shows the error. The tab's own worktree changes nothing.
2. Stops the file watcher (3.3).
3. Sets the worktree's data (status, current branch, repository state, interactive rebase, draft messages,
   submodules) to `Loading`, then the state to `Open(newGitDir)`. The shared data stays on screen until the refresh
   replaces it.
4. Marks the new worktree as the tab's in the worktree list it has (`withCurrentWorktree`). Otherwise the picker, the
   Worktrees section and the branch badges would show the old one until the refresh had run git in every worktree,
   which it does last.
5. Starts the file watcher on the new worktree, and refreshes everything (`DataToRefresh.ALL`).
6. Waits until the status and the repository state are loaded, 10 s at most. `toUiDataState` keeps showing the old
   worktree's data while the new one loads, so the status pane would show the old files and merge or rebase buttons,
   and an action on them would run in the new worktree. Waiting keeps input blocked meanwhile.
7. Doesn't add anything to the recent repositories. Phase 2a already keeps linked worktrees out, and the main one is
   there already.

The switch runs as a foreground task (`TaskType.SwitchWorktree`), so the processing screen shows if it takes more
than 300 ms, as with a checkout. It shows no toast: the picker and the tab name already say where you are.

### 3.2 Stale results

- Each `RepositoryDataRepository.update…` gets the git dir its block reads, and drops the result if the tab no longer
  shows that worktree, before the block runs or after. The check and the write happen under the same lock as
  `switchWorktree`, so no result of the old worktree lands after a switch.
- `StatusViewModelExtender` notes the git dir when the message is typed, and `PersistCommitMessageUseCase` gets it as
  a parameter instead of reading it when it runs. A switch leaves a waiting save to finish there: neither the next
  message nor a commit in the new worktree cancels it.

### 3.3 File watcher

- `FileChangesWatcher` creates a new Rust `FileWatcher` for each watch, and destroys it when the watch ends. That's
  a Kotlin-only change: Rust's `init()` already builds a fresh notify watcher and channel each time.
- `ObserveRepositoryToRefreshUseCase` keeps its job. `stop()` cancels it and waits for it to end. The Rust loop checks
  every 500 ms (`WATCH_TIMEOUT`) whether to stop.
- Closing the tab still ends it through the tab's scope.

### 3.4 View state

When the state goes from `Open(a)` to `Open(b)`, the view models reset what belongs to the worktree:

- **Commit message:** the field and the merge message take B's saved messages, even when the field isn't empty: the
  view model starts reading them over after each switch. Amend is turned off.
- **Closed:** the open diff of a changed file, blame, and file history.
- **Cleared:** the Staged and Unstaged folders' collapsed state and the selected files.

These stay:
- the selected commit; "Uncommitted changes" now shows B's;
- the log's scroll position and search;
- the side panel's sections, filter and folders;
- every setting.

`worktreeSwitches()` (`RepositorySelectionState.kt`) turns the selection state into the git dirs switched to, which
the view models collect. A view model then doesn't have to tell a first open from a switch.

### 3.5 The tab

- **Name:** `<repository> · <worktree>` for a linked worktree, and just `<repository>` for the main one. The worktree
  part is lighter. `<repository>` is the main worktree's folder (the common git dir's parent, or a bare repository's
  folder without `.git`). The window title follows the name, and the tooltip stays the worktree's full path.
- **Saving:** the tab list is saved after every switch. The saved path is the worktree's git dir, so the format
  doesn't change.
- **Restoring:** if a linked worktree's git dir no longer opens (an agent removed it while Leaf was closed), the tab
  opens its repository instead of showing an error. Git's layout is fixed: a linked worktree's git dir is
  `<common git dir>/worktrees/<id>`.
- **JGit cache:** after a switch, the old git dir's `Git` is closed unless another tab has it open
  (`CleanRepositoriesResourcesUseCase`, which ran only when a tab closed). `AppViewModel.worktreeSwitched` does this
  and the saving.

### 3.6 Entry points

| Where | Phase 2a | Phase 2b |
|---|---|---|
| Double-click on a Worktrees row | Selects the tab that has it, or opens a new one | Switches this tab |
| Double-click on a branch that another worktree has (side panel and log) | The same | Switches this tab |
| "Switch to worktree" in a worktree's and a branch's menus | The same | Switches this tab |
| "Switch to worktree" in the checkout error | The same | Switches this tab |
| "Open in new tab" in a worktree's menu (new) | — | Phase 2a's behaviour: selects the tab that has it, or opens a new one |
| Worktree picker in the toolbar (new) | — | Switches this tab |
| Welcome page, command line, the toolbar's Open | Opens or replaces a tab | Unchanged |

Switching in place happens even when another tab already shows that worktree, so that a double-click always does
the same thing. "Open in new tab" goes to that other tab instead.

`AppViewModel.selectOrOpenTab` and `indexOfRepository` stay, for "Open in new tab".

### 3.7 The picker

- **Where:** in the toolbar, right of Open.
- **Button:** the worktree's folder name, with its branch below and a chevron, shown only when the repository has
  linked worktrees.
- **List:** one row per worktree, with the content of the Worktrees section's rows. The tab's worktree is checked.
  Missing worktrees and a bare repository's main worktree are greyed out (`Worktree.canSwitchTo`).

Section 5 lists the alternatives.

## 4. Out of scope

- **Removing the tab's own worktree while it's open,** for example by an agent. Today its refreshes fail.
  - Phase 3's "Remove worktree" will switch the tab to the main worktree before removing it.
  - Leaf could also switch by itself when the worktree list stops listing the tab's worktree. That's left for
    Phase 3.
- **One tab per repository:** the Welcome page and the command line selecting the repository's existing tab.
- **A keyboard shortcut for the picker.**

## 5. Decisions to make

1. **Approach:** A, switch in place (recommended), or B, replace the tab's contents.
2. **Where the picker goes:**
   - the toolbar, right of Open (recommended);
   - a chevron inside the tab;
   - no picker, with the Worktrees section as the only way to switch.
3. **Tab name:**
   - `Leaf · fix-auth` (recommended);
   - `Leaf` only, with the worktree in the picker;
   - `fix-auth` only, as in Phase 2a.
4. **Picker visibility:** only when the repository has linked worktrees (recommended), or always.

## 6. Tests and commits

Tests:

- **Domain:** `SwitchWorktreeUseCase` with fakes. It checks the order (stop the watcher, set the state, refresh,
  watch), that a failed open changes nothing, and that it leaves recent repositories alone.
- **Data:**
  - `handleDataState` drops a result when the git dir changed while it loaded.
  - On a temp repository with two worktrees: after a switch, the status is B's, and a refresh that started before
    the switch is dropped.
  - The watcher sees B's file changes and not A's.
  - Restoring a tab whose worktree was removed opens its repository.
- **App:** the picker's rows and greyed-out entries, the tab name, the saved path after a switch, and the reset view
  state. Offscreen renders of the picker in light and dark.
- **Manual run** on a demo repository with agent-style worktrees.

Commits on `feat/worktree-switching`:

1. A restartable file watcher and the stale-result guard, with no visible change.
2. Switching in place, wired to the entry points, with the view state reset.
3. The picker and the tab names.
4. Fork docs: CHANGELOG, CLAUDE.md, and this note marked as done.
