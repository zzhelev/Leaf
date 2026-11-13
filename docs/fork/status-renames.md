# Renames in Staged and Unstaged (upstream issue 347)

| | |
|---|---|
| Question | Should a moved file show as one rename row in Staged, and in Unstaged, as Leaf's commit lists already do? What does each action keyed by a path do with one? |
| Verified against | `main` at `7df36a13`; JGit 7.7.0; git 2.54.0 |
| Date | 2026-11-12 |
| Status | Decided with the user on 2026-11-12: A, Undo the move, blame and history from the old path, intent-to-add fixed first on its own (section 7). Built the same day (section 9); the cost is still to be measured on a quiet machine |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

**Mockups:** the Design artifact "Leaf renames in Staged" (https://claude.ai/artifact/3dBPxTEV6Yp4EkJY83YdVV), in Tonal,
at the changes pane's default 330 px, with Leaf's own paths. Boards: Today, A (light and dark), B, the diff of a staged
rename, a Staged rename row's menu (A), an Unstaged rename row's menu (B), and "Undo the move".

## Summary

- **Today:** a staged move (`git mv`, or a move that was then staged) lists as a deletion and an addition in Staged,
  where `git status` says `R old -> new`. Leaf's commit lists, comparisons and the review show renames since 2026-10-19
  (`CLAUDE.md`, Renames in file lists). Upstream now pairs files in both lists, at every status, without a limit or a
  time budget, and pairs a deleted file with an untracked one in Unstaged, which git doesn't.
- **What git does** (probes, section 1): it pairs only on the staged side, HEAD against the index. A file moved outside
  git, or moved and only half staged, shows as a deletion and an untracked file. The one pair in the working tree's
  column is a file added with `git add -N` (intent to add).
- **JGit 7.7 agrees** when its rename detector runs on the staged side at git's score of 50: the same pairs in every
  probe. Its status has no renames, and its index scan includes a conflict's stages, which have to be left out.
- **Decided (2026-11-12, as recommended):** renames in Staged only, as `git status` shows them (option A, section 2).
  Detection runs only for the status that the lists show, on the files that Staged lists as added and deleted, and is
  kept until those change, so a refresh while an agent writes costs nothing (section 3). Every action on a rename row
  acts on both paths (section 5).
- **Found in passing:** JGit lists an intent-to-add file as added and as modified, so Leaf shows it in Staged, and
  JGit's commit stores it as an empty file, which git's commit leaves out (probe 4b). That is a bug of its own, with or
  without renames (decision D5).

## 1. What git and JGit report

From `docs/fork/probes/status-renames-probe.sh` and `StatusRenamesProbe.java`, which run in temporary repositories. A
file of 20 lines, `a.txt`, is moved to `b.txt` in each case. "JGit staged" is JGit's `RenameDetector` at score 50 on a
scan of HEAD against the index; "JGit unstaged" the same on the index against the working tree, untracked files included.

| # | Case | `git status --short` | JGit status | JGit staged | JGit unstaged |
|---|---|---|---|---|---|
| 1 | `git mv a.txt b.txt` | `R  a.txt -> b.txt` | added b, removed a | RENAME 100% | nothing |
| 2 | Moved, 3 lines edited, staged | `R  a.txt -> b.txt` (R085) | added b, removed a | RENAME 85% | nothing |
| 3a | `mv a.txt b.txt`, nothing staged | ` D a.txt`, `?? b.txt` | missing a, untracked b | nothing | RENAME 100% |
| 3b | Moved, only `git add b.txt` | ` D a.txt`, `A  b.txt` | added b, missing a | ADD b | DELETE a |
| 3c | `git rm a.txt`, b untracked | `D  a.txt`, `?? b.txt` | removed a, untracked b | DELETE a | ADD b |
| 4 | Moved, then `git add -N b.txt` | ` R a.txt -> b.txt` | added b, modified b, missing a | ADD b | DELETE a, MODIFY b |
| 4b | A new file, `git add -N` | ` A new.txt` | added and modified new.txt | ADD new.txt | MODIFY new.txt |
| 5 | `git mv`, then edited again | `RM a.txt -> b.txt` | added b, removed a, modified b | RENAME 100% | MODIFY b |
| 5b | `git mv`, then `rm b.txt` | `RD a.txt -> b.txt` | added b, removed a, missing b | RENAME 100% | DELETE b |
| 6a | Merge: theirs renamed a to b, ours edited a | `D  a.txt`, `UU b.txt` | removed a, conflicting b | RENAME and b's stages | b's stages |
| 6b | Merge: a clean rename c to d, a conflict in a | `UU a.txt`, `R  c.txt -> d.txt` | conflicting a, added d, removed c | RENAME c to d, and a's stages | a's stages |
| 6c | Merge: theirs renamed a to b, ours deleted a | `DU b.txt` | conflicting b | b's stages | b's stages |
| 7 | `cp a.txt copy.txt`, staged | `A  copy.txt` | added copy.txt | ADD | nothing |
| 8 | An empty file moved | `R  empty.txt -> empty2.txt` | added, removed | RENAME 100% | nothing |
| 9 | Two files with the same content, both moved | two `R`, each by its name | | the same two | nothing |
| 10 | Moved, 15 of 20 lines rewritten | `D  a.txt`, `A  b.txt` | added b, removed a | DELETE, ADD | nothing |
| 12 | `git mv src lib`, 3 files | three `R` | | the same three | nothing |

Also probed:

- **`status.renames=false`**, and `diff.renames=false` when `status.renames` isn't set, give `D` and `A` (case 11).
  `status.renames=copies` doesn't show a staged copy of an unchanged file.
- **Unstaging** (case 13): `git restore --staged a.txt b.txt` and JGit's reset of both paths leave ` D a.txt` and
  `?? b.txt`, the move undone in the index and kept on disk. Resetting only `b.txt` leaves `D  a.txt` staged and
  `?? b.txt`: half a move.
- **Discarding** (case 14, a staged move edited again): Leaf's discard of one path today (JGit's reset, then checkout)
  leaves `D  a.txt` and `?? b.txt`. JGit's reset and checkout of both paths bring `a.txt` back and leave `b.txt` on disk
  as an untracked copy, as JGit's checkout does nothing for a path that the index doesn't have. `git restore
  --source=HEAD --staged --worktree -- a.txt b.txt` brings `a.txt` back and deletes `b.txt`, its edits with it.
