# Worktree lifecycle actions (Phase 3)

| | |
|---|---|
| Question | How should Leaf create, review, merge, rebase, remove and discard worktrees, and run a merge tool in them? |
| Verified against | `main` at `ee50fb6a`; JGit 7.7.0; git 2.54.0 |
| Date | 2026-10-12 |
| Status | Approved 2026-10-12 with every recommendation in section 5; 3.1 to 3.4 done |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

## Summary

- **Six work items, one branch each** (section 3). The recommended order starts with the actions that clean up
  worktrees, since agents already create plenty of them:
  1. remove, discard, prune, lock and unlock;
  2. create;
  3. review;
  4. merge and squash-merge into the base;
  5. rebase onto the base;
  6. the external merge tool.
- **Every action runs the git CLI,** and its dialog shows the commands. The dialog and the action read them from the
  same list, so what is shown is what runs (section 2.1).
- **Copying ignored files uses `.worktreeinclude`,** the file that Claude Code, Codex, Conductor, worktrunk, Roo Code
  and PostHog Code already read. Leaf keeps no list of its own, as Phase 4.2 asks (section 3.2).
- **What the probes showed** (`docs/fork/probes/lifecycle-probe.sh`, against throwaway repositories):
  - Leaf's Continue, Skip and Abort (JGit) finish a rebase that the git CLI started in a linked worktree.
  - After `git merge --squash`, JGit's commit leaves `SQUASH_MSG` behind; git's own commit deletes it.
  - A squash merge has no repository state: JGit says SAFE and `git merge --abort` refuses. `git reset --merge`
    undoes it, conflicts included, and keeps untracked files.
  - `git merge-tree --write-tree <base> <branch>` tells a squash-merged branch from an unmerged one; ancestry alone
    can't.
  - `git worktree remove` deletes ignored files without `--force`, refuses untracked and modified files, needs
    `-f -f` for a locked worktree, and removes a worktree whose folder is already gone.

## 1. What exists

- **Worktree actions:** only reads. `GetWorktreesGitAction` (`git worktree list --porcelain -z`), status, ahead and
  behind, commit times, the base branch. Nothing runs `git worktree add`, `remove`, `prune`, `lock` or `unlock`.
- **Worktree row menu** (`A/ui/WorktreesSection.kt`, `worktreeContextMenuItems`): Switch to worktree, Open in new tab,
  Copy path.
- **Confirmations:** `ConfirmActionDialog` shows one code snippet of at most three lines, with a button text fixed per
  action. It can't show a list of commands. `DeleteRefDialog` has the two-step "Delete", then "Delete anyway".
- **Merge** (`MergeBranchGitAction`, JGit): merges the clicked branch into the tab's branch, FF or no-FF from one
  app-wide setting. No squash.
- **Rebase** (`RebaseBranchGitAction`, JGit): rebases the tab's branch onto the clicked branch. Every use case runs in
  the tab's worktree: `UseCaseExecutor` passes the tab's git dir.
- **Commit message:** the status pane's commit field. There is no commit dialog. `GetPersistedCommitMessagesGitAction`
  reads `SQUASH_MSG`, but nothing uses it.
- **Two-commit diff (Gitnuro#206):** not in 2.0. The log selects one commit (`SelectedItem`), and Files changed diffs a
  commit with its first parent. The diff pane reads both sides by blob id, so it can show any two trees' diff.
- **Conflicts:** edit, stage, then Continue or Commit. The banner (`OperationInProgressBanner`) has Abort. Nothing
  runs `git mergetool`.
- **A tab whose worktree is removed** keeps the dead git dir until it's reopened (`repositoryOfRemovedWorktree` only
  runs on open). Phase 2b left this for Phase 3.

## 2. Shared pieces

### 2.1 Commands that the dialog shows and the action runs

- A domain model lists the steps of an action:
  - a git command: the folder it runs in and its arguments;
  - a step that isn't a git command: switching the tab to another worktree, copying ignored files, writing the squash
    message.
