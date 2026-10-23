# The commit editor: a plan-first interactive rebase (part B of upstream issue 73)

| | |
|---|---|
| Question | How does Leaf replace upstream's interactive rebase list with an editor that plans first, says before Start what will happen, and only touches a worktree when it has to? |
| Verified against | `main` at `1edc1471`; git 2.54 (probes on 2026-10-23, section 2) |
| Date | 2026-10-23 |
| Status | Decided 2026-10-23: 1a, 2a, 3a, 4a, and yes to 5, 6 and 7 (section 3). Next: B1 |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

Part A (`docs/fork/squash-and-rebase.md`) is built: squash, fixup and edit message from the log, which keep every
commit's tree. Its section 4 is the outline this plan starts from.

**Mockup:** the Claude Design project "Leaf squash and interactive rebase", file `Commit Editor.dc.html`, a working
prototype (round 3), next to round 2's `Squash and Rebase.dc.html`. It has switches for where the branch is checked out,
for uncommitted changes, and for each choice in section 3.

## Summary

- **The editor** is round 2's: in the centre pane, newest first, P R S F E D, ⌥↑↓ and drag, joined rows under their
  target, each new commit's message on the right, onto the latest main or where the branch started, "fixup!" commits
  placed for you. Nothing touches the repository until Start.
- **New: Leaf checks the plan while it's edited.** It replays the plan in memory with `git merge-tree`, one commit at a
  time, so the editor says before Start whether the plan stops and where: the first commit that conflicts gets "Stops ·
  ParserCacheTest.kt" on its row, the file is marked in the panel, and the footer names it. An Edit row is a stop too.
- **A plan that doesn't stop is applied at once,** drops and moves included, by part A's engine: from any tab, without
  checking anything out, the working tree moved once at the end if the files changed, then the strip with Undo. The
  button says "Rewrite 7 commits".
- **Only a plan that stops replays in a worktree,** with `git rebase -i` and Leaf's todo. New messages go in as twin
  commits, so git never opens an editor. Its stops are Leaf's banner, resolver, Continue, Skip and Abort, plus "Back to
  the plan". The button says "Start rebase".
- **main's own unpushed commits** get a line, so the editor and part A's items work on them too.
- **Then upstream's list and JGit's interactive rebase go,** with their message bug.

## 1. What Leaf has today

| Piece | Where | In B |
|---|---|---|
| Upstream's list, started on JGit before it's edited | `A/ui/RebaseInteractive.kt`, `A/viewmodels/RebaseInteractiveViewModel.kt`, `DA/git/rebase/StartRebaseInteractiveGitAction.kt`, `ResumeRebaseInteractiveGitAction.kt`, `GetRebaseInteractiveTodoLinesGitAction.kt` | Removed (B6) |
| A branch's own line, the menus' reasons | `D/rewrite/BranchLine.kt`, `BranchLines.kt`, `DA/git/rewrite/GetBranchLinesGitAction.kt` | The editor's rows and its refusals |
| The rewrite engine: `commit-tree`, `update-ref`, `post-rewrite`, Undo | `DA/git/rewrite/RewriteBranchGitAction.kt`, `UndoBranchRewriteGitAction.kt` | Every plan that doesn't stop (B3) |
| Moving a checked-out branch's files, and what is in the way | `DA/git/rewrite/BranchPlace.kt` (`moveBranchWithFiles`), `OntoBase.kt` (`filesInTheWay`) | The same |
| `fixup!` targets, a squash's message | `D/rewrite/SquashMessage.kt` (`autosquashTarget`, `SquashMessageStart`) | "Put them there", the panel's Start from |
| The strip, Undo, "Push with force…" | `A/ui/RewriteStrip.kt`, `A/repositoryopen/RewriteNotice.kt` | The same |
| Continue, Skip and Abort for a rebase that git started | `DA/git/rebase/GitCliRebase.kt`, `D/rebase/RebaseSkip.kt` | Stops, as they are |
| An edit stop's message, the banner, the resolver | `DA/git/repository/RebaseEditMessage.kt`, `A/ui/status/OperationInProgressBanner.kt`, `A/ui/conflicts/` | Stops, as they are |
| Changes that git sets aside and doesn't put back | `DA/git/cli/operations/Autostashes.kt` | `--autostash` (choice 3) |
| A tab that goes to the worktree where a rebase stopped | `D/worktrees/WorktreeRebaseSteps.kt` (`SwitchTab`, `onlyIfStoppedAtConflicts`) | The same |
| "Changed 2 minutes ago: an agent may still be working" | `RecentChangeNotice`, `RECENT_CHANGE_MS` | Plans that touch another worktree |