- **A hunk unstaged** (case 15) with a patch that names the new path on both sides, applied with `git apply --cached
  -R`, takes the hunk out of the index and keeps the rename (`RM`). It is what `hunkPatch` already writes for a RENAME
  entry: `DiffEntry.filePath` is the new path, and a rename isn't an ADD.
- **Blame** (case 16): Leaf's blame (`BlameCommand` with `setFollowFileRenames(true)`) of the new path gives every line
  as not committed, as HEAD has no such path. A `BlameGenerator` on the old path, with HEAD pushed first and then the
  moved file's content as the uncommitted version, gives each line that the move kept its old commit. **File history**
  (`log().addPath`) of the new path lists no commit; the old path's lists its history.

What git does, in short: a rename is a property of the staged side, worked out each time from HEAD and the index. A
deleted tracked file and an untracked file are never paired. Unmerged paths are never paired. Copies aren't shown.

## 2. Where renames show (decision D1)

**A. Staged only, as `git status` (recommended).** A rename row in Staged for each pair of a file that the index deleted
and one that it added, as section 1. Unstaged is as today: a file moved outside git shows as a deleted file in Changes
and a new one in Untracked, until both are staged, which Stage all, a selection or a folder does, and they turn into
one row in Staged.

- It shows what a terminal, an agent's `git status` and VS Code's Source Control view (which reads `git status`) show,
  and what the commit will record.
- Staged's rows are all in the index, so detection reads only blobs that are already there (section 3).
- No Unstaged action changes. Stage, Discard, Delete, Ignore, the Untracked group and Discard all keep their meaning.

**B. Unstaged too, as upstream does.** Unstaged also pairs a missing tracked file with an untracked one (case 3a).

- A move made in a terminal, an editor or by an agent with plain `mv` reads as a move before anything is staged, and
  Stage stages both paths in one click.
