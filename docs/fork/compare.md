# Comparing two commits or branches (upstream issues 206 and 283)

| | |
|---|---|
| Question | How can Leaf show the difference between any two commits or branches, in a way people find without being told? |
| Verified against | `main` at `04e3f620`; JGit 7.7.0; git 2.54 |
| Date | 2026-10-21 |
| Status | Approved 2026-10-21: entry points, default modes, the A/B labels and layout 1c. Steps 1 to 6 done (2026-10-22). Step 7, the later items, chosen 2026-10-22: 7.4, 7.3 and 7.1 built |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

**Mockups:** the Claude Design project "Leaf compare commits and branches", file `Compare Commits.dc.html`. Ids used
below: the layouts 1a, 1b and 1c, and the features F1 to F7. They use Leaf's dark theme, at a 1180 px window.

## Summary

- **Today:** a commit shows its diff against its first parent only. The one comparison of two refs is a worktree
  row's "Review changes" (Phase 3.3), which compares the worktree's branch with the base branch, three-dot only. Upstream
  issue 206 ("see the diff between two commits") was closed as a duplicate of 283 ("Ctrl-click two commits in the
  graph"), which is open.
- **Most of it exists:** `GetBranchComparisonGitAction` finds the merge base, the commits on each side and the changed
  files with renames, and retries for partial clones. The diff pane reads both sides of a `DiffType.CommitDiff` by blob
  id, so a file from any comparison opens with no change there. The review's view model already keeps a comparison
  next to the selection, re-reads it when a branch moves, and closes with Esc.
- **Starting one (approved):** ⌘-click (Ctrl-click on Windows and Linux) a second commit in the log, as GitKraken,
  Fork, SourceTree, Tower and Sublime Merge do. Also right-click items, so it can be found: "Compare with selected
  (5d5537f)" on a commit, with the ⌘-click hint, and "Compare with main (current)" on commits, branches and tags.
- **Two modes (approved):** "Since they split" (`git diff A...B`, what merging B would bring) and "All differences"
  (`git diff A B`). The switch shows only when the two have diverged. It starts on "Since they split" when both sides
  are branches, and on "All differences" otherwise.
- **Labels (approved):** the sides are A and B, with an amber and a light blue badge, in the log, the pane, the full
  view's header and above each side of the diff.
- **Layout (approved, 1c):** the comparison opens in the right pane next to the log, where the two picks stay in
  sight. Opening a file switches to a full view that takes the whole tab, as file history and the conflict resolver do,
  so the diff gets about 850 px instead of 640. Esc goes back a step.
- **The worktree review becomes one use of the same pane** (F7), and gains the full view, the swap and the switch.

## 1. What Leaf has today

| Piece | Where | Reused? |
|---|---|---|
| A commit's files, against its first parent | `DA/git/diff/GetCommitDiffEntriesGitAction` | No change |
| Branch against base: merge base, commits both ways, files with renames | `DA/git/worktrees/GetBranchComparisonGitAction`, model `D/models/BranchComparison` | Generalised (step 1) |
| Renames in file lists, with a 2 s budget | `DA/git/diff/RenameDetection.kt` (`diffWithRenames`) | As it is |
| Partial clones: download what a read misses, then retry | `DA/git/cli/remote/PartialCloneFetch` (`retryingMissing`) | As it is |
| The review's state, next to `SelectedItem`, re-read when a branch moves | `A/repositoryopen/WorktreeReviewViewModelExtender` | Generalised (step 2) |
| The review pane | `A/ui/WorktreeReview.kt` | Generalised (step 3) |
| The diff of one file, any two blobs | `A/ui/diff/DiffPane.kt` with `DiffType.CommitDiff` | Hosted in the full view (step 4) |
| A view that takes the whole tab | `RepoContent` in `A/repositoryopen/RepositoryOpen.kt` (file history, the conflict resolver) | One more branch (step 4) |
| Shift and Ctrl clicks | `StatusPane` reads `LocalWindowInfo.current.keyboardModifiers` | Same in the log (step 5) |

`GetBranchComparisonGitAction` lists only the branch's commits (`base..branch`) and counts the others; it needs both
lists. It returns no changes when the two have no commit in common; "All differences" needs the trees compared then.

**How other clients do it:** GitKraken, Fork, SourceTree, Tower and Sublime Merge select two commits with ⌘-click or
Ctrl-click and show what differs. IntelliJ has "Compare with Current" in its branch menu. GitHub's compare page is
three-dot. GitHub Desktop compares branches in a Compare tab.

## 2. Decisions

1. **Entry points:** ⌘-click and the right-click items in the first version. A "Compare with…" picker for any branch,
   tag or hash comes later (section 6). Approved 2026-10-21.
2. **Default mode:** "Since they split" when both sides are branches, "All differences" otherwise. Approved 2026-10-21.
3. **Labels:** A and B, not From and To. Approved 2026-10-21.
4. **Layout:** 1c (summary next to the log, full view for files) over 1a (in place, the diff over the log, 640 px) and
   1b (the whole tab straight from ⌘-click, which hides the picks at once). Approved 2026-10-21.
5. **The later items (step 7):** option a of each, from `Compare Step 7.dc.html`: 7.1a (change a side in the pane's
   header, "Compare with…" in the menus, "Compare…" in the Actions list), 7.2a (the working tree as B, untracked files
   included and tagged), 7.3a (⌘-click a second row in file history), 7.4a (a commit's click lists its own changes).
   Built in that order from the smallest: 7.4, 7.3, 7.1, 7.2. 7.2c and 7.3b stay later items (section 6). Chosen
   2026-10-22.

## 3. Behaviour

### 3.1 The two sides

- **A side is a branch or a commit** (`CompareSide`). A branch follows its branch: the comparison is read again when
  it moves, as the review does when an agent commits. A commit stays where it is.
- **⌘-click on a row** takes the row's branch when the commit is a branch's tip, otherwise the commit. With several
  branches at one commit, the one the log's chips put first (`D/log/LogRefs.kt`, `logRefs`): the current branch, local
  branches that another worktree uses, other local branches, then remote branches. A tag at the commit doesn't make it
  a branch: the side is the commit, named by the tag where it has one.
- **A menu item** takes what it names: the branch or tag it was opened on, and the current branch (or `HEAD` when
  detached).
- **Which is A:** the ancestor, when one contains the other, however the comparison started: the changes then read
  forwards, from the older snapshot to the newer, as "what changed since v1.0" does. Otherwise ⌘-click and "Compare with
  selected" put the older commit first by commit date (`inPickedOrder`), and "Compare with main (current)" puts the
  current branch first, so a branch's comparison reads as what merging it into main would bring.
  - Dates are known before git compares the two, ancestry only after. So a comparison whose B turns out to be A's
    ancestor (dates set by hand or by a wrong clock, or a menu's current branch that is newer) swaps once, as it starts
    (`putsAncestorFirst`). After that only the swap button changes the order.
- **Names:** a branch side shows the branch's short name, a commit side its short hash. Both show the short hash next to
  the name, and the full view adds the subject.

### 3.2 Starting a comparison (F1, F2)

- **⌘-click** a commit while another is selected (F1). macOS reads Meta, the others Ctrl: on macOS Ctrl-click is the
  right-click. With nothing selected, or the uncommitted changes selected, ⌘-click selects as a plain click does.
- **Right-click a commit** (F2), in a new group after "Copy commit hash":
  - "Compare with selected (5d5537f)", with "⌘-click" (or "Ctrl-click") at the right. Shown when another commit is
    selected, and not on the selected commit itself.
  - "Compare with main (current)". Not on the current branch's tip.
- **Right-click a branch**, in the side panel and on a log chip: "Compare with main (current)", after Merge and Rebase.
  Not on the current branch. With a detached HEAD: "Compare with HEAD (detached)". The shared chip's remote part
  (`sharedChipRemoteItems`) gets the same item for the remote branch.
- **Right-click a tag**: "Compare with main (current)".
- **"Review changes"** in a worktree row's menu: A is the base, B the worktree's branch (F7).

### 3.3 The log while comparing (F1)

- Both rows are highlighted as selected, with a 16 dp badge before the chips: A amber, B light blue, a near-black
  letter. A badge's tooltip names the side and says that ⌘-click drops it. A row that is both a branch's tip and a side
  shows the badge first.
- The log scrolls to neither row when the comparison starts (the user just clicked them). After a swap, the badges
  swap. A branch side that moves moves its badge to the new tip.

| Action | Result |
|---|---|
| Click a row | Ends the comparison and selects that commit, as a click does today |
| ⌘-click a third commit | It replaces B |
| ⌘-click A or B | Drops that side; the other stays selected, as one commit |
| ↑ ↓ and the other log keys | As a plain click: end the comparison and move from A |
| Esc | Ends the comparison; A stays selected |
| ⌘-click the uncommitted changes row | Nothing yet (section 6) |
| ⌘-click a stash row | Compares like a commit: a stash is the snapshot it saved |
| Select anything in the side panel | Ends the comparison, as it ends the review today |

### 3.4 The comparison pane (F3)

The right pane, in place of the commit's files. Top to bottom:

- **Header:** A's and B's names and short hashes on two lines, then three buttons: swap, open the full view, end the
  comparison (Esc).
- **The switch** ("Since they split" | "All differences") and one line that says what the mode shows, with names:
  "What feat/clone-picker changed since it branched off main at e3c6632. main's 4 newer commits are left out." Only
  when the two have diverged.
- **Instead of the switch:**
  - when A is B's ancestor: "B has 3 commits that A lacks, and A has none of its own, so both ways of comparing give
    the same files." The commits section is then "Commits from A to B", and there is no "Commits only in A";
  - when B is A's ancestor, which only a swap leads to: "A has 3 commits that B lacks, and B has none of its own. The
    files show them as if B had undone them." All differences, as since they split would list nothing;
  - when they share no commit: "main and gh-pages share no commit, so this shows every difference between them." The
    mode is "All differences".
- **Commits only in B**, open, oldest first, at most `MAX_COMPARED_COMMITS` (1,000) with "N more" past it.
  **Commits only in A**, closed. A commit row's menu: "Select in the log", "Copy commit hash".
- **Files changed:** `ChangedFilesList`, with its views, search, sort, the split columns and rename hints, as in a
  commit. A click opens the full view at that file (3.5).
- **Loading:** a progress bar under the header. A comparison that is read again keeps the last result on screen.
- **Failed:** the error in place, in the dialog's red box, with Try again, as the diff pane shows a missing object. It
  stays until the user acts (no toast).
- **The review's line**, for F7 only: "agent1 has 2 uncommitted changes, which aren't in this comparison." with the
  link to switch, as today.

### 3.5 The full view (F4, layout 1c)

- **Opens** with a click on a file in the pane, or the pane's full-view button (at the file shown, else the first). It
  takes the whole tab, as file history does: `RepoContent` shows it in place of `MainContentView`. The arrow keys in
  the pane's file list show a file's diff over the log, as a commit's list does; Enter opens nothing (not built).
- **Header:** A and B with name, short hash and subject (the subjects give way first), then the switch, swap, back to
  the log (Esc) and end the comparison. Under it, the mode's line or the note.
- **Left column**, the right pane's width: the commits sections and the files, the same composables as the pane,
  without its header. A file's click shows its diff.
- **Right:** `DiffPane`, as it is, without its close button, with "‹ 2 of 5 ›" in its header and one row of A and B
  above the lines: over the two sides of a split diff, or "A main · 5d5537f → B feat/clone-picker · 7d03f51" for a
  unified one. Unified, split, wrapping and the View menu's whitespace options follow the diff pane's own settings.
  Without a file shown: "Select a file to see what changed in it."
- **Keys:** ↑ ↓ move through the files (the view's own `onKeyEvent`, as file history's; the file list takes them itself
  while it has the focus). Esc or the back button returns to the log with the comparison in the pane, without the diff,
  and gives the focus back to the tab.

### 3.6 The two modes (F5)

- **Since they split:** from the merge base's tree to B's tree, as the review does now. With several merge bases, the
  first JGit finds, as git takes one.
- **All differences:** from A's tree to B's tree. Whatever A did since they split shows as if B had undone it: main's
  release commits show "1.3.0" removed and "1.2.0" added in `latest.json`.
- **Default:** "Since they split" when both sides are branches (⌘-click on two branch tips, a branch menu, the review),
  "All differences" otherwise (two commits, a commit or a tag against the current branch).
- **When it shows:** only for diverged sides (`showsModeSwitch`). When A is B's ancestor, the merge base is A and both
  modes list the same files. When B is A's ancestor, or the histories share no commit, "Since they split" would list
  nothing (git refuses `A...B` without a merge base), so the comparison shows "All differences" (`modeToShow`).
- **The git action does what it's asked**, as git does: the review asks for "Since they split" and gets nothing for a
  branch that main already has, which is the review's answer. A comparison's view model asks for the mode it means to
  show, and asks again in the rare case that the relation it learns calls for the other one.
- The switch is per comparison: not saved, kept by a swap, reset when a new comparison starts.

### 3.7 Swap (F6)

The swap button exchanges A and B. In "Since they split" it shows the other side's own work. In "All differences" every
addition becomes a deletion. The commits sections swap, the log's badges swap, the mode stays.

### 3.8 How long it lasts

- Ends with: a plain click in the log, Esc in the pane, the close button, selecting something in the side panel,
  switching the tab's worktree, closing or replacing the tab. Not saved across restarts.
- A branch side that is deleted or renamed outside Leaf: the comparison shows the error ("feat/clone-picker doesn't
  exist.", as the review shows it) with Try again, until the user ends it. Not a silent close.

### 3.9 Refresh

As the review: the comparison is read again when the commit of a branch side changes in the tab's branch data (the file
watcher refreshes it when an agent commits). Commit sides never change. A read that finds the same commits changes
nothing on screen, and the last result stays while the next loads.

### 3.10 Limits

- At most 1,000 commits listed per side, with the full counts.
- The file list has no cap; `ChangedFilesList` is a `LazyColumn`. Rename detection stops after 2 s and keeps what it
  paired (`MAX_RENAME_DETECTION_TIME`).
- Partial clones: the list downloads nothing (`readableBlobs`); a file's diff downloads its two blobs, as for a commit.

## 4. Implementation

Seven steps, in order: 2 needs 1, and 3, 4 and 5 need 2. Step 7 came after the first version shipped. Each step builds
and passes its tests.

### Step 1: compare any two revisions (data and domain)

- `D/models/BranchComparison.kt` → `D/models/Comparison.kt`: `Comparison(a, b, mode, relation, mergeBase,
  commitsOnlyInB, countOnlyInB, commitsOnlyInA, countOnlyInA, changes)`. `ComparisonMode { SINCE_SPLIT, ALL }`.
  `ComparisonRelation { SAME, A_IS_ANCESTOR, B_IS_ANCESTOR, DIVERGED, UNRELATED }`.
- `D/interfaces/IGetComparisonGitAction`: `(repositoryPath, a: String, b: String, mode) -> Either<Comparison,
  GitError>`, where a side is a full ref or a commit id. `DA/git/compare/GetComparisonGitAction`, moved from
  `DA/git/worktrees/GetBranchComparisonGitAction`: both commit lists, the relation, and the trees of the mode asked for.
  Bound in place of `IGetBranchComparisonGitAction` in `WorktreeGitActionsModule`. `GetComparisonUseCase` replaces
  `GetBranchComparisonUseCase`. The review and the merge dialog ask for "Since they split".
- `D/compare/CompareRules.kt` (pure): `CompareSide` (`Branch`, `Commit`), the side for a log row (`compareSideOf`, in
  `logRefs` order, a detached HEAD left out), `inPickedOrder`, `putsAncestorFirst`, `defaultMode`, `showsModeSwitch`,
  `modeToShow` and `comparisonRelation`.
- **Tests:** `GetComparisonGitActionTest` on temp repositories (linear, diverged, criss-cross, unrelated, the same
  commit, a moved file, a branch against a tag, a missing ref), each against git: `git diff --name-status -M50% A B` and
  `A...B`, `git rev-list A..B` and `B..A`, `git merge-base --all`. A partial clone against `PartialCloneTest`'s setup.
  `CompareRulesTest` (domain).

### Step 2: the comparison's state (view model)

- `A/repositoryopen/WorktreeReviewViewModelExtender` → `ComparisonViewModelExtender`, which `RepositoryOpenViewModel`
  creates and exposes as `comparisonState`. `ComparisonTarget(a, b, reviewedWorktreePath)`: two `CompareSide`s, and
  the worktree for "Review changes" (F7). Started by `comparePicked(PickedSide, PickedSide)` (⌘-click and "Compare with
  selected"; a `PickedSide` is a side and its commit's date), `compareWithCurrent(current, other)` (the menus) and
  `openReview(worktree, base)`; `pick(PickedSide)` is ⌘-click while comparing.
- `ComparisonState`: the target, the mode shown, loading, the comparison (the last one of the same sides and mode
  stays while it's read again), the error, the commits that A and B are at now (`aCommit`, `bCommit`, where the log
  draws the badges), the file rows, `isFullView` and the reviewed worktree (`review`). `showsModeSwitch` comes from the
  comparison's relation.
- Order and mode (3.1, 3.6): a new comparison's first read settles it. When git says that B is A's ancestor, the sides
  turn around and are read again; when the relation calls for the other mode (`modeToShow`), that mode is read. That
  first result never shows. The review keeps its order and asks for "Since they split" whatever the relation. A swap
  flips the known relation (`ComparisonRelation.swapped`), so it reads the right mode at once.
- `ComparisonAction`: `SelectEntry`, `OpenFullView(entry)` (the file clicked, else the one shown, else the first),
  `LeaveFullView`, `SetMode`, `Swap`, `ViewStateChanged`, `TreeDirectoryToggle`, `TryAgain`, `End` and
  `ShowUncommittedChanges` (the review's link).
- Ending (3.8): a comparison selects A at the commit it is at now (`selectionFor`, which looks the commit up in the
  loaded log, a stash as a stash); the review selects again what was selected before it. Selecting anything else, and
  a switch of the tab's worktree, end it without selecting.
- `D/repositories/CloseableView`: `WORKTREE_REVIEW` → `COMPARISON`, and `COMPARISON_FULL_VIEW` while the full view
  shows. Esc closes the last closeable view, so it leaves the full view first, then ends the comparison.
- The pane is still `WorktreeReview.kt`, reading `ComparisonState`, until step 3. Nothing starts a comparison of two
  commits yet: the log and the menus come in step 5.
- **Tests:** `ComparisonViewModelExtenderTest` (24) with a fake git action: the review's cases from before, the
  ordering, an ancestor turned first (and never shown the wrong way round), the default modes, "Since they split" that
  would list nothing, the switch, a swap (onto an ancestor too), the current branch first, the same commit twice,
  ⌘-click on A, on B and elsewhere, ending on a branch side that moved, the full view and Esc twice, Try again.

### Step 3: the pane (UI)

- `A/ui/WorktreeReview.kt` → `A/ui/compare/ComparisonPane.kt` (`ComparisonPane`, and `ComparisonPaneView`, which takes
  the state, for tests). Top to bottom:
  - the header: A and B on two lines (`SideLine`: badge, name, and the commit's short id unless the name is it), read
    as one by screen readers, then swap (`compare_arrows` turned upright) and end;
  - the switch (`ModeSwitch`, two tabs; the shown one sends nothing) and its line, or the note of 3.4, by relation and
    mode: the review's own when it asks for "Since they split" of a branch that main has, or of unrelated histories;
  - "Commits only in B" (or "Commits from A to B") open and "Commits only in A" closed, each opening and closing with
    its header, a side without commits left out; at most 40% of the pane's height each; a commit's menu is the commit
    details' parents' (`parentContextMenu`: "Select in the log", "Copy commit hash");
  - the files (`ChangedFilesList`, with the view and sort button), or "The two have the same files." (the review's
    "No changes: a merge would change no file.");
  - loading ("Reading the commits and files…" until a first result), failure (`DialogWarning` and Try again), and the
    review's line about the worktree's uncommitted changes.
  - A click on a file still opens its diff over the log, as before. Step 4 turns it into the full view.
  - The lists' scroll states are `ComparisonListStates`, kept with the tab (`rememberInTab`) by `ComparisonPane`, and
    passed in, so that tests need no tab.
- `A/ui/compare/CompareBadges.kt`: `CompareBadge` and `CompareLetter`, fixed colours as the avatar palette has (amber
  `#E9C754`, light blue `#9FD1FF`, letter `#0E1621`, 11 : 1 on both), not theme colours: `ColorsScheme` is upstream's
  and serialized for custom themes. Renders showed the fills only 1.2 to 1.6 : 1 on the light theme's white, grey and
  selected row, so on a light theme a 1 dp outline of the same hue draws the badge (`#8A6D12`, `#2F6FA8`, at least
  3 : 1 on all three).
- Strings `compare_*` in `strings.xml`; the review's header, ahead/behind and section strings are gone, its uncommitted
  line's stay.
- **Tests:** `ComparisonPaneTest` (16), rendered at 330 px from a state: each state of F3 and the review's, the switch
  and what it sends, swap and end, a section that opens and closes, a file's click, a commit's menu, the older commits'
  count, a narrow pane (260 px) with a long branch name, and PNGs of 8 states in both themes with `LEAF_RENDER_DIR`.
  `CompareBadgeTest`: the fills and the light theme's outline in pixels, and the contrasts.

### Step 4: the full view (UI)

- `A/ui/compare/ComparisonFullView.kt`: `ComparisonFullView`, and `ComparisonFullViewContent`, which takes the state
  and a slot for the diff, for tests. It reuses the pane's parts (`SideLine`, `ModeSwitch`, `comparisonLine`,
  `CommitsSections`, `ChangedFiles`), which became internal. `RepoContent` shows it when `isFullView`, after the
  conflict resolver's branch and before file history's.
- `DiffPane` gains three optional parameters, which leave a commit's diff as it was: `isClosable` (false: no close
  button, and the pane doesn't add itself to the views that Esc closes, so that Esc leaves the full view rather than
  closing the diff), `headerActions` (before the view's buttons) and `sideLabels` (above the lines of a text diff).
- `RepositoryOpenViewModel`: leaving the full view, and the end of a comparison, clear the comparison's diff and its
  `CloseableView.DIFF` (`clearComparisonDiff`), which an extra Esc would otherwise close.
- `RepositoryOpenPage` gives the focus back to the tab when the full view closes, as for the conflict resolver.
- The pane's file click sends `OpenFullView`, and its header has the full-view button.
- **Tests:** `ComparisonFullViewTest` (9), at 1180 × 640 with a stand-in for the diff pane that shows what the view
  gives it: the header and its buttons, the lists on the left, a file's click, the stepper and its ends, ↑ and ↓, a
  file found by its paths after a new read, the labels of a split and a unified diff, and PNGs with `LEAF_RENDER_DIR`.
  `ComparisonPaneTest`: the click and the full-view button. The real diff pane inside the view needs the view model, so
  it was checked by hand in a dev run (2026-10-21): the diff, its labels, the stepper and the two Escs.

### Step 5: the log and the menus (UI)

- `A/ui/log/LogComparison.kt`: `LogComparison` (A's and B's commits, and the selection's name for "Compare with
  selected"), which `Log` gives its rows through `LocalLogComparison`, so that upstream's composables in between don't
  change; `commitRowClick` and `uncommittedRowClick`, what a click on a row does; and `CompareRowBadge`, the badge and
  its tooltip.
- `A/ui/log/Log.kt`: `CommitsList` reads the window's modifiers when a row is clicked (`LocalWindowInfo`, read in the
  click so that pressing a key recomposes nothing) and sends `LogAction.CommitPicked` for ⌘-click, `CommitSelected`
  otherwise. Both sides' rows are drawn as selected, with their badge. The badge starts 8 dp in, clear of the column's
  divider, and takes the place of the chips' 16 dp start padding (`LogRefChips`' `startPadding`), so a side's chips
  move 14 dp. ⌘-click on the uncommitted changes' row does nothing while comparing.
- `RepositoryOpenViewModel`: `pickCommit` (⌘-click: compares the row with what is selected, or picks it while comparing,
  or selects it when nothing else is selected), `compareWithSelected`, `compareBranchWithCurrent`,
  `compareTagWithCurrent`, and `selectedForComparison`, the name in "Compare with selected (…)". A row's side is
  `compareSideOf` its refs in the chips' order (`sideOfRow`); a branch picked in the side panel is that branch, a tag
  its commit named by the tag. The log's keys start from A while comparing.
- Menus (`A/ui/context_menu/CompareContextMenu.kt`, `CompareMenuItems`, `addCompareItems`, `addCompareWithCurrent`):
  - a commit row (`logContextMenu`'s `compare`): "Compare with selected (5be239a)" with "⌘-click" (Ctrl-click elsewhere)
    at its right, through a new optional `hint` of the menu's text entries, and "Compare with main (current)", in a
    group after "Copy commit hash";
  - a branch (`branchContextMenuItems`' `onCompareWithCurrent`), in the side panel and on a log chip, after Merge and
    Rebase, not on the current branch; the remote part of a shared chip (`sharedChipRemoteItems`) for each remote
    branch; a tag (`tagContextMenuItems`), between "Checkout tag's commit" and "Delete tag". The chips get the current
    branch and the callbacks through `LocalCompareWithCurrent`, which the list behind +N gives again, closing itself
    first.
  - With a detached HEAD, "Compare with HEAD (detached)".
- "Review changes" in a worktree row's menu was wired in step 3.
- **Tests:** `LogComparisonTest` (5: the letters, both clicks, the badge before the chips in both themes, from its
  pixels, and how far it moves them, with PNGs under `LEAF_RENDER_DIR`) and `CompareMenusTest` (7: each menu's items,
  their order, separators and hint, what they call, a detached HEAD, and the click per system). The click's routing is
  in two functions, so no test sets the window's modifiers. The view model's part was run against a real repository
  through the Dagger graph in a temporary harness (2026-10-21): ⌘-click with nothing selected and on the selected row,
  two branch tips ordered by date, a tag's commit before its descendant, a third commit replacing B and B dropped, ↓
  from A ending the comparison, the menus' comparisons with the current branch for a row, a branch and a tag, "Compare
  with selected" from a branch picked in the side panel, and a detached HEAD. 16 mutations of the menus, the clicks and
  the badge were each caught by a named test.

### Step 6: notes and a demo

Done 2026-10-22.

- `CLAUDE.md`: "Comparing two commits or branches", after "Reviewing a worktree", which shrank to what is the review's
  own (its order and mode, what ending it selects, the line about uncommitted changes). The Keys section names the two
  closeable views, and the lines that named `WorktreeReviewTest` point to its successor. `docs/fork/CHANGELOG.md` has
  the feature's entry.
- A demo repository for a manual check of the badges' tooltips, ⌘-click, the menus, the switch, the swap and the full
  view: `/tmp/leaf-compare-demo/repo`, made by `/tmp/leaf-compare-demo/make-demo.sh` (outside the repository, and gone
  after a reboot), with a `:app:run` command for it. A commit a day from 2026-10-01, by three authors, so that each case
  of 3.4 shows:
  - `main` and `feat/clone-picker`, diverged: the branch moves `src/Parser.kt` to `src/parse/Parser.kt` with an edit
    (a rename at 78%) and adds a file, and `main` has 4 newer commits, among them "Release 1.3.0", which changes
    `latest.json`: "All differences" shows it as undone;
  - an annotated tag `v1.0` on an older `main` commit, an ancestor of both, so a tag against `main` reads forwards;
  - `fix/typo`, which `main` merged (A is B's ancestor, and B is A's after a swap), `gh-pages`, an orphan branch (no
    commit in common), and a stash on `main`;
  - a linked worktree `repo-agent1` on `agent1`, two commits ahead of `main`, with a changed and an untracked file, for
    "Review changes" and its line.

### Step 7: the later items

Chosen 2026-10-22 (decision 5). The mockups are `Compare Step 7.dc.html` in the same project, one section per item,
drawn at the right pane's 330 px in the dark theme. Built smallest first, each a commit of its own: 7.4, 7.3, 7.1, 7.2.
7.3 touches file history only; 7.1, 7.2 and 7.4 all change the extender and the pane.

#### 7.4 A commit's own changes in a comparison (built 2026-10-22)

- **A click on a commit** in "Commits only in B" or "Commits only in A" lists that commit's own changes in place of the
  comparison's: against its first parent, as the log shows a commit (`GetCommitDiffEntriesUseCase`), a merge's too. The
  row stays highlighted, as the log's selected row is.
- **The strip** between the commits and the files names it: "Changes of e701c86 · 2 of 5", ‹ › to the older and newer
  commit of its side, oldest first as the list is, and "All changes". Its tooltip is the whole message, and for a merge
  says which parent the changes are against. In a narrow pane "Changes of" gives way first; the id, the place, the
  arrows and "All changes" stay. At 330 px all of it fits.
- **Back to all changes:** "All changes", a second click on the row, or Esc (`CloseableView.COMPARISON_COMMIT`). Esc goes
  back in the order things opened: a commit picked inside the full view first, then the full view, then the comparison.
  A new comparison starts with all changes.
- **Its files** open in the full view as the comparison's do. The labels above the diff are then the parent and the
  commit ("Parent · 30756d6 → e701c86", or "No parent" for a root commit), not A and B.
- **Loading and failure:** while its changes load, the list keeps what it showed, under the progress bar, as a
  comparison read again does. A read that fails shows its error in place of the files, with Try again.
- **The diff follows the list** (`followFiles`) when it changes: to a commit's files and back, and when the comparison is
  read again. It moves to the entry with the same paths, else, in the full view, to the first file; in the pane, a diff
  of a file that the list no longer has closes (`clearDiff`). The same versions of a file (paths and blob ids) keep the
  diff as it is, where it was scrolled to. Before, a comparison read again left the diff on the old entry.
- **A commit that a new read no longer lists**, as after a rebase of a branch side, stops being shown.
- **State:** `Active.shownCommit`; `ComparisonState.shownCommit` (`ShownCommit`: the commit, its place and count,
  loading, error) and `files`, what the list shows, which the files' count, the empty note ("This commit changes no
  file."), the full view's stepper and `OpenFullView` read. `ComparisonAction.ShowCommit`, `StepCommit` and
  `ShowAllChanges`. The extender gets `GetCommitDiffEntriesUseCase`, the tab's `diffSelected` and `clearDiff`.
- **Tests:** `ComparisonViewModelExtenderTest` (+9: the click and a second click, All changes, stepping and its ends,
  the list kept while loading, the diff following in the full view and closing in the pane, the same versions kept, a
  commit gone after a read, Esc's order, a failed read and Try again), `ComparisonPaneTest` (+5: the click, the
  highlight, the strip and its buttons at both ends, an empty commit, a failure, loading, a 260 px pane where "Changes
  of" gives way and the id stays), `ComparisonFullViewTest` (+1: the parent's and the commit's labels, a root commit).
  10 mutations of the steps, the toggle, the following, the loading and the labels, each caught by a named test.

#### 7.3 Two versions of a file in file history (built 2026-10-22)

- **⌘-click a second row** (Ctrl-click elsewhere, `isCompareClick`, read when the click happens, as the log does), or
  "Compare with selected (0101010)" in a row's menu, with the ⌘-click hint, on a row other than the selected one and
  only while one commit is shown. The older row is A and the newer B, by their place in the list, which is newest first
  (`versionPick`, `inListOrder` in `viewmodels/FileHistoryCommits.kt`). Both rows are highlighted, with the A and B
  badges at their ends (`VersionRowBadge`). The diff is the file from A's version to B's: one path, as file history
  doesn't follow renames. A row that deleted the file, or the commit that moved it away in the old name's history, shows
  as a deletion, as `git diff A B -- <path>` does.
- **The strip** (`FileVersionsStrip`, `ui/FileHistoryVersions.kt`) takes the place of the selected commit's strip: "A
  e4f5a6b subject → B 920d6ac subject · 4 commits apart" (the subjects are cut first, the whole message is a tooltip),
  the diff's View menu, swap and "Back to one commit (Esc)". The labels over the diff's sides are the full view's
  (`SideLabelsRow`, shared). Two rows with the same version of the file (a change and its revert) say "The file is the
  same in both versions." (`SameVersionsNote`) in place of the diff.
- **Ending it:** a plain click, ↑ or ↓ select one commit, as in the log; the arrows go on from A. The close button and
  Esc go back to the commit selected before the comparison (`FileVersions.before`). Esc reaches the tab's handler first,
  which now asks `RepositoryOpenViewModel.escapeHistory`: two versions go back to one commit, and only then does Esc
  close file history. ⌘-click on A or B drops that side; elsewhere the row replaces B, with the older first again.
- **The menu's item** gives the focus back to file history, which the menu took, so Esc and the arrows work after it.
- **Data:** `GetFileDiffBetweenCommitsGitAction` (`IGetFileDiffBetweenCommitsGitAction`, `GetFileDiffBetweenCommitsUseCase`):
  a `TreeWalk` of both commits' trees with a `PathFilter`, and `DiffEntry.scan`; the path's own entry, as a folder in its
  place lists its files too. Null when both have the same version. It reads no blob; the diff pane reads them by id, as
  for any commit's file.
- **View model:** `HistoryViewModel.versions` (`FileVersions`: A, B and the commit selected before), `pickCommit`,
  `swapVersions`, `endVersions`, and `reloadDiff` (Try again). The selected commit stays what it was. The whitespace
  mode's reload makes the two versions' diff again where it was scrolled to. `selectCommit`'s formatting is
  `showDiff`, shared.
- **Not built:** "Compare with working tree" in the row's menu, which needs 7.2's reading.
- **Tests:** `GetFileDiffBetweenCommitsGitActionTest` (6: modified either way, added, deleted, the same version, a folder
  in the file's place, a mode change, a missing commit, each against `git diff --name-status A B -- <path>` and git's
  blob ids), `FileHistoryCommitsTest` (+4: the order, a ⌘-click with nothing to compare, dropping and replacing a
  side, the distance), `HistoryViewModelTest` (+4: compare and Esc back, a click and the arrows from A, swap and the
  same version, the whitespace reload and Try again), `FileHistoryTest` (+3, rendered with the real view model: the
  menu's item and hint, the badges and the highlight, the strip, the note, swap, close and Esc, and PNGs in both
  themes with `LEAF_RENDER_DIR`). 11 mutations of the order, the picks, the reloads, the menu, the badges, Esc and the
  tree walk, each caught by a named test. The tab's Esc branch (`escapeHistory`) has no kept test.

#### 7.1 Compare any two refs (built 2026-10-22)

- **A's and B's lines in the pane's header are buttons** (`SideLine`'s `onClick`, a chevron after the name, which hugs
  it so that the name keeps its room). A click turns the name into a field (`SidePickerLine`) and opens the list of refs
  under the header, as wide as it (`RefChoicesPopup`, `ui/compare/SidePicker.kt`): a commit row for a typed hash or
  revision, then local branches, tags (newest version first) and remote branches, filtered as one types, as the clone
  dialog's branch list is, with "4 of 46 match. A hash or HEAD~2 works too." under it. A pick reads the comparison again
  with the other side kept, in the order the user put them (the swap button turns it around), in the new pair's default
  mode. The review's sides are the worktree's, so its lines open nothing.
- **The field keeps the focus:** what is typed filters the list, ↓ and ↑ move through it, and Enter picks; the first row
  is highlighted once something is typed, so Enter picks it. Esc closes the list (the tab's handler sees it first,
  through `CloseableView.COMPARISON_SIDE_PICKER`, and the pane gives the focus back to the tab), and so does leaving the
  field.
- **"Compare with…"** after "Compare with main (current)" in a commit row's menu, a branch's (the current branch's too),
  a tag's and a shared chip's remote part opens the pane with that ref as A and B's list open: a pending comparison
  (`ComparisonState.isPending`), which reads nothing, has no swap or full view, and says "Choose what to compare main
  with.". Closing its list ends it, selecting again what was selected before. **"Compare…"** in the Actions list does
  the same from the current branch, or a detached HEAD (`RepositoryTabViewModel.compareFromCurrentBranch`).
- **Data:** the list comes from the tab's branches, remotes and tags (`refChoicesFor`, `domain/compare/RefChoices.kt`),
  so nothing asks a remote. A typed text that could be a revision (`looksLikeRevision`) goes to `ResolveCommitGitAction`
  (`<revision>^{commit}`, as the comparison resolves a side) 150 ms after typing pauses; what names no commit, an
  ambiguous part of a hash, or text that isn't a revision is null, not an error. The commit row is left out when the
  text is a listed ref's name, which has its own row.
- **Shared with the clone dialog:** its rows and group headers (`PickRow`, `ChoiceGroupHeader`) and its keys
  (`branchChoicesKeys`). Its popup stays its own, as its states (asking a remote, failing) aren't the comparison's.
- **Tests:** `RefChoicesTest` (5: the groups and their order, the sides, the filter and its mark, the commit row, what
  could be a revision), `ResolveCommitGitActionTest` (2, against `git rev-parse <text>^{commit}`: hashes, parts, refs,
  `HEAD~2`, a tag; a tree, a range and nonsense are null), `ComparisonViewModelExtenderTest` (+5: a pending comparison
  and its Esc, a pick for B, a pick for A that keeps B, the list's refs and a resolved revision, the review),
  `ComparisonPaneTest` (+5: the lines' clicks and the review's, the field and the list under the header, the keys and
  Enter, the commit row and nothing matching, a pending comparison), `CompareMenusTest` (+1: "Compare with…" in each
  menu and what it calls). 13 mutations of the rules, the picks, the pending comparison, the pane and the menus, each
  caught by a named test. The tab's Esc and the Actions list's item have no kept test.

#### 7.2 Compare with the working tree (plan)

- **B can be the working tree:** the files as they are on disk, with what isn't committed yet. ⌘-click the "Uncommitted
  changes" row while a commit, branch or tag is selected (or a row while the uncommitted changes are), or "Compare with
  working tree" in the commit, branch and tag menus. The working tree is always B: no swap.
- **Modes:** its commits are the current branch's, so with A on another branch both apply. Since they split is from the
  merge base of A and HEAD to the working tree, what the branch and its uncommitted work would bring into A; All
  differences is `git diff A`. "Commits only in B" are HEAD's, followed by "and the uncommitted changes".
- **Untracked files** are in the list, as Unstaged lists them, with an "untracked" tag (7.2a); ignored files aren't. The
  B badge marks the "Uncommitted changes" row, not HEAD's.
- **It re-reads when files change** (`refreshTriggered` with ALL or STATUS), as Staged and Unstaged do. Its diffs are
  read-only: no stage or discard buttons.
- **Implementation:** `CompareSide.WorkingTree` (its commit is HEAD's); the use case and the action take sides, not
  strings; a `DiffType` for a file between a commit and the working tree; a walk of the commit's tree, the index
  (`DirCacheIterator`) and the working tree (`FileTreeIterator`), with rename detection that reads the working tree's
  files; an index that JGit can't read gives `UnsupportedIndexError`, as status does.
- **Tests:** the action against `git diff A` and `git diff $(git merge-base A HEAD)` plus `git ls-files --others
  --exclude-standard`, the view model's side (no swap, the refresh), the log's badge on the uncommitted row, the pane's
  tag.

## 5. Checks before calling it done

- The suites of the steps, then `./gradlew build`.
- Mutations of the ordering, the default mode, the switch's visibility, the modifier mapping and the tree choice by
  mode, each caught by a named test.
- Renders of F1, F3, F4 and F5 in both themes against the mockups.
- The real Dagger graph in a temporary harness: ⌘-click two commits, swap, both modes, the full view, Esc twice, and
  a branch side that moves while the comparison is open.

## 6. Later, as their own items

The four items that were here are step 7. Left:

- **A worktree's review with its uncommitted work (7.2c):** "Include its uncommitted changes" in "Review changes" on
  another worktree. It needs the diff code to read another worktree's files, so it comes after 7.2.
- **Pin a base in file history (7.3b):** "Pin as base" fixes A, then a click, ↑ or ↓ move B, to walk a file's changes
  from one release on. It could come on top of 7.3.
- **Enter in the pane's file list** opens nothing yet.