## 2. Findings (git 2.54, probed on 2026-10-23)

1. **A replay without a working tree.** `git merge-tree --write-tree --merge-base=<c>^ <new parent> <c>` gives the tree
   that picking `c` onto the new parent gives, or exits 1 with the files that conflict. It takes trees for all three, so
   the commits joined into one need no commits in between. A plan that reordered three commits, dropped one and moved
   them onto a main that had renamed a file gave the same tree as `git rebase -i` with the same todo. About 12 ms a
   commit in a small repository, and up to 80 ms in Leaf's own (1,890 commits). `--merge-base` came with git 2.40, to
   confirm in B2 with the oldest git Leaf supports.
2. **A commit that comes out empty.** `git rebase -i` stops at it ("nothing to commit"); with `--empty=drop` it drops it.
   merge-tree gives the new parent's tree for it.
3. **git asks for an editor more often than the todo says.** It opens one for `reword` and `squash`, and also on Continue
   after a conflicted pick: with `GIT_EDITOR=false`, Continue failed ("there was a problem with the editor"). So a plan's
   new messages can't be left to `reword` lines: a Continue from a terminal would show the old message, and Leaf's
   (`GIT_EDITOR=:`) would keep it.
4. **Twins carry the new messages.** A twin is `git commit-tree <c>^{tree} -p <c>^` with the new message and `c`'s author
   and author date, picked in place of `c`. A joined group is `pick <twin of the first>` then `fixup` lines. With
   `GIT_EDITOR=false`, the plan ran without an editor, and after conflicts at the twin, Continue (`GIT_EDITOR=:`) kept
   the planned message, as a terminal's Continue does in the editor it opens. What differs: `REBASE_HEAD`,
   `rebase-merge/done` and `post-rewrite`'s list name the twin, not the original.
5. **Hooks.** `git rebase -i` runs `pre-rebase`, then `prepare-commit-msg` and `post-commit` for each pick, and
   `post-rewrite` at the end; not `pre-commit` or `commit-msg`. Part A's engine runs only `post-rewrite`.
6. **The user's rebase config.** `rebase.missingCommitsCheck=error` refuses a todo that leaves a commit out, and accepts
   `drop` lines. `rebase.updateRefs=true` would add `update-ref` lines for other branches; `--no-update-refs` keeps
   them out. `GIT_SEQUENCE_EDITOR="cp '<Leaf's todo>'"` replaces git's todo.

## 3. Decisions (2026-10-23)

Chosen with the user from round 3 of the mockup, each option tried there with its switch.

1. **Before Start, Leaf checks the plan and says where it stops (1a).** Each change to the plan runs the check again,
   about 10 to 80 ms a commit, and a plan that doesn't stop never touches a worktree. Not 1b, round 2's "It may stop at
   conflicts" for any plan that drops or moves, found out at the stop.
2. **A plan that stops, on a branch that no worktree has checked out, checks it out in this tab first (2a),** as
   `git rebase -i main <branch>` does, and the footer says "Checks out claude/parser-cache here first". The tab stays on
   the branch afterwards. Not 2b, a refusal. A plan that doesn't stop runs from any tab either way.
3. **Uncommitted changes where a plan stops (3a):** in this tab's worktree, a box "Set my 2 uncommitted changes aside,
   and put them back after the rebase" (`--autostash`), which Start waits for; in another worktree, an agent's,
   refused ("leaf-parser-cache has 2 uncommitted changes"), as Leaf doesn't stash an agent's work in progress. Not 3b, a
   refusal everywhere. A plan that doesn't stop keeps them, unless the files it changes have them, as with part A's
   squash onto main.