- It shows something that git doesn't: an agent or a terminal calls the same files a deletion and a new file.
- Its new file is untracked, so the row leaves the Untracked group for Changes, and every Unstaged action needs a rule
  for it. Stage: both paths. Hunk and line actions: none, as the index has no new path to patch (staging part of the
  new file would have to create it and delete the old one at once). Discard: brings the old file back and either
  deletes the new one, which Discard has never done (only Delete file and Discard all's box do), or leaves it. Delete
  file: deletes the new file, and the row turns back into a deleted file. Ignore: turns it back too. Discard all: has to
  count the new file as a new file or as part of a restore.
- Detection has to hash untracked files at every refresh, as they change while an agent writes, and compare their
  content from the disk (section 3).

## 3. What detection costs (decision D2)

Measured with Leaf's own `withRenames` (the stages of `diffWithRenames`: exact ids, then files of the same name, then
everything left within `diff.renameLimit`, 100 when unset, with its 2 s budget), on a clone of Leaf (2,310 files) in a
temporary repository, the median of five runs after a warm-up. The test that measured it is
`docs/fork/probes/StatusRenamesCostProbeTest.kt`.

**The machine was busy:** load average 168 on 14 cores, from other sessions' builds. There, with the 252 moved and
edited files staged, `git status` itself took 6 to 10 s on the same clone, and 2 to 5 s with `status.renames=false`. So
read the numbers as ratios; a quiet machine is several times faster. They are to be measured again before building.

| Scenario | Staged side | Unstaged side |
|---|---|---|
| 1 file moved with `git mv` | 2 ms, 1 pair | |
| 252 files moved (a folder), `git mv` | 3 ms, 252 pairs | |
| The same, each file's `package` line edited, staged | 762 ms, 252 pairs | |
| 100 files renamed and given a new import, staged | 350 ms, 100 pairs | |
| 150 files renamed and edited, staged (over the limit of 100) | 2 ms, only the 2 exact pairs | |
| 252 files moved with `mv`, outside git | | 553 ms, 252 pairs |
| The same, `package` lines edited | | 587 ms, 252 pairs |
| 100 files renamed and edited outside git | | 2,264 ms, **0 pairs**: out of time |
| 1 file deleted, 2,000 untracked files of 100 KB | | 4,661 ms, 0 pairs |
| 1 file deleted, 1 untracked file of 300 MB | | 1,582 ms, 0 pairs |

What the numbers say:

- **Exact moves cost nothing** on the staged side: the pairs are found by blob id, nothing is read.
- **Moves with edits cost hundreds of milliseconds,** from reading and hashing blob content, a lot of it from the fresh,
  loose blobs that `git add` wrote. At every status refresh, that would be paid each time a file is saved anywhere in
  the working tree, while nothing in the index changed.
- **The unstaged side is far worse:** before any pairing, every untracked file is hashed (the 300 MB log, the 2,000
  build outputs), and content pairs read the disk for each comparison. The 100 renamed files ran out of the 2 s budget
  with no pair, so whether a row shows as a rename would depend on how busy the machine is, and could change from one
  refresh to the next.

Proposed for A:

- **Only the status that the lists show** runs detection, as a step of its own after it (`RefreshDataUseCase`, STATUS).
  The other readers of the status don't: the file watcher's (twice), the log's (whether there are uncommitted changes),
  Stage all's and Discard all's checks.
- **The candidates come from the status:** Staged's added and removed paths, without unmerged paths (6a, 6b) and
  without intent-to-add entries (4, 4b). Their ids come from HEAD's tree and the index for those paths only, not from a
  second walk of the whole tree. No pair is possible, and nothing runs, unless both lists have a path, which is the
  usual state.