- The use case builds the list from the dialog's choices. The dialog renders it, and the same list runs on confirm.
- **Display:** as shell lines that can be copied into a terminal, a `cd` before each change of folder, paths quoted
  the shell's way:

  ```
  cd ~/dev/leaf
  git worktree remove ../leaf-agent1
  git branch -D agent1
  ```

  The other steps show as sentences, such as `# Switch this tab to leaf`.
- **A new dialog,** `WorktreeActionDialog` (fork-only), on `IconBasedDialog`:
  - what the action affects;
  - warnings;
  - the action's checkboxes;
  - the command block, monospace, selectable and scrolling.
- **Running:** a foreground task (`executeLaunch`), so it gets the ProcessingScreen, a toast, and an `ErrorDialog` with
  git's stderr. The commands stop at the first failure, and the error says which one failed.
- `git worktree add`, merges and rebases run hooks and LFS filters, so they get no 30-second timeout. The task has
  Cancel, as remote operations do.

### 2.2 Running in another worktree

- The git actions take the folder to run in, not the tab's git dir.
- Where the result needs the user (staged changes to commit, conflicts), the tab switches to that worktree first, with
  Phase 2b's switch. The dialog lists that step.

### 2.3 "An agent may still be working there"

- **The signal:** the newest modification time among the worktree's `index`, `HEAD` and `logs/HEAD`, and the files
  `git status` lists.
- **When it warns:** a change in the last 5 minutes.
- **Where it shows:** the Remove, Discard, Merge and Rebase dialogs, for example "agent1 changed 2 minutes ago. An
  agent may still be working in it."
- Unconfirmed: whether Claude Code locks its worktrees while an agent runs. A lock would be a stronger signal.

### 2.4 "Merged into the base"