4. **Drop commit… on a commit row is a dialog (4a)** (decision 6 of part A moved it to B), like Squash into parent…: the
   commit, its files, where the commits after it stop if they do. "Drop commit" with Undo, or "Drop and rebase". Not 4b,
   the editor with that row on Drop.
5. **main's own unpushed commits get a line too:** for the base branch, the commits that its upstream lacks
   (`origin/main..main`). Upstream's list works on any commit, so without this, B would lose rebasing main's local
   commits. Part A's items offer it too.
6. **A plan is kept** per tab and branch while the branch's tip doesn't move: Cancel, or switching away, and opening the
   editor again shows it as it was. Reset the plan starts over. No "Discard the plan?" question.
7. **Upstream's files are deleted, not left unreachable** (B6). A sync then shows modify/delete for them, and the deletion
   is kept.

## 4. The editor (B5)

As round 2, with these changes (round 3 of the mockup):

- **Where:** "Edit branch commits…" in a local branch's menu (side panel, log chip, worktree row), "Interactive rebase
  from here…" on a commit row (that commit included, as IntelliJ does; it replaces upstream's "Rebase interactive",
  which started after the row), and "Edit these in the rebase editor…" on a Shift range. Disabled with part A's reasons
  ("On main", "Includes a merge", "On 2 branches", "In a rebase"). On main's own line (decision 5), a commit that
  origin/main has says "On origin/main".
- **The pane:** the centre pane, the branches pane hidden while it shows, the changes pane held open for the panel
  (`ChangesPaneNeed`).
- **Rows:** a row that the check says stops gets "Stops · <file>" (amber); a row that comes out empty gets "In main" and
  is left out; "moved", "+2" and "picked" as in round 2. Only the first stop is marked: after it, what happens depends on
  the resolution.
- **The panel:** "Commit 3 of 6 after the rebase", the message (focused, the caret after the subject, when a row turns
  Reword or a group forms), Start from for a group, the commits joined, the files, with "conflicts" on the stop's files.
  "Dropped" for a dropped row. No notes on what Squash, Fixup or Edit do.
- **The footer:** "7 commits → 5 · 1 joined · 1 dropped · reordered", then only lines to act on: where it stops ("Stops at
  d25e917 "wip": ParserCacheTest.kt conflicts." or "Stops at 0a6c3e8 for you to edit it."), where it runs ("Runs in
  leaf-parser-cache.", "Checks out claude/parser-cache here first."), what is in the way, an agent's recent change, the
  pushed commits. Reset the plan, Cancel (Esc), and "Rewrite 7 commits" or "Start rebase" (⌘↵), which waits for the
  check.
- **Drop commit…** (choice 4).
- **After:** the strip, "Rebased claude/parser-cache onto main: 7 commits → 5.", with "Push with force…" and Undo; for a
  rebase that stopped, when it ends, from Leaf or a terminal.

## 5. The plan and the check (B1, B2)

- **Domain** (`D/rewrite/RebasePlan.kt`): rows oldest first (shown newest first), each a commit of the line and an action
  (pick, reword, squash, fixup, edit, drop); the groups they make; the first kept row can't join; moving a row keeps its
  action unless it becomes the first kept row. "Put them there" places `fixup!`, `squash!` and `amend!` commits with
  `autosquashTarget`. A message per group, from `SquashMessageStart`.
- **The check** (`CheckRebasePlanGitAction`, `DA/git/rewrite/`): from the first row that changes, for each group oldest
  first, a tree chain from the new parent's tree `P`: for each commit `c` of the group, `P = tree(c)` when `P` is
  `c^`'s tree (nothing before it changed, part A's case), else `P = merge-tree(c^, P, c)`. The result per group: a tree,
  empty (left out), a conflict with its files (the plan stops there), or an edit stop. One rule for both parts: a plan
  that only joins and rewords never merges.
  - It runs off the UI thread, 150 ms after the last change, and the next change cancels it. Results are kept by
    (parent tree, commit), so a change late in the plan only checks the rows after it.
  - A partial clone checks with `GIT_NO_LAZY_FETCH=1` (git 2.44+), so editing downloads nothing. A blob it lacks makes
    the plan "may stop", which then replays with git.
  - Without a git that has `--merge-base`, there's no check (choice 1b's footer), and every plan that drops or moves
    replays with git.

