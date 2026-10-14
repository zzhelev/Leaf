# Agent awareness (Phase 4)

| | |
|---|---|
| Question | How should Leaf tell which agent made a worktree, mark the worktrees that are done or forgotten, and clean up the merged ones? |
| Verified against | `main` at `976c96c5`; git 2.54.0; Claude Code 2.1.287 (a probe run); the other tools' docs and source, 2026-10-14 |
| Date | 2026-10-14 |
| Status | Approved 2026-10-14 with every recommendation in section 6; 4.1 and 4.2 done |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

## Summary

- **Three work items** (section 4): origin labels (4.1), the note on harness features (4.2, docs only), and stale
  markers with "Clean up merged worktrees…" (4.3, two branches: the markers, then the cleanup).
- **Labels come from markers first, folders second.** Claude Code, its desktop app and Codex leave a file in the
  worktree's git dir, which finds their worktrees wherever they are. Nine tools get built-in folder rules, and the user
  adds their own in Settings → Worktrees.
- **A worktree counts as merged only when it had work of its own.** A fresh agent worktree is in the base by ancestry
  too, so ancestry alone would offer to clean up an agent that just started.
- **Cleanup never loses commits.** It offers merged worktrees and missing ones, deletes their branches, and leaves out
  any worktree that a running agent session has locked.
- **No tool does what 4.3 does.** Their own cleanup goes by age or count (Claude Code, Cursor, Codex), or by archiving
  a session (Conductor, VS Code). None looks at whether the work reached the base, and Claude Code never removes a
  `--worktree` session it kept (section 2).

## 1. What exists