- **Kept until the candidates change:** the result is kept per tab, keyed by the candidates (each path with its blob id,
  and HEAD's tree). Saving a file in the working tree doesn't change them, so a refresh while an agent writes costs
  nothing; `git add`, a commit, a checkout or a reset do. A result that ran out of time is kept too, so rows don't
  change between two refreshes of the same index.
- **The same stages, limit and budget** as the commit lists (`withRenames`), with `status.renames` read first, then
  `diff.renames`, and `status.renameLimit`, then `diff.renameLimit`, as git reads them. The budget could be lower for
  the status, as it delays the lists; 2 s keeps the commit lists' rule. To decide with the numbers of a quiet machine.

What B would add: a size filter before hashing (an exact pair needs the same size as a deleted file, a content pair a
size within a factor of two), no untracked file above a size limit, a cache of untracked files' ids keyed by path, size
and modification time, and acceptance that pairs found from a file an agent is writing can come and go.

## 4. The model

- `StatusType.RENAMED`, so that every `when` over a status type has to say what a rename does (the compiler lists the
  places), and `StatusEntry.renamedFrom: RenamedFrom?` (`D/sorting/RenamedFrom.kt`, the commit lists' path and
  similarity). `filePath` stays the new path. A `paths` helper gives both paths to the actions that go by path.
- `toFileItems` sets `FileItem.renamedFrom` and `FileChangeKind.Renamed`, so the row, its tooltip, sorting, the three
  views and the search (which already matches the old path) are the commit lists' (`ChangedFilesList`).
- `DiffType.UncommittedDiff` keeps the entry. `GetDiffEntryFromStatusEntryGitAction` returns a RENAME entry with the old
  path's id from HEAD and the new path's from the index, and the diff pane reads both by id, as for a commit's file.

## 5. Each action on a rename row (option A)

| Action | What it does | How |
|---|---|---|
| The row | The rename icon (`→`), the new name, `← …/status/` (`RenamedFrom.label`), the directory; the tooltip "Renamed from <old path> (92% similar)". In the tree, under the new path's folder | `ChangedFilesList`, as a commit's row |
| Click, Up and Down | The diff from HEAD's old file to the index's new one, with `← old path · 92% similar` in the header, or "Renamed from X. The content is unchanged." | Both sides read by blob id |
| Unstage: the row's button, double-click, Enter, "Unstage N files", Unstage all, "Unstage 3 matches" | Both paths go back: the move leaves the index and stays on disk, so Unstaged shows the old file deleted and the new one untracked (case 13) | `reset` of both paths. The bulk targets (`bulkTarget`, `entriesShown`) list both paths for a rename row |
| A folder's Unstage, in the tree | The rows shown under the folder, each rename with both its paths | Explicit paths. Today it's `reset -- <folder>`, which would split a rename whose old path is outside the folder |
| Unstage a hunk or a line | Takes that part out of the index; the rename stays. With every hunk unstaged, it is an exact rename, which Unstage undoes | `hunkPatch` names the new path on both sides; `git apply --cached -R` (case 15) |
| "Undo the move…" (in place of "Discard file changes") | Decision D3. Recommended: the old file comes back as it is in HEAD, the new one leaves the index and the disk, after a confirmation that names both paths and says that its changes are lost | `git restore --source=HEAD --staged --worktree -- <old> <new>` (case 14) |
| Blame file | Decision D4. Recommended: the old path at HEAD, with the new file as the uncommitted version, so lines that the move kept show their commits | `BlameGenerator` on the old path, HEAD then the file's content (case 16) |
| File history | Decision D4. Recommended: the old path's history, with "before the move" in the menu. Leaf's file history doesn't follow renames, and the new path has none yet | `log().addPath(<old path>)` |
| Open file in folder, Copy absolute and relative path | The new path, the file that exists | As today |
| Delete file, Ignore | Not offered in Staged, as today | |
| Stage all, Discard all, the Untracked group | Unstaged only: unchanged | `StageAllGitAction` and `DiscardAllGitAction` match entries by equality against a fresh status, which runs no detection: they get Unstaged's entries, which have no renames in A |
| The amend group ("In the last commit") | Unchanged: it lists the last commit's files, with renames already | `GetCommitDiffEntriesUseCase` |
| Commit | Unchanged: JGit commits the index, and a rename is how the index is | |
| A staged move edited again (`RM`) or deleted again (`RD`) | Staged's rename row as above; Unstaged shows the new path modified or deleted, with its own actions as today | |
| Conflicts | An unmerged path never pairs (cases 6a to 6c), and its row is as today | |
| Counts ("Unstage 3 files", the header's count) | A rename row is one file, as `git status` counts it | |

## 6. Mockup

The Design artifact (link at the top). The rows keep the commit lists' look (option B of 2026-10-19's renames in file
lists): the hint goes right after the name, before the long directory, which is what gives way at 330 px. The menu of a
Staged rename row replaces "Discard file changes" with "Undo the move…", and File history carries "before the move" at
its right (`ContextMenu`'s `hint`). Option B's Unstaged rename row adds "Delete file" with "the new one", and has no hunk
or line buttons in its diff.

## 7. Decisions

**Decided with the user on 2026-11-12, from this document and the mockup:**

- **D1: A**, renames in Staged only, as `git status` shows them.
- **D3: (a)**, "Undo the move…": the old file back from HEAD and the new one deleted, after a confirmation that names
  both paths.
- **D4: (a)**, blame and file history from the old path.
- **D5: (a)**, intent-to-add files fixed first, as a piece of work of its own, before the renames.
- **D2** wasn't put as a question: the approach is the recommended one below (detection only for the shown status, on
  Staged's added and removed paths, kept until they change). The budget is set in step 1 of the build plan, from
  numbers taken on a quiet machine.

The options as they were put, recommended first:

- **D1. Where renames show:** A, Staged only, as `git status` (recommended); or B, Unstaged too.
- **D2. Cost:** detection only for the status that the lists show, on Staged's added and removed paths, kept until they
  change (recommended); the budget, 2 s as the commit lists or lower, decided after measuring on a quiet machine.
- **D3. Discarding a staged rename:** (a) "Undo the move…": the old file back from HEAD, the new one deleted, after a
  confirmation (recommended, as a move is one change); (b) the old file back and the new one left as an untracked
  copy, which Discard has never deleted; (c) no Discard on a rename row, only Unstage.
- **D4. Blame and file history:** (a) from the old path, as section 5 (recommended); (b) as today (blame says every line
  is uncommitted, file history is empty).
- **D5. Intent-to-add files:** (a) fix it first, on its own: such a file leaves Staged and shows in Unstaged as a new
  file, and Leaf's commit leaves it out, as git's does (recommended); (b) in this work; (c) leave it. In A, a move onto
  an intent-to-add file stays a deleted file and a new one in Unstaged, the one place where Leaf would differ from
  `git status` (case 4).

## 8. Build plan

0. Before it, on its own (D5): intent-to-add files leave Staged and show in Unstaged as new files, and Leaf's commit
   leaves them out, as git's does (probe 4b).
1. The model and detection, with the cache, in the data layer. Tests against `git status --porcelain=v2` for every
   case of section 1, and the cost scenarios of section 3 measured again on a quiet machine.
2. The rows and the diff (`toFileItems`, `GetDiffEntryFromStatusEntryGitAction`), rendered at 330 px in Tonal and
   Classic, light and dark.
3. The actions of section 5, each tested against what git leaves (index, disk, `git status`) in a `TestTab`.
4. Blame and file history, if D4 is (a).
5. A demo repository for a manual run.

## 9. Built (2026-11-12)

Steps 1 to 5 of section 8, as designed; step 0 was done first, on its own (`db942a46` on `main`). What differs from
the plan:

- **The budget stays 2 s**, the commit lists', as the machine was as busy as during the first measurement. With the
  result kept until the index changes, it is paid once per change of what is staged. Measuring on a quiet machine is
  still to do (`docs/fork/probes/StatusRenamesCostProbeTest.kt`).
- **A folder's Unstage** goes by its rows' paths only when a staged rename has a path under it; otherwise it is the
  folder's pathspec as before, which a sparse checkout needs (`UnstageByDirectoryGitAction`).
- **An old path added again with `git add -N`:** with step 0 in, Staged lists it as deleted (`DA` in git status), and git
  still pairs the move (`R  a -> b` and ` A a`, probed with git 2.54 after step 0 landed). The scan reads the index
  without its intent-to-add entries, as git does, for the pairs and for the rename's diff.
- **Blame** reads the moved file as `git add` would (a check-in tree walk, as JGit's own blame reads the working file),
  so filters and line endings apply.

Where each piece is: `CLAUDE.md`, Renames in Staged.