- **The test:** the branch is merged when its tip is an ancestor of the base, or when merging it would change nothing
  (`git merge-tree --write-tree <base> <branch>` prints the base's tree).
- **Why both:** the second test catches squash-merged branches. In the probe, a branch was "merged" after its squash
  merge and "not merged" once it got another commit.
- `merge-tree --write-tree` needs git 2.38. Leaf accepts 2.36, so on older versions only ancestry counts.

## 3. Work items

### 3.1 Remove, discard, prune, lock and unlock (plan items 7, 8, 9)

**Menu** (worktree rows): after Open in new tab, a separator, then Lock… or Unlock, Remove…, Discard…. A missing
worktree's row adds "Prune missing worktrees…". Nothing for the main worktree or a bare repository's.

**Remove…** runs `git worktree remove <path>`.
- When the dialog opens, Leaf runs a fresh `git status` in the worktree and checks recent activity. The list may be
  stale.
- **Uncommitted changes:** Remove stays disabled until "Delete the uncommitted changes too" is checked, which adds
  `--force`.
- **Locked:** the dialog shows the reason. Remove stays disabled until "Remove it although it's locked" is checked,
  which runs `git worktree unlock <path>` first. git's own override, `--force --force`, would also delete changes
  that an agent made after the dialog checked, which git refuses otherwise (changed while building it).
- **"Also delete the branch":** unchecked by default. It adds `git branch -D <branch>`, with a line saying whether the
  branch is merged into the base (2.4). If it isn't, a warning says how many commits only that branch has.
  - It's `-D`, because `-d` checks HEAD or the upstream, not the base. A squash-merged branch would always fail it.
- **Ignored files:** a note says git deletes them too (build output, `.env`).
- **The tab's own worktree:** the tab switches to the main worktree first.

**Discard…** opens the same dialog, with force and the branch always on, so it shows no boxes for them, and lists
what's lost. If anything would be (uncommitted changes, or commits not in the base), the button turns into "Discard
anyway" after the first click, as `DeleteRefDialog` does.

**Prune missing worktrees…** lists what `git worktree prune --dry-run -v` would remove, then runs
`git worktree prune`.

**Lock…** asks for an optional reason, then runs `git worktree lock [--reason <reason>] <path>`. **Unlock** runs
`git worktree unlock <path>` without asking, since nothing is lost.

**A tab whose worktree disappears** switches to the main worktree, with a toast. Leaf may have removed it from another
tab, or a terminal or an agent did. This closes Phase 2b's open item.

### 3.2 Create (plan item 1)

**Entry points:**
- a "+" in the Worktrees header, shown on hover like the Remotes header's;
- "New worktree…" in a branch's menu:
  - a local branch that no worktree uses is checked out there;
  - a remote branch gets a new local branch that tracks it;
- "New worktree from commit…" in the log's commit menu;
- a "New worktree…" row at the end of the toolbar picker.

**Dialog:**
- **Start point:** read-only, from where it was opened. From "+" and the picker, it's the base branch, or the tab's
  branch (or commit) when there's none.
- **Branch:** a new branch's name, checked with `git check-ref-format --branch` and against existing branches. From an
  unused local branch: "Check out agent1" or "New branch from agent1". From a remote branch, its name to start with.
- **Folder:** follows the branch's name and the repository's location (below) until the user types one. Paths in the
  home folder show as `~/…`, so the field shows the worktree's own folder, and `~/…` can be typed. A folder that
  exists and isn't empty, or a path that isn't absolute, is refused before git sees it.
- **Location (per repository, saved in `<common git dir>/leaf`, `worktrees.newWorktreeFolder`):** next to the
  repository, `../<repo>-<branch>`, the default; or a folder chosen with the field's button or "Choose a folder",
  `<folder>/<repo>-<branch>`. "Next to the repository" goes back. It's saved when a worktree is created. `/` in a
  branch name becomes `-`.
- **Copy ignored files:** "Copy the N ignored files that `.worktreeinclude` lists", with the files, checked when the
  file exists (decision 4). Without the file there's no checkbox. Instead a note explains what the file is for, and
  "Create `.worktreeinclude`" writes one with comments and examples, then opens it. "Edit `.worktreeinclude`" opens
  an existing one. The dialog reads the file again when Leaf's window gets the focus back.
- **"Switch to the new worktree":** checked by default, and remembered per repository with the location
  (`worktrees.switchToNewWorktree`).
- **Commands:**
  - `git worktree add -b <branch> <folder> <start point>`, `git worktree add <folder> <branch>` to check one out;
  - `--track` for a remote branch picked in a menu, `--no-track` for a remote base branch (an agent's branch
    doesn't push to `main`);
  - then comments for copying the files and switching the tab.

**Copying, matching Claude Code and Codex:**
- Only files that `.worktreeinclude` matches and git ignores are copied, from the main worktree. git decides both:
  `git ls-files --others --ignored --exclude-from=.worktreeinclude` lists the untracked files that the patterns
  match, and `git check-ignore --stdin` keeps those that git ignores. A tracked file is never listed.
- Never from another worktree's folder: git lists a nested worktree (`.claude/worktrees/x`) as its folder, without
  entering it, and such entries are skipped.
- Never a symbolic link, never over an existing file, and never out of either worktree, through `..` or a linked
  folder.

### 3.3 Review (plan item 2)

**"Review changes"**, first in a worktree row's menu when the worktree is on a branch other than the base, shows in the
right-hand pane, where Files changed is:
- the header "agent1 compared with main", "Changes on agent1 since it branched off main", the counts ahead and behind,
  and a close button;
- the commits that the base doesn't have, by subject and short hash, oldest first (the newest 1000);
- the changed files, as `git diff <base>...<branch>`, with Files changed's list view and sort settings.

**A file** opens in the centre diff pane, like a commit's file. The entry diffs the merge base's tree with the
branch's tree, and the diff pane reads blobs by id, so `FormatDiffGitAction` needs no change.

**Uncommitted changes** in that worktree aren't part of a merge, so they aren't in the list. A line says how many there
are, with a link that switches to the worktree, or "Show them" for the tab's own worktree.

**State:** a comparison held next to the selection, not a new `SelectedItem` variant. `SelectedItem` is Gitnuro's, and
its `when`s are spread through Gitnuro's code. While the review shows, the selection is `None`. Selecting anything else
closes it; its close button and Esc select again what was selected before.

**Kept up to date:** the comparison is read again when the branch's or the base's commit moves, as an agent keeps
committing. The last one stays on screen meanwhile.

**The subtitle:** the mockup said "as a merge would bring them", which isn't true of a squash-merged branch. The base
already has its changes, but the three-dot diff still lists them, as git's does.

**Gitnuro#206** (comparing two commits) isn't part of this item, but the panel takes any two refs, so it can build on
it later.

### 3.4 Merge and squash-merge into the base (plan items 3, 4)

Done on 2026-10-12 (branch `feat/worktree-merge`), as below. Where it differs from the first design, the reason is
given.

**Menu:** "Squash-merge into main…" and "Merge into main…", after "Review changes", on a worktree row whose branch is
neither the base nor the target.

**Target:** the base's local branch. A base of `origin/main` merges into `main`, and without a local branch the items
aren't shown.

**Where it runs:** in the worktree that has the target checked out. If that isn't the tab's, the tab switches to it
first. Then the staged result, the message and any conflicts show in the tab.

**If no worktree has the target checked out,** the tab's worktree checks it out first (`git switch main`), a step that
the dialog's commands show, with a note. Only when that worktree has no changes to tracked files, and isn't the
branch's own. Untracked files don't stop it: git refuses to overwrite them. (Decided at implementation: a step and a
note rather than a checkbox, as the dialog's commands are what the user confirms.)

**Checks** (git 2.54 probes, in the tests):
- **Refused:**
  - the branch has no commits that the target doesn't have;
  - for a squash, the branch's changes are already in the target (squash-merged before), as the squash would stage
    nothing. A merge is allowed, and records the history;
  - the target worktree has staged changes or conflicts: `git merge --squash` takes staged changes into the squash
    without a word, and a plain merge refuses them. Also when its status can't be read, or its folder is missing;
  - a worktree rebases the target, or bisects from it.
- **Warnings:** the target worktree's unstaged and untracked changes (git stops if they overlap); the branch's
  worktree's uncommitted changes, which aren't merged; recent activity (2.3).

**How git names the branch:** its short name, unless `git rev-parse --symbolic-full-name` resolves it to something
else: with a tag named `agent1`, `git merge agent1` merges the tag. Then the full name, and git's message says
`Merge branch 'refs/heads/agent1'`.

**Conflicts:** git exits with 1 when a merge stops at conflicts, but also when it refuses a squash merge (a plain
merge refuses with 2). So a merge has stopped at conflicts only with exit code 1 and unmerged paths. Then the task's
toast warns, and the banner stays.

**Squash:**
- Runs `git merge --squash <branch>`. Then Leaf writes its own message to `SQUASH_MSG`, so a commit from a terminal
  gets it too.
- **The message** (decision 7): a branch with one commit gets that commit's whole message. Otherwise the branch's
  name, then one `- subject` line per commit, oldest first.
- **In progress** while `SQUASH_MSG` exists and something is staged or conflicted. git records nothing else: no
  `MERGE_HEAD`, and JGit's state is SAFE. A squash that stops at conflicts writes `MERGE_MSG` too.
- **The commit field** shows `SQUASH_MSG` meanwhile, as it shows `MERGE_MSG` while merging, and saves edits to it. The
  user commits with the usual button, which is disabled while files have conflicts, as git refuses then.
- **After Leaf's commit,** Leaf removes what git's commit removes and JGit's leaves: `SQUASH_MSG`, `MERGE_MSG`,
  `MERGE_MODE` and `AUTO_MERGE`. That's every commit's, not only a squash's, except while a rebase goes on. A
  `MERGE_MSG` left there would be the message of the next commit made in a terminal.
- **The banner** says "Squash merge staged. Review the changes, then commit." Its Abort runs `git reset --merge`, which
  keeps unstaged changes, and refuses while a file that the squash changed has some. The banner doesn't name the
  branch, as the first design did: git doesn't record it.

**Merge:**
- Runs `git merge --no-ff --no-commit <branch>`. Leaf's existing merge UI takes over: `MERGE_MSG` in the field, the
  Merge button, the banner.
- The message is git's ("Merge branch 'agent1'").

**Found while building it:** the commit field filled each of its messages from its file only while blank, and a
commit cleared the field shown after the commit's refresh. So the old merge message showed at the next merge, and a
squash that stopped at conflicts left its `MERGE_MSG` in the merge field. A commit now clears the field it committed
from, and a merge's or a squash's field is cleared once its file is gone.

### 3.5 Rebase onto the base (plan item 5)

**"Rebase onto main…"** on a worktree row whose branch isn't the base, and isn't detached. It runs `git rebase <base>`
in that worktree, with the base as chosen (`origin/main` too).

**Uncommitted changes** in that worktree: refused. git refuses too, and Leaf doesn't autostash an agent's work in
progress.

**Recent activity:** a warning, with "Rebase anyway".

**The tab** stays where it is when the rebase succeeds. When it stops at conflicts, the tab switches to that worktree,
where the banner and Continue, Skip and Abort work on it (probe).

### 3.6 External merge tool (plan item 6)

**Where:**
- "Open in merge tool" on a conflicted file (the Unstaged menu);
- "Merge tool" in the banner, for every conflicted file.

**What it runs:** `git mergetool --no-prompt [-- <file>]`, in the tab's worktree, which is where conflicts show.
- It has no timeout, and runs as a task with Cancel while the tool is open. Then it refreshes.
- The login shell's PATH finds Homebrew's tools, such as Meld.

**Without `merge.tool`,** the items are disabled, with a tooltip saying to set it. Otherwise git would pick from its own
list, such as `opendiff`.

**The user's config applies:** `mergetool.keepBackup` and `mergetool.<tool>.trustExitCode` are read as git reads them.

## 4. Out of scope

- Comparing two commits from the log (Gitnuro#206). The review panel can host it later.
- Phase 4: origin labels, stale markers, "Clean up merged worktrees".
- `git worktree move` and `repair`, and bare repositories' worktrees.
- Claude Code's `WorktreeCreate` hook. It replaces Claude Code's own `git worktree add`, which Leaf doesn't run.

## 5. Decisions to make

1. **Order:**
   - start with remove, discard, prune, lock and unlock (recommended);
   - or follow the plan's order, starting with create.
2. **How commands show:**
   - `cd <folder>`, then plain `git` lines (recommended);
   - or `git -C <folder> …` on each line.
3. **The list of ignored files to copy:**
   - `.worktreeinclude` (recommended);
   - a list of Leaf's own in `<common git dir>/leaf`, as the plan says;
   - both.
4. **Copying ignored files by default:**
   - on when `.worktreeinclude` exists (recommended, as Claude Code always copies);
   - or off every time, as the plan says.
5. **The location of new worktrees:**
   - two choices, remembered per repository: next to the repository, or in a chosen folder (recommended);
   - or a pattern field with `<repo>` and `<branch>`.
6. **After creating:**
   - switch the tab, remembered (recommended);
   - open a new tab;
   - stay.
7. **The squash message:**
   - a subject or the branch name, then the subjects (recommended);
   - or git's `SQUASH_MSG` ("Squashed commit of the following:" with every commit's full log).
8. **A plain merge:**
   - always a merge commit, `--no-ff --no-commit` (recommended);
   - or the Fast-forward setting decides.
9. **Merging into a branch that another worktree has:**
   - switch the tab there first (recommended);
   - or run it there, and leave the tab.
10. **A rebase that stops at conflicts:**
    - switch the tab to that worktree (recommended);
    - or stay, with the toast saying where the conflicts are.