## 6. Running a plan (B3, B4)

- **No stop (B3):** `RewriteBranchGitAction` with the check's trees, groups in any order: `commit-tree` per group with
  the author and author date of its first commit, git's committer, `-S` when `commit.gpgSign` says so; the trees are
  worked out again at run time against the expected tip and base. One `update-ref` that expects the old tip;
  `moveBranchWithFiles` when the branch is checked out and its last tree changed, refused when files are in the way;
  `post-rewrite` with git's list for the same todo. Undo as in part A.
- **A stop (B4):** in the worktree that has the branch (choice 2 when none has it), through `GitCliOperations.run`
  (askpass for signing, Cancel):
  1. twins for the groups with new messages;
  2. the todo, full hashes: `pick`, `fixup`, `edit`, `drop` lines, from the commit after the last unchanged one;
  3. `git rebase -i --empty=drop --reapply-cherry-picks --no-autosquash --no-update-refs --force-rebase [--autostash]
     [--onto <base commit>] <upstream>`, with `GIT_SEQUENCE_EDITOR` copying the todo and `GIT_EDITOR=:`.
     `--force-rebase` makes every replayed commit again, so it's signed as git's config says (a twin isn't);
  4. `rebase-merge/leaf-plan`: the plan and each twin's original, so the banner, the resolver's labels and the Skip
     dialog name the commits that the user knows, and "Back to the plan" works after a restart.
  - The tab goes to that worktree when it stops there. Continue, Skip and Abort are today's; "Back to the plan" aborts
    and opens the editor with the plan.
  - To decide in B4: twins are unreferenced while a rebase waits, so `git gc --prune=now` would delete them; a ref under
    `refs/leaf/` while it runs would keep them.
- **Not as `git rebase -i`:** a plan that doesn't stop runs no `pre-rebase`, `prepare-commit-msg` or `post-commit`, as part
  A's rewrites don't; a stopping plan's `post-rewrite` list names twins.

## 7. Steps

1. **B1:** the plan in domain, with its tests.
2. **B2:** the check, with tests against `git rebase -i` (section 8).
3. **B3:** the run without stops, and main's own unpushed commits (decision 5).
4. **B4:** the run with stops: twins, the todo, `leaf-plan`, Back to the plan, choices 2 and 3.
5. **B5:** the editor, its menus, Drop commit… (choice 4), the strip.
6. **B6:** upstream's list and JGit's interactive start removed.
7. **B7:** `CLAUDE.md`, the changelog, a demo repository for a manual run.

Each step is its own commit (or two, code then docs), shown to you before it's made.

## 8. Tests

- **Domain:** the plan's rules, groups, moves, `fixup!` placement, which rows stop, the footer's lines, Start's label.
- **Data, against git in twin repositories:**
  - random plans (reorder, drop, join, reword, edit; onto the base or not) on generated branches: where git's
    `rebase -i` stops, the check stops at the same commit with the same files; where it doesn't, Leaf's run gives the
    same trees, messages, authors and author dates (committer dates fixed), and the same empty commits left out;
  - the stop path: twins, Continue from Leaf and from a terminal, Skip, Abort, Back to the plan, `--autostash`, a branch
    in another worktree, a branch checked out nowhere, `rebase.missingCommitsCheck=error`, `rebase.updateRefs=true`,
    signing, `post-rewrite`;
  - a partial clone: the check downloads nothing.
- **App, rendered:** the editor in each state of the mockup, the keys, drag, the panel, the Drop dialog, the strip.
- **Done** (`PLAN.md`): a manual run on macOS against a demo repository, and `CHANGELOG.md`.

## 9. Not in B

- Merges among the commits that a plan replays: refused, as replaying one means merging again (git's `--rebase-merges`).
  Merges after the rewritten commits are kept, as in part A, when nothing replays them.
- Branches stacked on this one (`--update-refs`): they keep the old commits, as with `git rebase`.
- `exec` lines, and C, drag and drop in the log.
