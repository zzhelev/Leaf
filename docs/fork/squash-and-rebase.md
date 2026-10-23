# Squashing and rewriting a branch's commits (upstream issue 73)

| | |
|---|---|
| Question | How can Leaf tidy a feature branch's commits (squash, fixup, reword, move onto the latest main) without the user finding the fork point or fearing a rebase? |
| Verified against | `main` at `6b8e8eec`; JGit 7.7.0; git 2.54 |
| Date | 2026-10-23 |
| Status | Decided 2026-10-23: A, then B; Shift-click; newest first; onto the latest main; messages edited before anything is applied. Decisions 6 to 8 and the plan for A (section 3) approved the same day. A1 (the engine), A2 (the log's range), A3 (menus, dialog, strip, Undo), A4 (onto the latest main) and A5 (CLAUDE.md, the changelog, a demo) done: part A is built. After the manual run, "Fixup into parent" refuses a `fixup!` commit that names another commit than its parent |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

**Mockup:** the Claude Design project "Leaf squash and interactive rebase", file `Squash and Rebase.dc.html`, a working
prototype (round 2, with the decisions below). It has an agent's branch of seven commits, four of them pushed, and a main
two commits ahead.

## Summary

- **Today:** upstream's interactive rebase ("Rebase interactive" on a commit row) starts JGit's rebase before the list is
  edited, so Cancel is an abort and the worktree is mid-rebase while the list is open. It can't take a squash's message
  (its handler answers JGit with the next Reword's text, or JGit joins the messages), and it rebases the commits after
  the row clicked, so squashing a branch means finding where it left main. No multi-select, undo, pushed-commit warning
  or autostash.
- **A, quick actions in the log (first):** Squash N commits (a Shift-click range), Squash branch into one commit (the
  branch and worktree menus), Squash into parent, Fixup into parent, Edit message. They keep every commit's files, so
  Leaf rebuilds the commits from git's own trees: nothing is replayed, they never stop at conflicts, the working tree and
  its uncommitted changes aren't touched, and a branch checked out in an agent's worktree can be rewritten from any tab.
  Squash branch can also put the one commit on the latest main, with one merge checked before anything changes.
- **B, a plan-first editor (then):** replaces today's list. Nothing happens until Start. Newest first, keys, drag, the
  joined rows under their target, each result's message on the right, onto the latest main or where the branch started.
  A plan that only joins and rewords runs as A does; one that drops, moves or stops replays with `git rebase -i`, and
  its stops use Leaf's banner and resolver. B gets a plan of its own (section 4 is its outline).

## 1. What Leaf has today

| Piece | Where | Reused? |
|---|---|---|
| The interactive rebase list | `A/ui/RebaseInteractive.kt`, `RepositoryOpenViewModel.continueRebaseInteractive`, `moveCommit`, `onCommitActionChanged` | Replaced in B |
| JGit's rebase, started before the list | `DA/git/rebase/StartRebaseInteractiveGitAction`, `ResumeRebaseInteractiveGitAction` | Removed in B |
| Continue, Skip and Abort with git for a rebase that git started; the banner; Skip's guards | `DA/git/rebase/GitCliRebase.kt`, `A/ui/status/OperationInProgressBanner.kt`, `D/rebase/RebaseSkip.kt` | B, as they are |
| The base branch of the worktrees | `D/models/Worktree.kt` (`WorktreeBaseBranch`), `DA/git/config/WorktreeBaseBranchConfig.kt` | A and B: "since it left main", "the latest main" |
| `git merge-tree --write-tree` | `DA/git/worktrees/GetBranchMergedGitAction.kt` (`mergeChangesNothing`) | A4: the same call, with the conflicted files |
| A multi-selection with an anchor, Esc and a chip | `D/branches/BranchSelection.kt`, `A/repositoryopen/BranchSelectionController.kt` | A2: the same shape for the log |
| ⌘-click in the log (comparison) | `A/ui/log/LogComparison.kt`, `isCompareClick` | Unchanged; Shift is new |
| git's message clean-up | `D/commitmessage/MessageCleanup.kt` (`cleanCommitMessage`) | A3: every message Leaf writes |
| A squash merge's message | `D/worktrees/WorktreeMergeSteps.kt` (`squashMessage`) | A3: the subjects as a list |
| git runs with Leaf's dialogs (ssh-keygen's passphrase) and Cancel | `DA/git/cli/remote/GitCliRemoteCommand.kt`, `DA/git/cli/operations/GitCliOperations.kt` | A1: `commit-tree -S` |
| Force push, with its confirmation | the Push dialog | A3: the strip's "Push with force…" |

**How other clients do it** (sourced in the mockup's table): SourceTree, Fork, GitKraken and IntelliJ edit a plan in a
dialog; Sublime Merge and IntelliJ have one-shot actions; Tower and GitHub Desktop drag in the log; GitHub Desktop,
GitKraken, Tower and IntelliJ squash a multi-selection with a message dialog. Undo after a rewrite is now common. Only
lazygit finds "since main" by itself, and GitKraken's drag of a branch onto main is the one "onto the latest main".

## 2. Decisions

1. **A first, then B.** C (drag and drop in the log) later, if at all. 2026-10-23.
2. **Shift-click, and Shift+↑↓, select a range in the log.** ⌘-click (Ctrl-click) stays the comparison's. 2026-10-23.
3. **The editor lists the newest commit first,** as the log and SourceTree do; Squash joins the row below. 2026-10-23.
4. **Onto the latest main:** in the editor ("Onto", the latest main by default when main has moved) and in Squash branch
   into one commit (a box, checked by default). 2026-10-23.
5. **Every new message is edited before anything is applied:** in the dialog, or in the editor's panel, which opens
   focused, the caret after the subject. It starts from the first subject that says something (not `wip`, `fixup!` or
   `squash!`) with a list of the others; "Start from" switches to all the messages or the oldest one. 2026-10-23.

Proposed with the plan, approved 2026-10-23:

6. **A never stops at conflicts.** Anything that replays (drop, move, an edit stop) is B's, so Drop commit… moves to B.
   Onto main in Squash branch is one merge checked when the dialog opens: when it would conflict, the box is off and
   says with which files.
7. **Fixup into parent runs at once**, with Undo: it makes no new message, as it keeps the parent's.
8. **No text explains what Leaf handles** (`no-ui-explanations`): the dialogs say what the user acts on, the pushed
   commits that need a force push, the files that would conflict with main, uncommitted changes in the way.

## 3. A: quick actions

### 3.1 Items

| Where | Item | Does |
|---|---|---|
| A commit row of a branch's own line | Edit message… | Dialog with the message |
| The same, not the line's first commit | Squash into parent… | Dialog with both messages |
| The same | Fixup into parent | At once (the parent's message), Undo |
| Two or more rows selected | Squash N commits… | Dialog |
| A local branch (side panel, log chip), a worktree row | Squash branch into one commit… | Dialog, with "On top of the latest main" |

- **A branch's own line:** the first-parent commits from its tip down to where it left the base (`git merge-base`). A
  commit row's items rewrite the one local branch whose line holds the commit, or the current branch when several do;
  with several and none current, they're disabled ("On 2 branches").
- **Disabled, with the reason at the item's right:** a commit on the base's line ("On main"), a merge in the commits
  that would be joined, a branch whose worktree is in a merge, rebase, cherry-pick or revert, a range that isn't
  contiguous on one line, and Squash branch with one commit. Commits after the rewritten ones may be merges: they keep
  their other parents.

### 3.2 The log's range (A2)

- `LogSelection` in domain (anchor and commits), `LogSelectionController` in `A/repositoryopen/`, as the branch selection:
  Shift-click takes the rows between the anchor and the click, Shift+↑↓ extends from the anchor, a plain click or Esc
  (`CloseableView.LOG_SELECTION`) ends it. The rows are drawn selected; the right pane shows "4 commits selected" and
  their subjects; the menu on a selected row is the range's ("Squash 4 commits…", "Copy commit hashes").
- Which commits make a valid range is decided when the menu opens (`D/rewrite/`), so the selection itself takes any rows.
- Built in A2: `LogSelection` (`D/log/`) holds the anchor and the lead, and the rows between them come from the log as
  it is. While there's a range, the log's selection is the anchor, as a commit: a range from a branch or a tag picked in
  the side panel starts from its commit, and anything else selected (a click, the side panel, ⌘-click, the uncommitted
  changes) ends it, as do a refresh that loses either end and the pane's ×. The arrows without Shift move on from the
  lead. A range closes the commit's diff, as the list it was opened from gives way to the range's pane. The menu on a
  selected row has "Copy N commit hashes" (newest first) and "Clear selection"; A3 adds the squash.

### 3.3 The dialog (A3)

- The commits, newest first; the message, focused with the caret after the subject; "Start from"; for Squash branch, "On
  top of the latest main · 55991ae, 2 new commits" (checked when main has moved), and under it either nothing or "Conflicts
  with main in ParserCache.kt" (unchecked and disabled); the pushed commits that change ("4 commits that this rewrites are on
  origin/x. Afterwards, push with force to replace them."). Cancel, Squash N commits (⌘↵).
- The message is cleaned as git cleans it (`cleanCommitMessage` with the repository's clean-up mode). An empty one
  disables the button.
- After it: a strip under the toolbar, "Squashed 4 commits into one on claude/parser-cache.", with "Push with force…"
  when pushed commits changed, Undo and ×. It stays until closed or the next rewrite (not a toast).
- Built in A3, without "On top of the latest main" (A4):
  - **What the menus read:** `BranchLines` (`D/rewrite/`), every local branch's line, refreshed with the branch counts
    (`GetBranchLinesGitAction`, one `RevWalk`: a branch's own commits are those that its base doesn't reach, and its line
    is the first-parent chain among them, up to 2,000; the upstream's own commits give `pushed`). The menus ask it, when
    they open, what each item does or why it can't (`commitChoices`, `rangeChoice`, `branchChoice`), and a worktree in the
    middle of a merge, rebase, cherry-pick, revert, bisect or `git am` with the branch disables them (`rewriteBusyWith`).
  - **The menus:** a disabled item stays in the menu, faded, with why at its right (`ContextTextEntry.isEnabled`, a
    small change to upstream's `ContextMenu.kt`). A commit row's three items come before Checkout; the range's "Squash N
    commits…" first; a branch's "Squash branch into one commit…" in its own group, in the side panel, on a log chip and
    in a worktree row's menu. "Fixup into parent" runs at once, as planned.
  - **The dialog** (`RewriteCommitsDialog`, `Screen.RewriteCommits`): the title says what it does ("Squash 2 commits into
    one", "Squash claude/parser-cache into one commit", "Edit the message of b1c2d3e"), the subtitle where ("On
    claude/parser-cache.", or for the whole branch "All 4 commits since the branch left main at a867b0c."). A commit
    whose subject says nothing (`wip`, `fixup! …`) isn't the lead of "Subjects as a list". "Start from" replaces the
    message. ⌘↵ (Ctrl+Enter) confirms. Save is disabled while a reword's message, as git's clean-up reads it, is the
    commit's own: it would only make new commits, which a pushed branch then has to replace (found in the real-tab
    run). The message field is the dialog's own, with the outlined fields' look: the shared one centres a single line
    in its border. Found by its test: the field's request for the focus has to come after the dialog's own, or the
    dialog's wins.
  - **Checked in the real tab** (the Dagger graph, in a temporary harness, both themes, a branch pushed to a bare
    `origin`): each menu's items and reasons, squash into parent, a range of three, the whole branch, a reword, a
    fixup, each with Undo, the files unchanged after each, the selection, the strip's texts and buttons. An offscreen
    scene runs the log's scroll to the selected commit (`scrollToCommit`, upstream's) inline and throws "pending
    composition has not been applied", so the harness gives its scene the Swing dispatcher.
  - **The run** (`TaskType.RewriteBranch`, no toast): the processing screen, then the strip, and the log selects the
    commit that the squash, fixup or reword made, once the refresh has it; after Undo, the branch's tip again. A refusal
    (the branch moved, a merge after it) is `ErrorDialog`'s. Undo (`TaskType.UndoBranchRewrite`) turns the strip into
    "Undone: claude/parser-cache is back at 223cba0." with only ×. "Push with force…" is offered for the tab's own
    branch, through the force push's confirmation.

### 3.4 The engine (A1, A4)

- `IRewriteBranchGitAction` / `RewriteBranchGitAction` (`DA/git/rewrite/`), with the git CLI: the branch (full ref), its
  expected tip, the commit the rewrite starts after, and groups oldest first, each with the commits it joins and a message
  or none (keep).
  - Each new commit is `git commit-tree <tree of the group's newest commit> -p <new parent> -F <message file>`, with the
    author, email and date of the group's oldest commit (`GIT_AUTHOR_*`, as git's squash keeps), git's committer, and
    `-S` when git's config says `commit.gpgSign` (checked: `commit-tree` ignores it). It runs through
    `GitCliRemoteCommand`, so ssh-keygen's passphrase dialog and Cancel work. The commits after the groups are made again
    with their own trees, messages, authors and other parents.
  - Then one ref update with the old value expected, so a branch that moved meanwhile is refused and nothing changes:
    `git update-ref -m <message> refs/heads/<branch> <new> <old>`, run in the worktree that has the branch checked out,
    or in the tab's. git writes the reflog of the HEAD that points to the branch too, as `git rebase` does. The working
    tree and the index stay as they are: the tip's tree doesn't change.
  - `post-rewrite`: `git rebase` runs it with `rebase` and the old and new ids. Leaf does the same with
    `git hook run --ignore-missing --to-stdin=<list> post-rewrite -- rebase` (git 2.36 and later), and the list is the
    one `git rebase -i` gives for the same todo (tested).
  - Found in A1: a commit made again keeps its message's bytes and its `encoding` header (`-c i18n.commitEncoding`,
    where `git rebase` turns them into UTF-8). A merge after the rewritten commits whose other parent has them (a branch
    that started on this one) is refused: the history would have the old commits next to the new ones.
- **Onto the latest main (A4):** `git merge-tree --write-tree --name-only --no-messages <main> <branch>` when the dialog
  opens: exit 0 gives the tree, exit 1 the files that conflict (checked on git 2.54). The commit is `commit-tree <tree>
  -p <main>`. A branch checked out somewhere moves with `git reset --keep <new>` in that worktree (with
  `GIT_REFLOG_ACTION`), which keeps uncommitted changes that main's files don't touch and refuses, changing nothing,
  when they do; the dialog says so before ("Your changes to Log.kt are in the way").
- **Undo:** the ref back with the new value expected (`update-ref <old> <new>`, or `reset --keep <old>` after a move onto
  main), refused when the branch moved since. The reflog keeps the old tip either way.
- Built in A4:
  - **`read-tree -m -u` and `update-ref`, not `reset --keep`** (probed on git 2.54): both refuse, changing nothing, when an
    uncommitted change is in a file that the move changes, or an untracked file is where it adds one, but `reset --keep`
    unstages every staged change, also in the files it doesn't touch. So a checked-out branch moves with
    `git read-tree -m -u <old> <new>` (the two-tree merge that `git checkout` makes), which keeps them staged, then the
    `update-ref` that expects the old tip; when that's refused, the files go back the same way
    (`moveBranchWithFiles`, `DA/git/rewrite/BranchPlace.kt`). Undo moves back the same way. The reflog says
    `squash onto main: <subject>`.
  - **An ignored file is in the way too:** `read-tree` overwrites an ignored file where the move adds one without a word, as
    git counts ignored files as expendable, and that may be a local `.env`. `filesInTheWay` (`DA/git/rewrite/OntoBase.kt`)
    lists tracked files that the move changes and that have uncommitted changes (`git status`), and anything on disk
    where it adds a file or a folder (`git diff-tree --name-status`); the move checks it again just before it runs.
  - **The check** (`GetOntoBaseCheckGitAction`, `OntoBaseCheck`, when the dialog opens): null when there's no base, the
    branch has the base's tip, or git is older than 2.38; else the base's commit, how many of its commits the branch lacks,
    and `Clean`, `Conflicts` (`merge-tree --name-only`) or `ChangesInTheWay`, the last only where the branch is checked
    out. The squash goes on that commit, whatever the base did since.
  - **The dialog:** "On top of the latest main · 001cd3e, 1 new commit" after Start from, checked when clean, off and
    disabled with "Conflicts with main in parser.kt" or "Your changes to NOTES.md are in the way" (three names, then "and
    N more"), not there when there's nothing to offer. Squash waits for the check. The strip says "…, on top of main.";
    a refusal at run time (`ConflictsWithBase`, `ChangesInTheWay`) is `ErrorDialog`'s, with the files.

### 3.5 Tests

- Data, against git in twin repositories: the same plan through `git rebase -i` (with `GIT_SEQUENCE_EDITOR`) gives the same
  trees, messages, authors and author dates; the reflog entries; a fake gpg for `commit.gpgSign` and ssh-keygen with a
  passphrase; the branch checked out in the tab's worktree, in another worktree, and nowhere; uncommitted and staged
  changes kept; a branch that moved; merges after the range; onto main clean, conflicting, and with local changes in the
  way; Undo, and Undo refused.
- Domain: the line of a branch, the ranges, the disabled reasons, the message's start.
- App, rendered: the menus, the selection and its pane, the dialog in both themes, the strip.
- The definition of done (`PLAN.md`): a manual run on macOS against a temp repository, and `CHANGELOG.md`.

### 3.6 Steps

1. **A1:** domain rules and the engine, with its tests.
2. **A2:** the log's range.
3. **A3:** the menus, the dialog, the strip and Undo.
4. **A4:** onto the latest main.
5. **A5:** `CLAUDE.md`, the changelog, a demo repository for a manual run.

Each step is its own commit (or two, code then docs), shown to you before it's made.

## 4. B: the editor (outline; its own plan after A)

Planned in `docs/fork/commit-editor.md` (2026-10-23), which changes this outline: a plan that doesn't stop, drops and
moves included, runs in memory as A does, and only a plan that stops replays with `git rebase -i`.

- The editor of the mockup in the centre pane, from "Edit branch commits…" (the branch and worktree menus) and
  "Interactive rebase from here…" (a commit row, including that commit, as IntelliJ does). Nothing runs until Start.
- The plan in domain: rows newest first, P R S F E D, ⌥↑↓ and drag, the joined rows under their target, the first kept
  row can't join, "fixup!" commits placed for you, Reset the plan.
- Start: a plan that only joins and rewords goes through A's engine. Any other replays: `git rebase -i <onto>` in the
  worktree that has the branch, with Leaf's todo as git's sequence editor and Leaf's messages through `leaf-askpass` as
  git's editor (it reads which commit git is at from `rebase-merge/done`; to probe). Uncommitted changes in the way ask
  to be set aside (`--autostash`).
- Stops: the existing banner, conflict resolver, Continue, Skip and Abort, and "Back to the plan" (abort, then the editor
  with the same plan).
- Then upstream's list and JGit's interactive rebase go, with the message bug.

## 5. Later, or not planned

- C, drag and drop in the log.
- A merge inside the commits joined (a squash would lose its second parent).
- Branches stacked on the rewritten one (git's `--update-refs`); they keep the old commits, as with `git rebase`.
- The base branch's own commits: in B, its unpushed ones (decision 5 of `docs/fork/commit-editor.md`).