- **Worktree rows** (`A/ui/WorktreesSection.kt`, `WorktreeRowLines`, shared with the toolbar's picker): the folder name
  and lock icon and last commit's age, then the branch, a changes dot and `↑ahead ↓behind` against the base. The
  tooltip (`worktreeTooltip`) has the path, the changes, the comparisons, the lock reason.
- **Branch badges** (`A/ui/BranchWorktreeBadge.kt`): a folder icon and the worktree's folder name, left out when it
  repeats the branch's last part, as agents' folders do. So an agent's badge is usually the icon alone.
- **Reading the worktrees** (`GetWorktreesInfoUseCase`): `git worktree list`, then per worktree `git status`, the
  comparison to the base and the last commit's time, four worktrees at a time. `GetWorktreesGitAction` already reads
  each worktree's git dir (`worktreeHeads`, for rebases and bisects).
- **"Merged into the base"** (`GetBranchMergedGitAction`, 3.1): no commits outside the base, or
  `git merge-tree --write-tree` gives the base's tree, which finds a squash merge. Only the Remove dialog runs it.
- **Last change** (`GetWorktreeActivityGitAction`, 3.1): the newest of the git dir's `index`, `HEAD` and `logs/HEAD`
  and of the changed files. Only the dialogs run it, for "an agent may still be working".
- **Settings → Worktrees** has one setting, the refresh interval.
- **Running commands:** `WorktreeStep` lists, shown by `WorktreeCommands` and run by `RunWorktreeStepsUseCase`, as the
  Remove, Merge and Rebase dialogs do.

## 2. Where the tools put their worktrees

Checked against each tool's docs or source on 2026-10-14. Claude Code's markers come from a probe: one
`claude -p --worktree probe-wt` run in a throwaway repository.

| Tool | Folder | Branch | Marker in the worktree's git dir | Its own cleanup |
|---|---|---|---|---|
| Claude Code (CLI) | `<repo>/.claude/worktrees/<name>` | `worktree-<name>` | `CLAUDE_BASE` (the commit it started from); lock reason `claude session <name> (pid <n> start <date>)` | Clean unnamed sessions on exit; background and subagent ones after `cleanupPeriodDays` (30). Kept `--worktree` sessions never. |
| Claude desktop app | `<repo>/.claude/worktrees/<name>`, or a folder chosen in its settings | `claude/<name>` here (a "Branch prefix" setting), or detached | `claude-desktop-worktree` (empty) | Archiving a session, optionally after its PR merged |
| Codex (app, CLI, IDE) | `~/.codex/worktrees/<4 hex>/<repo>` (`git-worktree-root`) | detached; `codex/…` when the user creates one | `codex-thread.json` | Keeps the newest 15; archiving a chat |
| Cursor | `~/.cursor/worktrees/<repo>/<id>` | detached | none | Keeps 25 per machine, deletes anything under its folder |
| OpenCode (app) | `~/.local/share/opencode/worktree/<project id>/<adj>-<noun>` (`$XDG_DATA_HOME`) | `opencode/<adj>-<noun>` | none (`<common git dir>/opencode` holds the project id) | Deleting a workspace |
| Conductor | `~/conductor/workspaces/<repo>/<city>`; older: `<repo>/.conductor/<name>` | a prefix and the name | none | Archiving, optionally on merge |
| GitHub Copilot (VS Code, CLI) | `<repo>.worktrees/<branch>` next to the repository | `copilot/…`, `agents/…`, or named by the model | none | Archiving a VS Code session; the branch stays |
| Gemini CLI | `<repo>/.gemini/worktrees/<name>` | `worktree-<name>` | none | none |
| Cline CLI | `~/.cline/worktrees/<5 hex>/<repo>` | detached | none | none |
| Junie CLI | `../<repo>-junie-wt-01` | not documented | none | not documented |

Left out of the built-in rules, as their folders would match worktrees that people make by hand: Crystal
(`<repo>/worktrees/<name>`), Zed (`../worktrees/<repo>/<name>`) and worktrunk (`../<repo>.<branch>`). Roo Code's
repository was archived in May 2026. Antigravity and Amp create worktrees, but where isn't documented.

Two worktrees of this repository, made by the Claude desktop app, have `claude-desktop-worktree`, and one of them is on
`claude/resolver-model-f2582e`.

## 3. Harness features (plan item 4.2)

The plan asks to check whether the tools already copy ignored files into new worktrees before Leaf builds its own.
3.2 settled it: Leaf keeps no list of its own and reads `.worktreeinclude`, which Claude Code (CLI, subagents, desktop
app) reads too, in gitignore syntax, copying files that match and that git ignores. The other tools set up a new
worktree with their own files instead: Cursor's `.cursor/worktrees.json`, Conductor's setup script, Codex's
environment setup. Leaf doesn't read those, as they run commands.

4.2 adds this section and nothing else.

## 4. Work items

### 4.1 Origin labels (plan item 4.1)

**Detection,** per linked worktree (the main worktree gets none), first match wins:
1. The user's rules, so that they can correct a built-in one.
2. Markers in the worktree's git dir: `CLAUDE_BASE` (Claude Code), `claude-desktop-worktree` (Claude desktop),
   `codex-thread.json` (Codex). `GetWorktreesGitAction` reads them with the other files it reads there.
3. The built-in folder rules, in section 2's order:
   - `<repo>/.claude/worktrees/*` (Claude Code)
   - `~/.codex/worktrees/*/*` (Codex)
   - `~/.cursor/worktrees/*/*` (Cursor)
   - `~/.local/share/opencode/worktree/*/*` (OpenCode)
   - `~/conductor/workspaces/*/*` and `<repo>/.conductor/*` (Conductor)
   - `<repo>.worktrees/*` (Copilot)
   - `<repo>/.gemini/worktrees/*` (Gemini CLI)
   - `~/.cline/worktrees/*/*` (Cline)
   - `<repo>-junie-wt-*` (Junie)

**Rules:**
- A rule is a label and a folder pattern, matched against the worktree's whole path, ignoring case, as git gives it and
  as a real path (macOS gives temporary folders as `/var/…`, which are `/private/var/…`).
- `~/` is the home folder, `<repo>` the main worktree's folder and `<repo-name>` its name. `*` stands for one folder
  name or part of one, `?` for one character, and `**` for any number of folders. A pattern that starts with `**`
  matches anywhere (`**/aider-*`), and one that starts with none of `/`, `~/`, `<repo`, `**` or a drive is in the
  repository's folder: `.agents/x` is `<repo>/.agents/x`. The placeholders'
  paths match as they are, brackets and all.
- The user's rules are a list in Settings → Worktrees ("Worktree labels"): a label field, a pattern field and a remove
  button per row, then "Add label". The built-in rules follow, greyed out, so the list shows everything that's
  matched. Saved app-wide, as `worktree_labels` in `user_prefs.json` (`AppConfig.WorktreeLabels`).

**What shows:**
- **Rows:** a small tag after the folder name, before the lock icon and the age: `Claude`, `Codex`, `Cursor`,
  `OpenCode`, `Conductor`, `Copilot`, `Gemini`, `Cline`, `Junie`, or the user's label. The name ellipsizes first, and
  the toolbar's picker gets the same tag. Text, not logos: the tools' logos are trademarks.
- **Tooltip:** "Made by Claude Code (desktop app)", with how Leaf knows: "its git dir has `claude-desktop-worktree`",
  or "its folder matches `<repo>/.claude/worktrees/*`".
- **Branch badges:** the tag in place of the worktree's name when the name repeats the branch, which is the usual case,
  so `claude/fix-login` shows the folder icon and `Claude`. A `worktree-<name>` branch (Claude Code's CLI, Gemini CLI)
  counts as repeating its folder's name.
- **The side panel's filter** matches the tag: "codex" finds Codex's worktrees.

**Claude Code's locks:**
- Claude Code locks a worktree while its session runs. A `-p` run keeps the lock until Claude Code's own sweep: the
  probe's lock stayed after its process had exited.
- When the lock reason is `claude session <name> (pid <n> start <date>)`, Leaf checks the process as Claude Code does:
  it runs when a process with that pid exists and started at that time. Claude Code writes the time with
  `LC_ALL=C TZ=UTC ps -o lstart= -p <pid>` (found in its 2.1.287 binary); Leaf compares it with the JVM's
  `ProcessHandle` start time, to the second, which rules out a pid that another process got later. When either time
  is unknown, the pid decides.
- The tooltip and the Remove dialog say "Locked by a Claude Code session that is still running" or "…that has ended",
  in place of the raw reason. The dialog still asks before unlocking: the box reads "Remove it although its Claude
  Code session is still running" for a running one.

**Tests:** the matcher (placeholders, `*` and `**`, the user's rules first, a path with glob characters in it, real
paths), the marker reading in `GetWorktreesGitActionTest`, and the lock reason's parser and process check.

### 4.3a Stale markers (plan item 4.3)

**Merged:** a linked worktree on a branch other than the base, whose work is in the base:
- its branch has commits that the base doesn't, and merging it would change nothing (`merge-tree`, as 3.1): a squash
  merge; or
- its branch has no commits that the base doesn't, and it moved since the worktree started: a merge or a
  fast-forward.

Where it started: `CLAUDE_BASE`, or else the branch's first reflog entry when it reads `branch: Created from …`, which
`git worktree add -b` writes. A probe on git 2.54 showed the difference: a fresh branch has that one entry; one that
was committed to and fast-forwarded has two. Without either, an unmoved branch isn't marked: Leaf can't tell a fresh
worktree from a merged one.

A detached worktree isn't marked: the plan asks about branches, and Codex, Cursor and Cline, which make detached ones,
clean up their own.

**Untouched:** no change for N days, by 3.1's last change. The cheap part runs first: when the git dir's `index`,
`HEAD` and `logs/HEAD` are newer than N days, the worktree isn't untouched, and the changed files aren't looked at.

**Setting:** Settings → Worktrees, "Mark worktrees untouched for": never, 7, 14, 30 or 90 days, 14 by default
(`AppConfig.WorktreesUntouchedDays`).

**What shows:**
- **Merged:** "merged" in the added-file green in place of `↑ ↓` on the row's second line. The tooltip says how: "Its
  changes are in main (squash-merged)" or "Its commits are in main".
- **Untouched:** the age on the first line turns amber and reads "idle 21d". The tooltip says "No changes for 21 days".
- Dimming stays for missing worktrees only.

**Cost:** the list refreshes every 10 seconds while it's shown. `merge-tree` runs only for branches with commits of
their own, and its answer is kept per (base commit, branch commit) for the tab, as it can't change. The reflog and
`CLAUDE_BASE` are one file each.

**Tests:** fresh, committed, fast-forwarded, merged and squash-merged branches, from `git worktree add -b` and with
`CLAUDE_BASE`, in `TestTab`; untouched with file times set back; the cache.

### 4.3b Clean up merged worktrees

**Entry points:** "Clean up merged worktrees…" in the Worktrees header's menu (the base button's menu, which becomes
the section's menu), in a merged row's menu after Discard, and in the Actions list. Shown while something can be
cleaned up.

**The dialog** reads everything again when it opens, as Remove does, and lists:
- **merged worktrees,** checked;
- **missing worktrees** (prunable), checked, cleaned up with `git worktree prune`;
- **merged worktrees with changes,** unchecked, with the changes counted. Checking one adds `--force`;
- **merged worktrees changed in the last 5 minutes,** unchecked, with 3.1's notice;
- **merged worktrees locked by a running Claude Code session,** shown without a checkbox, as "In use by a Claude Code
  session". Other locks: unchecked, with the reason. Checking one unlocks it first, as Remove does.

Then "Also delete their branches", checked: `git branch -D`, as squash-merged branches fail `-d`. The base branch and
its local branch are never deleted. The tab's own worktree switches the tab to the main worktree first.

**Commands:** per worktree `git worktree remove [--force] <path>` and `git branch -D <branch>`, then
`git worktree prune` once, shown and run as one `WorktreeStep` list. It stops at the first failure and refreshes, as
the other dialogs do.

Untouched worktrees that aren't merged aren't offered: removing them loses commits, which Discard asks about one
worktree at a time.

**Tests:** the candidates and their checkboxes from a `WorktreeList` (domain), the steps, and a `TestTab` run with three
worktrees: squash-merged, merged with changes, and fresh, which isn't listed.

## 5. Out of scope

- Logos. Labels are text.
- Grouping the Worktrees section by tool.
- Reading the tools' settings for their folders (Codex's `git-worktree-root`, the Claude desktop app's location,
  `$XDG_DATA_HOME`). Markers cover Claude and Codex wherever they are; the user's rules cover the rest.
- Running the tools' own cleanup, and the tools' setup files (`.cursor/worktrees.json`, Conductor's scripts).
- Detached worktrees in 4.3.
- Branches that no worktree uses. "Clean up merged worktrees" is about worktrees; a branch cleanup is its own feature.

## 6. Decisions to make

1. **Where the label shows:**
   - a text tag on the row's first line and on branch badges (recommended);
   - an icon per tool in place of the folder icon (logos are trademarks, letters are hard to read);
   - the tooltip only.
2. **Detection:**
   - the user's rules, then markers, then built-in folders (recommended);
   - folders only, as the plan says.
3. **Built-in folders:**
   - the nine tools with folders of their own (recommended);
   - also Crystal, Zed and worktrunk, whose folders match hand-made worktrees.
4. **The user's rules:**
   - a list in Settings → Worktrees, built-in rules shown greyed (recommended);
   - one text field with a rule per line.
5. **What counts as merged:**
   - work of its own that reached the base; fresh worktrees aren't (recommended);
   - in the base by ancestry, which marks every fresh worktree.
6. **Untouched after:**
   - 14 days by default (recommended);
   - 30, as Claude Code's sweep;
   - 7.
7. **How stale shows:**
   - "merged" in green in place of `↑ ↓`, an amber "idle 21d" in place of the age (recommended);
   - an icon for each;
   - dimming the row, which missing worktrees use.
8. **What cleanup offers:**
   - merged and missing worktrees, with changes or locks unchecked, running sessions left out (recommended);
   - merged worktrees without changes or locks only;
   - also untouched worktrees that aren't merged, unchecked.
9. **Their branches:**
   - deleted too, checked by default (recommended);
   - unchecked by default.
10. **Order:** 4.1, then 4.3a, then 4.3b, a branch each, and 4.2 with 4.1's docs (recommended).
