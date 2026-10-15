# Built-in conflict resolver

| | |
|---|---|
| Question | Should Leaf resolve conflicts itself, with three panes in the style of Meld and the JetBrains merge dialog, and how? |
| Verified against | `main` at `ee50fb6a`; JGit 7.7.0; git 2.54 |
| Date | 2026-10-12 |
| Status | Approved 2026-10-12: Option A by default, with a switch to Option B, and every recommendation in section 6. Step 1 (the model and its git actions), step 2 (the read-only Columns view) and step 3 (the Stacked layout and the switch) built the same day, on `main` at `28cceb84`. Step 4 (region editing and one undo history) built 2026-10-13 (section 8). Step 5 (the choice cards) built 2026-10-13 (section 9). |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

Launching an external tool through `git mergetool` is a separate proposal. This note covers only the built-in resolver,
and how the two sit together (section 4).

## Summary

- **Layout:** Option A ("Meld columns") by default: Ours | Result | Theirs, with a gutter of arrows between the
  columns. A switch in the resolver's toolbar changes to Option B (both sides on top, the result below), for portrait
  monitors. Both take the whole tab, as file history does. The result starts auto-merged, and each conflict waits for
  an arrow or for typing. Option C (the result only, with a card per conflict) wasn't picked. See section 5.
- **Data:** the three versions come from the index (stages 1, 2 and 3, which `stagesOf` in
  `DA/git/conflicts/IndexConflicts.kt` reads). The chunks come from JGit's `MergeAlgorithm`, the content merge that
  Leaf's own merges already use. The result starts as base plus every change that doesn't conflict. Before opening,
  Leaf checks the file on disk for conflict markers, so it never silently throws away an edit, an agent's work or a
  `git rerere` resolution.
- **Saving:** Save writes the file, with conflict markers for the conflicts still open. Mark resolved saves and
  stages, and is only offered once no conflict is left. Deleted files, binaries, LFS files, submodules and symbolic
  links get a choice instead of three panes: the same choices `git mergetool` offers.
- **The hard part is the editable centre.** Compose Desktop has no code editor component. Meld, JetBrains, Sublime
  Merge, SmartGit and VS Code all build on one. The design draws the three columns as rows aligned with filler lines,
  as Leaf's split diff already does, and edits one region of the result at a time (section 3).

## 1. What Leaf has today

- Conflicted files show in Unstaged (`StatusType.CONFLICTING`), and `OperationInProgressBanner` counts them and offers
  Abort.
- Selecting one shows the working file, markers included, against ours (`unmergedDiffEntry`). It's read-only, and
  conflicted files get no hunk or line actions.
- Resolving means editing the file elsewhere, then staging it. Double-clicking a conflicted entry stages it right away
  (`StatusAction.EntryAction`), markers and all.
- Nothing in Leaf reads stage 1 or 3, picks a side, or writes a merge result.

## 2. Data

### 2.1 The three versions and the chunks

- Read the stages from the index as `unmergedDiffEntry` does: stage 1 is the base, 2 is ours and 3 is theirs. Each is a
  blob and a mode. Load them as `RawText`, which keeps the bytes.
- Merge them in memory with `MergeAlgorithm(DiffAlgorithm.getAlgorithm(<diff.algorithm>))`. The result is a list of
  `MergeChunk`s, each a line range of one version:
  - `NO_CONFLICT` names the version its lines come from: base for unchanged lines, ours or theirs for a change that
    only one side made (or that both made the same way);
  - a conflict is three chunks in a row: `FIRST_CONFLICTING_RANGE` (ours), `BASE_CONFLICTING_RANGE` and
    `NEXT_CONFLICTING_RANGE` (theirs).
- The resolver keeps that list, with a state per chunk: applied or not for a change that doesn't conflict;
  unresolved, ours, theirs, ours then theirs, theirs then ours, base (neither side's change) or edited for a conflict.
  The result is the chunks' text in order. This is the JetBrains model. It knows where every line came from, so a
  one-sided change can be undone, the base is always there, and the columns line up chunk by chunk without diffing the
  result.
- JGit's `ResolveMerger` merges file contents with this same `MergeAlgorithm`. So for a merge, rebase, cherry-pick,
  revert or pull that Leaf ran, the resolver shows the same conflicts as the markers in the file.
- For conflicts that git left (a terminal, an agent), the boundaries can differ. A probe, run against git 2.54:
  - both sides changed lines 3 to 5, the same way at lines 3 and 5. JGit, `git merge` and `git merge-file` all left a
    conflict on line 4 only, with `X3` and `Z5` merged;
  - with `merge.conflictStyle=diff3`, git kept lines 3 to 5 in the conflict, as diff3 doesn't trim. `zdiff3` trims;
  - git joins two conflicts that are three lines apart or less into one, and splits a conflict whose sides have four
    lines or more alike in its middle. JGit does neither: two conflicts two lines apart were one for `git merge-file`
    and two for JGit, and a conflict with four lines alike in its middle was two for git and one for JGit.
- **Built (step 1), in `DA/git/conflicts/TextMergeChunks.kt`.** JGit's chunks give their lines in one version only: a
  change by one side carries that side's lines, not the base's lines it replaced or the other side's. So the merge's
  parts are found again from the two diffs that `MergeAlgorithm` walks (base to ours, base to theirs), with the same
  comparisons in the same order: lines that neither side changed, one side's edit, and edits of both sides that overlap
  or touch. Each part takes JGit's chunks for it, which must fit, and gets its lines in all three versions.
  - Lines that ours and theirs have alike at a conflict's start and end, which JGit merges as ours, become changes by
    both, or unchanged lines when the base has them too.
  - JGit cuts a conflict's base lines by as many lines as that start and end have. With an empty base (added by both)
    that gives `[1,-1)`, and with a short one a range that starts after it ends. The model clamps it to the part.
  - Myers can return two edits of one side that touch (a deletion, then an insertion), so a change by one side can
    sit right after a conflict. Changes by the same side that touch are joined into one.
  - Checked on 10,000 random merges with each algorithm: with every conflict open, the result is what JGit's
    `MergeFormatter` writes, in both styles; taking ours (or theirs) everywhere is JGit's merge that favours that side;
    and keeping only ours' changes gives ours back byte for byte, and the same for theirs and the base.
- **Diff algorithm:** `diff.algorithm`, as `ResolveMerger` reads it. JGit only knows `myers` and `histogram`; Leaf's
  `GitCompatibleConfigGetter` reads git's `default` and `minimal` as myers and `patience` as histogram, for its merges
  and for the resolver. A value that git refuses too gives the resolver histogram, JGit's default.

### 2.2 The file on disk

The model starts from the stages, so it ignores what happened to the file since the conflict. Before opening, Leaf
looks for conflict markers in the working file, as rerere does: `<<<<<<<`, `=======` and `>>>>>>>` in order, at the
start of a line, with the `conflict-marker-size` attribute's length (7 by default).

| The working file | The resolver |
|---|---|
| Has as many conflicts as the model | Opens from the stages. The usual case. |
| Has no markers left (resolved by hand, by an agent, or by `git rerere`) | Doesn't open the panes. It offers **Mark as resolved** (stage the file as it is) or **Resolve again from the merge**, which opens the panes and replaces the file on Save. `git mergetool` skips files that rerere resolved. |
| Has markers, but fewer or more conflicts | Opens from the stages, with a strip: the file was edited after the conflict, and Save replaces it. **Open in external merge tool** stays one click away. |

Comparing the file with the model line by line isn't worth it: the conflict style and git's trimming make them differ
for files nobody touched. Even the number of conflicts can differ for a file that git wrote, as git joins and splits
conflicts where JGit doesn't (2.1). So the strip should say that the file's markers don't match the merge, not that
someone edited it.

Built (step 1): `DA/git/conflicts/ConflictMarkerScan.kt` follows rerere.c (`handle_path`, `handle_conflict`,
`is_cmarker`). `<<<<<<<` and `>>>>>>>` need a space after them (git always writes a label), `|||||||` and `=======`
a space or the line's end, so a marker on a last line without a line ending isn't one. Conflicts can nest. A
`=======` or `>>>>>>>` outside a conflict is text, and markers out of order or a conflict left open mean the markers
don't pair up, where rerere gives up on the file: that's "fewer or more conflicts" too.

### 2.3 Saving and staging

- **Save** (Cmd+S) writes the result into the working file, in place, so its mode stays. The conflicts still open are
  written as markers in the style that `merge.conflictStyle` asks for, so the file stays a valid conflicted file for
  git, for other tools and for opening it again. Nothing is staged.
- **Mark resolved** (Cmd+Enter) saves, then stages the file with `StageEntryGitAction`, as staging it from Unstaged does.
  It's only enabled when no conflict is open. It warns when an edited region still has a line that looks like a marker,
  as `git diff --check` would.
- **Bytes:** lines nobody touched are written as they were read, from `RawText`, never from decoded text (the same
  rule as `HunkPatch`). A file that isn't valid UTF-8 is edited as ISO-8859-1, which keeps every byte; a typed character
  that doesn't fit is refused. Line endings follow the file: `\r\n` stays, and so does a missing newline at the end.
- **Built (step 1), checked against `git merge-file` and git's own merges:**
  - Each line is written with its own line ending. One that has none (the last of its version) gets one when
    something follows it, as when a conflict takes both sides or keeps its markers. JGit's formatter, by contrast,
    ends the file without a line ending whenever the last chunk it writes comes from a version whose last line has
    none, even if that chunk stops before it.
  - Markers end with CRLF when ours' and theirs' lines just before the conflict and the base's first line do, as git
    decides (`is_cr_needed` in xdiff). So a file that both added with CRLF gets LF markers, as from git. JGit's merges
    write markers with LF in any file.
  - Labels are git's: ours is `HEAD`; theirs is the branch merged, or `a1b2c3d (Fix parser)` for a rebase or a
    cherry-pick; the base is the merge base's short id, or `parent of a1b2c3d (Fix parser)` (for a revert, the two
    swap). After a stash, git stash's `Updated upstream` and `Stashed changes`. Their length comes from the
    `conflict-marker-size` attribute, and `zdiff3` is written as `diff3`, as JGit's chunks already leave out the lines
    that both sides have alike.
  - The versions come from the index, which can have LF where the working tree has CRLF, so the result goes through a
    checkout's line ending conversion (`core.autocrlf`, `core.eol`, the `text` and `eol` attributes), as git's merge
    writes its result.
  - Save writes nothing when the index's versions changed since the file was loaded (`ConflictError.NotConflicted`
    when resolved elsewhere, `StagesChanged` when another operation stopped there), when the working file changed
    (`WorkingFileChanged`, unless the user chose Keep), or when a folder or a symbolic link is in the file's place
    (`NotAFile`). A file that was deleted is written again, executable when its side is.
  - Mark resolved (`MarkConflictResolvedUseCase`) is refused while a conflict is open (`ConflictsLeft`), then saves
    and stages the file with `StageEntryGitAction`, and refreshes the status.
- After Mark resolved, the file stays on screen, resolved: dimmed and read-only (it still scrolls), with "✓ Resolved"
  in the toolbar in place of Save and Mark resolved, and a strip, "a.kt is resolved and staged.", with "Next file: b.kt
  (⌘⏎)". The button, or Mark resolved's keys again, opens the next conflicted file. After the last one, the resolver
  says so and offers to go back to the status pane, where Commit and Continue are.
  - A file that something else resolved (a terminal, an agent, the external merge tool) waits the same way: "a.kt has
    no conflicts any more."
  - The file list, and the file menu in Focus, still open any file at once.
  - Opening the next file keeps the last one on screen while it loads, fading out, with clicks going nowhere, and fades
    the next one in. "Loading…" only shows over it when the load takes over 400 ms.
  - Asked for on 2026-10-12 and 13: the resolver used to move on by itself, and in Focus, without the file list, the
    move was a blink of "Loading…". A strip naming the next file didn't help: the files still changed too fast to
    follow. IntelliJ's and VS Code's merge tools wait for the user too.
- **Changes from outside while it's open:**
  - closing it, switching files, switching the tab's worktree or closing the tab with unsaved changes asks: Save,
    Discard or Cancel;
  - when the working file changes on disk (an agent, an editor), a strip offers Reload (losing the resolver's changes)
    or Keep (Save overwrites the file);
  - when the stages go away (resolved or aborted elsewhere), the resolver closes and says why.
- **Built (step 2):**
  - leaving a file with picks that aren't saved, or closing the resolver, asks in a strip under the toolbar: Save,
    Discard or Cancel. Since step 4, closing the tab, the worktree picker's switch and opening another repository in
    the tab ask the same, as typing can be lost (quitting Leaf doesn't: nothing in Leaf asks then);
  - a changed working file is noticed when Save or Mark resolved writes (`WorkingFileChanged`): the strip offers Reload
    or Keep mine, which writes over it. Save returns the id of the bytes it wrote, so the resolver's own Saves don't
    count as changes;
  - when the shown file stops being conflicted (marked resolved here, or resolved or aborted elsewhere), the next
    conflicted file opens. When none is left, the view says "All conflicts resolved" if a file was resolved here, and
    closes otherwise (`afterStatus` in `ConflictResolverViewModel.kt`).

### 2.4 Conflicts that three panes can't show

| Conflict | Index | What the resolver does |
|---|---|---|
| Both modified | stages 1, 2, 3 | Three panes. |
| Both added | stages 2, 3 | Three panes with an empty base: identical lines merge, the rest conflicts. |
| Deleted by us or by them | 1 and 3, or 1 and 2. A file that one side renamed and the other deleted looks the same, at its new path | A choice: keep the modified version (its diff against the base is shown) or delete the file. Keep stages the working file, delete runs `git rm`. Like `git mergetool`'s "(m)odified or (d)eleted". |
| Binary, images included | any stage binary (`RawText.isBinary`) | A choice: use ours or use theirs (`git checkout --ours`/`--theirs`, then stage). Images side by side with the existing `ImageDiff`. |
| LFS | stages are pointer files | Like binary. Merging pointers line by line means nothing. The card shows each side's size and object id. A preview needs the objects, which the built-in LFS client can download later. |
| Submodule | mode `160000` | A choice of commit, written with `git update-index --cacheinfo`, as `git mergetool` does. The submodule's own checkout isn't touched. |
| Symbolic link | mode `120000` | A choice of target, as in `git mergetool`. |
| Rename conflicts (rename/rename, file/directory) | each version alone at its own path: the original at stage 1 (DD), each side's name at 2 or 3 (AU, UA). git also records a file against a symbolic link or a submodule at two paths | Not handled at first (the resolver said what git reported and pointed to the terminal). Since step 5: a file renamed on both sides opens as one, to pick its name (section 9), and the rest get a card per side for each path. |
| Too large | over 10 MB on a side, or comparing the versions took over 2 s (section 10) | Use ours, use theirs, or open in the external merge tool. |

Built (step 1): `GetConflictedFileGitAction` loads a conflicted file as `ConflictedFile` (`domain/models/`), and picks
its row in `git mergetool`'s order: rename conflicts, then a submodule, a deletion, a symbolic link, Git LFS pointers
(a file of 1,024 bytes or less on ours or theirs that JGit's `LfsPointer` parses), binary (the `merge` attribute unset
or `binary`, as the `binary` macro sets it, or `RawText.isBinary` on each whole version, as JGit's merge reads them),
too large (over 10 MB on a side, measured in step 6, section 10), and text. Only text is merged and gets the working
file's check.

Built (step 5): every row but "Both modified" and "Both added" gets a card per side, which section 9 describes. A file
renamed differently on each side (rename/rename) opens as one, with a card per new name; the other conflicts at
several paths get a card per side for each path.

## 3. UI feasibility in Compose Desktop

| Hard part | Other tools | Proposal for Leaf |
|---|---|---|
| **Editable centre.** No code editor component exists for Compose. `BasicTextField` holds the whole text as one paragraph and lays it all out again on each change, with no virtualization. | Each tool builds on a real editor: Meld on GtkSourceView (all three panes editable), JetBrains on its own editor, Sublime Merge on Sublime Text's, VS Code on Monaco. SmartGit edits the working tree file, markers included, in its centre pane. | Edit **one region at a time**. Clicking a region of the result (a conflict, or a run of lines between changes, split at 200 lines) turns it into a `BasicTextField`. Its rows grow and shrink as you type, and the side columns' fillers follow. The rest stays read-only rows. Selecting across regions isn't possible; Copy result covers the common case. One undo history covers arrows and edits. |
| **Synchronized scrolling.** | Meld and JetBrains keep each pane's own lines and map the scroll position from pane to pane. Beyond Compare, WinMerge and KDiff3 pad with filler lines so that rows line up. | **Aligned rows**, as Leaf's split diff does today: one `LazyColumn`, each row holding every column, so the panes can't drift and virtualization comes free. A chunk's row is as tall as its tallest side. The others get hatched fillers. |
| **Connector gutter and arrows.** | Meld and JetBrains draw curves between ranges of different heights, with arrow and cross icons at the start of each change. Meld's Ctrl-click inserts instead of replacing. | With aligned rows, the gutter is a straight band per chunk, drawn in the row, with its buttons on the first line. No curves, no reading of line positions from three panes. |
| **Long lines.** | Each pane scrolls sideways on its own. | Soft wrap, as the diff pane does (`DiffText`, `softWrap = true`). Sideways scroll would need one scroll state per column shared by every row, which Compose's `ScrollState` doesn't support. |
| **Highlighting.** | Word-level and syntax highlighting everywhere. | The read-only rows reuse the diff pane's `SyntaxHighlighter` and line colours. The region being edited goes plain at first. Word-level highlights come later. Both came in step 6 (section 11). |
| **Swing editor instead** (RSyntaxTextArea in a `SwingPanel`) | | Rejected. It's a heavyweight component that draws over Compose content, and Leaf's dialogs, popups and tooltips draw on the window's canvas (see CLAUDE.md, Dialogs). It's also a new dependency. |

## 4. Where it opens, and the external merge tool

- **Unstaged:** **Resolve…** comes first in a conflicted file's menu, next to **Open in external merge tool**,
  **Use ours** and **Use theirs**, which the external merge tool's work added.
  Double-clicking a conflicted file opens the resolver, instead of staging the file with its markers.
- **Diff pane:** a **Resolve…** button in the bar under a conflicted file's diff header (`ConflictBar`), next to
  **Open in external merge tool**.
- **Operation banner:** a **Resolve conflicts** button before Abort, which opens the first conflicted file.
- **The resolver:** its file list switches between conflicted files, and **Open in external merge tool** hands the
  current file to `git mergetool` (after Save or Discard). The resolver waits while the tool runs, then reloads.
- **Settings → Conflicts** (decided 2026-10-12). A new entry in the GIT section, after Worktrees, which the resolver
  adds with its UI. The external merge tool's work adds no setting.
  - **Resolve conflicts with:** Built-in resolver (the default) or External merge tool. It decides what double-clicking
    a conflicted file and the banner's **Resolve conflicts** open; the other stays in the file's menu and the diff's
    bar. `AppConfig.ConflictOpener`, stored in `user_prefs.json` as `conflict_opener`.
  - Files that `git mergetool` can't take from Leaf (`mergeToolRefusal`: a deletion, a symbolic link, a submodule, a
    name with `*`, `?` or `[`), and repositories where neither `merge.guitool` nor `merge.tool` names a tool, open in
    the built-in resolver whatever the setting. The entry can show which tool git would run, read-only.
  - **Resolver layout:** Columns (the default) or Stacked, the same choice as the resolver's toolbar toggle
    (`AppConfig.ConflictResolverLayout`, `conflict_resolver_layout`, section 5).
  - **Wrap long lines:** on by default, the same switch as the toolbar's Wrap lines
    (`AppConfig.ConflictResolverWrapLines`, `conflict_resolver_wrap_lines`, section 5).
- **Ours and theirs always say which branch or commit they are.** The user can't remember which is which, and the
  bare names confuse people: a Sublime Merge forum thread asks for clearer labels for exactly this. This holds for
  every step of the UI: the column headers, the arrows' tooltips, the conflict menu, the choice cards, the toolbar and
  the messages.
  - A header names the side: **Ours: main**. Its tooltip says what ours and theirs mean in the operation in progress:
    in a merge, ours is the branch checked out and theirs the branch merged; in a rebase, ours is the branch being
    rebased onto (with the commits replayed so far) and theirs the commit being replayed; in a cherry-pick or a
    revert, ours is the branch checked out and theirs the commit picked, or the one being reverted.
  - So: **Ours: main** and **Theirs: feature/x** for a merge, **Ours: main (onto)** and **Theirs: a1b2c3d "Fix
    parser"** for a rebase.
  - The model carries the `ConflictSides` that the external merge tool's work reads (`GetConflictSidesGitAction`), and
    `ui/status/ConflictSideLabels.kt` already words them for the diff's bar and the banner (`main`, `main (onto)`,
    `a1b2c3d "Fix parser" from feature`, `the revert of …`). The resolver uses the same words.
  - A stash that didn't apply cleanly records no theirs (`ConflictOperation.OTHER`), so the labels need a fallback
    there.

## 5. Layout options

All three share the data model, the toolbar (previous and next conflict with a counter, Save, Mark resolved, Base,
Open in external merge tool) and the choice cards from section 2.4. The mockups reviewed on 2026-10-12 are kept outside
the repository; the sketches below show the layouts.

### Option A: Meld columns (the default)

```
┌ files ┐┌ Ours: main (HEAD) ──┐  ┌ Result ──────────────┐  ┌ Theirs: feature/x ──┐
│ ⚠ a.kt││ fun load() {        │  │ fun load() {         │  │ fun load() {        │
│ ⚠ b.kt││   val x = parse()  ━┿━▶│ ░ unresolved (2/4) ░ │◀━┿━  val x = read()    │
│ ✓ c.kt││   log(x)            │  │ ░ base: val x = … ░  │  │   check(x)          │
│       ││ ░░░░░░░░░░░░░░░░░░░ │ ✓│   retry(3)           │✓ │   retry(3)          │
└───────┘└─────────────────────┘  └──────────────────────┘  └─────────────────────┘
```

- The whole tab, like file history: the side panel, log and status pane go away while it's open. A narrow list of the
  conflicted files sits on the left.
- Arrows point into the result: **▶** takes ours, **◀** takes theirs. Once one side is taken, the other side's arrow
  adds its lines after it, so "both" in either order is two clicks. The conflict's menu also has Ours then theirs and
  Theirs then ours. A cross rejects a change, and a one-sided change that was applied goes back to the base.
- An open conflict shows a hatched placeholder in the result. **Base** shows the base text in every placeholder.
- Good: the arrows map straight onto the columns, the user knows it from Meld, and JetBrains, SmartGit and Sublime
  Merge work this way too. Bad: each column gets a third of the width, so long lines wrap. It wants a window around
  1,200 px wide or more.

### Option B: both sides on top, the result below (switchable)

```
┌ Ours: main (HEAD) ───────────────┐┌ Theirs: feature/x ───────────────┐
│   val x = parse()       [Take ↓] ││   val x = read()        [Take ↓] │
└──────────────────────────────────┘└──────────────────────────────────┘
┌ Result ──────────────────────────────────────────────────────────────┐
│ fun load() {                                                         │
│ ░ conflict 2/4: take ours or theirs above, or type here ░            │
└──────────────────────────────────────────────────────────────────────┘
```

- VS Code's merge editor and GitKraken; KDiff3 adds the base as a third pane on top. The top pair scrolls with the
  bottom, conflict by conflict.
- Good: the result gets the full width, and it suits a portrait monitor. Bad: no arrows from side to result, the eye
  moves up and down, and two scroll areas have to follow each other.
- On its own, B's one-column result could be a single `BasicTextField` for files of normal size. As a layout of the
  same resolver, it uses A's rows and region editing instead, so that the switch changes the layout, not the editor.

### Switching between A and B

- Two toggle buttons in the resolver's toolbar, Columns and Stacked, like the diff pane's Unified and Split
  (`DiffTypeButtons`). Settings → Conflicts has the same choice (section 4).
- One setting for the whole app, `AppConfig.ConflictResolverLayout`, stored in `user_prefs.json`
  (`conflict_resolver_layout`), with Columns as the default.
- Switching keeps everything: each chunk's state, edits (an open editor keeps its text), the current conflict and the
  scroll position, mapped by chunk.
- Both are built from the same pieces. A chunk renders as rows of cells. A draws ours, the result and theirs in one row
  with the gutters between them. B draws ours and theirs in the top list, with **Take ↓** and **Add ↓** in place of the
  gutter arrows, and the result alone in the bottom list. The top list follows the bottom one by chunk: the first chunk
  that's visible, and the current conflict.
- **Built (step 3):** `A/ui/conflicts/ResolverStackedView.kt`, with the rows from `sidesRows` and `resultRows` next to
  `columnRows` in `domain/conflicts/ResolverColumns.kt`.
  - The switch is two icons in the toolbar, Columns and Stacked, and "Resolver layout" in Settings → Conflicts. Both
    write `conflict_resolver_layout` (`AppConfig.ConflictResolverLayout`), so every tab's resolver follows.
  - The top pair lines up as the columns do, and a chunk that neither side has a line of still gets a row, for its
    chips. Each chunk's first row ends with a chip per side:
    - a conflict side has **Take ↓**, then **Add ↓** once the other side is taken (the columns' arrows, `arrowFor`),
      and **Taken** once its lines are in the result. Taken takes them out again (`dropping`): the other side's lines
      stay alone, or the conflict is open again;
    - a change has **Applied** on the side that made it (ours' and both sides' on the left, as the gutters have
      them), which undoes it, or **Apply**.
  - The result has one row per line, with a bar on its left: blue for ours' changes, purple for theirs', green for a
    resolved conflict, amber for an open one, whose row is the hatched placeholder (or the base's lines, with Base).
    A chunk without a line in the result, such as lines that a side deleted, gets one thin row with a line across, so
    that every chunk has a place there. Conflicts keep their right-click menu. The legend lists the chips.
  - **Following:** a place is a chunk and a row of it (`ChunkPlace`, `placeOf`, `rowOf`). The top pair shows the place
    at the result's top, as its own row of that chunk or the chunk's last, with the same offset on a line that both
    show. Moving to a conflict (F7, or opening a file) pins the top pair to the conflict until the result has been
    there and moved on (`ResolverLists.pinned`): the place above a conflict can be in a chunk that is much taller on
    top, such as lines that one side deleted. The top pair scrolls by itself too, and the result doesn't follow it.
  - **Switching** keeps the picks, the current conflict and Base, which the view model holds, and the place at the
    top of the list (the columns, or the result), mapped by chunk, with its offset on the same line (`keepPlace` in
    `ConflictResolver.kt`). A new file still opens at its first conflict.
  - **The handle** between the two parts starts at 45% for the top pair, and a double-click puts it back. Each part
    keeps 90 and 120 dp. Its place is kept until Leaf quits (`ConflictResolverSession`, an app singleton), not saved
    (decided 2026-10-12): saving it, as the status pane's sections are, would take a prefs key.
- **Widths and long lines** (asked for after trying both layouts on long files, 2026-10-12):
  - **Columns' widths:** each gutter, in the headers and in every row, is a handle for the edge between its column and
    the result (`ColumnShares` in `domain/conflicts/ResolverSizes.kt`). A column keeps 120 dp, one already narrower
    after the window shrank doesn't shrink further, and a double-click shares the width out evenly. A drag that
    starts on a gutter's button stays the button's click. The file list's right edge is a handle too (140 to 480 dp,
    220 by default).
  - **Wrap lines,** a toolbar toggle, on by default as before. Turned off, every line stays on one row, and every
    column of both layouts scrolls sideways together, by one offset (`SidewaysScroll`, `ResolverSideways.kt`), as
    Meld keeps its panes in step. A sideways swipe or Shift and the wheel scroll it, before the lists see the event,
    and a scrollbar under the lines shows where it is. Line numbers stay, and so does the 16 dp gap before the text.
    The longest line of the three versions can reach its end in the narrowest column; a column with shorter lines
    scrolls into blank space.
  - The widths are kept with the handle until Leaf quits. Wrap lines is a setting too, "Wrap long lines" in Settings
    → Conflicts (`AppConfig.ConflictResolverWrapLines`, `conflict_resolver_wrap_lines`, on by default), which the
    toolbar's toggle writes, for the whole app (asked for on 2026-10-12).
- **Focus** (asked for on 2026-10-12, both layouts): the resolver already takes the tab, and Focus hides what is left
  around it, for reviewing with the whole window.
  - It hides Leaf's tab bar, the repository's toolbar, the operation banner, the bottom bar and the file list. The
    resolver's toolbar and legend stay, so Save, Mark resolved, the conflict counter and the way back are still there,
    and the file name in the toolbar becomes a menu of the conflicted files (`FileMenu`), with a check on the ones
    resolved here, which can't be opened again. Next to it, "2 of 3 files" counts them, those resolved here first.
  - The button at the right end of the toolbar, or ⇧⌘F (Ctrl+Shift+F on Windows and Linux; macOS's own full screen
    is ⌃⌘F), turns it on and off. Esc leaves Focus first, and a second Esc closes the resolver, as video players do
    with full screen.
  - It's kept until Leaf quits, as the widths are: the resolver opens in Focus again. Closing the resolver shows
    everything again.
  - The window stays as it is: Leaf doesn't switch macOS to full screen, which moves the window to a Space of its own
    with an animation. The window's own full screen works with Focus.
  - The tab bar is drawn by `App`, above the tab. The tab hides it through `LocalAppChrome` (`ui/AppChrome.kt`):
    `RepositoryOpenPage` sets `tabBarHidden` while the resolver is in Focus, and back when it leaves the composition,
    as when another tab is selected.

### Option C: the result only, with a card per conflict

```
┌ Result: a.kt ─────────────────────────────────── 2 of 4 ▲ ▼ ┐
│ ▌fun load() {                         ▌ = changed by theirs │
│ ┌ conflict 2 ────────────────────────────────────────────┐  │
│ │ ours: val x = parse()   │ theirs: val x = read()       │  │
│ │ [Ours] [Theirs] [Ours, theirs] [Theirs, ours] [Edit]   │  │
│ └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

- VS Code's inline conflict actions, inside Leaf's diff pane: the status pane, with Commit and Continue, stays in view.
- Good: the smallest build, and no new full-tab view. Bad: neither side is shown whole, changes that merged cleanly are
  just a stripe in the margin, and it isn't the Meld model the user asked for.

## 6. Decisions (2026-10-12)

1. **Layout:** Option A by default, with the rendering choices from section 3 (aligned rows, region editing), and a
   switch to Option B for users who prefer it, such as on portrait monitors. Most users are expected to keep A.
2. **Where it lives:** the whole tab, like file history. A dialog can't be larger than the window, because Leaf's
   dialogs draw on the window's canvas. A separate OS window would need its own focus, shortcuts and theme handling
   for little gain.
3. **Double-click on a conflicted file** opens the resolver instead of staging it with its markers.
4. **Editing comes after picks.** Picks alone resolve most conflicts, so the first version has the model, the arrows,
   Save and Mark resolved, and region editing follows (section 7).

## 7. Build order

1. **Model** (data and domain): load the stages, classify the conflict (section 2.4), merge into chunks, build the
   result, check the markers, write and stage. Tests compare the clean merges with `git merge-file -p` on temp
   repositories, and cover CRLF, Latin-1, a missing final newline and an empty base. Built 2026-10-12 (sections 2.1 to
   2.4 say how).
2. **Read-only Option A:** columns, arrows, navigation, Base, Save and Mark resolved, and the entry points from section
   4. Built 2026-10-12:
   `ui/conflicts/`, `ConflictResolverViewModel`, the rows in `domain/conflicts/ResolverColumns.kt`. Keys while it's
   open: Cmd+S saves (Stash elsewhere, which git refuses while files have conflicts), Cmd+Enter marks resolved, Esc
   closes, F7 and Shift+F7 go to the next and the previous conflict. The conflicts that three panes can't show get a
   plain panel with each side, and the external merge tool where it can take the file, until the cards of step 5.
   Settings → Conflicts has "Resolve conflicts with"; the layout's setting comes with step 3.
3. **Option B and the switch:** the stacked layout from the same rows, the toolbar toggle and the setting. Built
   2026-10-12 (section 5, "Switching between A and B", says how). Opening a file now loads it before the status
   arrives: the status could come first and open the first conflicted file in its place.
4. **Region editing** and the shared undo history, in both layouts. Built 2026-10-13 (section 8 says how).
5. **Choice cards** for the rows of section 2.4 other than "both modified". Built 2026-10-13 (section 9 says how).
6. **Later:** word-level highlights, highlighting while editing, and the size limit from measurements. The size limit
   was measured and set on 2026-10-14 (section 10 says how), and the words marked and the colours while editing were
   built on 2026-10-15 (section 11).

## 8. Region editing (step 4, decided 2026-10-13)

Meld, IntelliJ and VS Code let you type anywhere in the result, as each is built on a code editor. One text field for
the whole result would lose the aligned rows, and the arrows with them (section 3), so a part of the result at a time
is edited. Two ways were mocked up:

- **In place (picked):** the part turns into a text field where it is. Ours and theirs get fillers as it grows, so the
  rows after it stay lined up. In the stacked layout, the same field at the full width.
- **An editor below the part,** across the three columns, with Apply and Cancel: more room in Columns, but the text
  isn't where the result is, it shows twice while editing, and no other tool works that way.

The defaults that came with it:

- **A part:** a conflict, a change that merged cleanly, or a run of unchanged lines, cut at 200 lines.
- **Starting:** a double-click on a line of the result, the Edit button that the pointer over it shows, "Edit these
  lines" in its menu, or Enter for the current conflict. A single click doesn't edit. The caret goes to the end of the
  line.
- **What it starts from:** the lines that the result shows, and the base's for an open conflict, as Meld, IntelliJ and
  VS Code start. To start from both sides, take both first.
- **Finishing:** Esc or a click outside keeps the text; ⌘Z takes it back. A second Esc leaves Focus, then closes, as
  before. A text that didn't change leaves the part as it was.
- **Undo:** ⌘Z and ⇧⌘Z (Ctrl+Z and Ctrl+Shift+Z elsewhere), and two toolbar buttons. Each pick is a step, and each
  finished edit; Base isn't. In the field, ⌘Z undoes the typing first. The history is the open file's.
- **An edited part:** a conflict counts as resolved (green), and a ✎ marks the part. The arrows, chips and a change's ✓
  and × put other lines in place of the typing; unchanged lines get ↺, which puts them back.
- **Unsaved typing:** closing the tab and switching worktrees ask (Save, Discard or Cancel), as section 2.3 planned.
  Quitting Leaf doesn't ask, as nothing in Leaf does; that would be a change of its own.
- **For now:** the field shows plain text (colours while editing are step 6, section 11), and with Wrap lines off it
  doesn't wrap and scrolls sideways with the columns.
- **Opening a saved file again** (found in the manual run, 2026-10-13): the resolver used to build the merge from the
  stages alone, so a line typed and saved with a conflict still open didn't show when the file was opened again, and
  Save would have written over it. It now picks out of the working file the state that Save wrote: picks where the
  lines are a pick's, lines typed elsewhere, the conflicts still open where their markers are. Only a state that writes
  the file again byte for byte is kept; otherwise the merge opens as before. A file without markers still gets its
  panel.

Built: the parts and the typed lines in `domain/conflicts/ResolverEditing.kt` (`inParts`, `editStart`, `LineFormat`,
`typing`), written by `resultBytes` and found again by `resumedFrom` (`DA/git/conflicts/ResumedMerge.kt`); the edit,
Undo and Redo in `ConflictResolverViewModel`; the field and keeping the list still in
`A/ui/conflicts/ResolverEditor.kt`. CLAUDE.md has the details.

## 9. Choice cards (step 5, decided 2026-10-13)

The conflicts that three panes can't show (section 2.4) had a plain panel: a sentence, then "Use ours" and "Use
theirs", which asked first and staged at once. Two ways to show a card per side were mocked up:

- **A button on each card:** "Use main" on ours' card, which asks, then stages, as the panel did. It's how `git
  mergetool` asks in text ("(m)odified or (d)eleted", "(l)ocal or (r)emote") and IntelliJ's Accept Yours and Accept
  Theirs.
- **Pick, then Mark resolved (picked):** a click on a card, or ← and →, picks it, outlined in its side's colour; Mark
  resolved (⌘↩) stages it, as for text. Picking costs nothing, Mark resolved is the one step that stages, so there's no
  dialog; the same pause and Next file follow.

What the cards show:

- **One side deleted the file:** that side's card says the file is gone. The other shows its diff against the base,
  with +/− counts, as the diff pane colours it, at most 300 lines. Keep stages the working file as it is, as `git
  mergetool`'s "modified" answer does, so that edits made since the merge stay; the diff is the working file's (as
  `git add` reads it), and a note says when it was edited. A `filter` attribute, such as Git LFS's, makes the working
  file something else than the blob, so the diff is then the blob's. Without a working file, Keep checks the side's
  version out, as "Use theirs" does.
- **Binary:** each side's size; PNG, JPEG, GIF, WebP, BMP and ICO up to 16 MB also as an image, with its size in pixels.
  "Open in the default app" opens a copy from Leaf's temporary folder, as the diff pane's image click does.
- **Git LFS:** each side's object id and size; the object as an image, or opened in the default app, once it's in the
  repository's LFS store with its pointer's size. Nothing is downloaded.
- **Submodule:** each commit's subject, author and date, read from the submodule's own repository, which commit has
  the other in its history, and which commit the submodule has checked out. Its checkout doesn't change.
- **Symbolic link:** each target, and whether it points to something that exists.
- **Too large:** each side's size and line count, and the External merge tool button stays.
- **Base:** the toolbar's Base adds a card for the base between the sides, except where one side deleted the file,
  whose diff already shows it.
- **Mark resolved and Next file where the pointer is** (decided after the manual run, 2026-10-13): picking a card, then
  Mark resolved at the toolbar's right end, then Next file at the strip's left end, crossed the window three times.
  The card picked has Mark resolved next to its pick, and once the file is resolved, the same button is Next file. A
  text file's Mark resolved, in the toolbar, turns into Next file in place, as ⌘↩ twice does. So there's one Next file,
  where Mark resolved was: the strip only says what ⌘↩ opens, and cards have no Mark resolved in the toolbar (a first
  try had all three, which was too many). Double-clicking a card to resolve it at once, and skipping the pause for
  cards, were left out: the first can't be seen, the second hides what was staged.
- **Renamed differently on each side** (rename/rename, decided 2026-10-13): section 2.4 left it to the terminal, as git
  records three conflicts that don't say they belong together (the old name at stage 1 alone, ours' new name at stage
  2, theirs' at stage 3). But git merges both sides' changes to the content and writes the same blob at both new names,
  so they pair by their blob, and the old name joins them by content (JGit's rename detection, at git's 50%), or as the
  only one left. The file is one entry of the list, and any of its paths opens it under its old name: a card per new
  name ("Name it notes/b.txt") and "Keep both names". Mark resolved runs what git suggests: `git rm` for the old name
  and a new name not kept, `git add` for a name kept.
  - When both sides' changes to the content conflict, git writes its markers into both new names. The name kept then
    becomes a conflict of its content, with the base from the old name, ours from HEAD and theirs from the commit
    applied (`git update-index --index-info`), and opens next in the three panes. git's markers there are a character
    longer than the attribute says, so the file is written again with the resolver's, unless it was edited since.
  - A revert or a stash doesn't record which commit theirs is, so Mark resolved waits there until the markers are fixed
    in an editor. Markers fixed by hand are staged as they are.
- **Other conflicts at several paths** (a file against a folder, which git moves to `name~HEAD`): a card per side, as
  for a deleted file: "Keep it here" stages the working file, "Leave it out of this path" removes it.

Built: the previews in `DA/git/conflicts/ConflictPreviews.kt` (`ConflictedFile.preview`, models in
`domain/models/ConflictPreview.kt`), Keep in `ResolveConflictGitAction` (`keepWorkingFile`), the copy for opening in
`WriteConflictVersionGitAction`, renamed files in `DA/git/conflicts/RenamedFiles.kt` and
`ResolveRenamedFileGitAction`, the pick in `ConflictResolverViewModel`, and the cards in
`A/ui/conflicts/ResolverChoiceCards.kt`. CLAUDE.md has the details.

## 10. Size limits (step 6, measured 2026-10-14)

Step 1's limit, 10 MB on a side, was a guess. It was measured on three kinds of conflicted text, from 1 to 40 MB, in
repositories that git merged: generated TypeScript with 6 conflicts, a minified bundle of 2,000-character lines with 4,
and a `package-lock.json` where both sides bumped every 15th package (2,599 conflicts at 10 MB). A temporary test timed
each step of the load, and a temporary harness ran the resolver's view model through Leaf's Dagger graph in an
offscreen scene: opening, scrolling, picks, Undo, editing, the layouts and Save. On an M4 Pro:

| | 1 MB | 5 MB | 10 MB | 20 MB | 40 MB |
|---|---|---|---|---|---|
| Open generated code | 0.03 s | 0.08 s | 0.15 s | 0.3 s | 1 s |
| Open the lock file | 0.2 s | 3.8 s | 16 s | about 1 min | about 4 min |
| Pick / Undo / switch to Stacked, generated code | 16 / 22 / 147 ms | 22 / 39 / 50 ms | 49 / 79 / 89 ms | 80 / 120 / 184 ms | 136 / 342 / 368 ms |
| Heap held, generated code | 20 MB | 101 MB | 182 MB | 348 MB | 678 MB |

(The 1 MB file opened first, while the JVM warmed up. The scene draws in software, so a window may be a little faster.)

- **The merge's diffs are nearly all of the load,** and their time follows the number of changes, not the size: the
  lock file's grows with the square of its size, with either of JGit's algorithms. git has the same shape (its own
  merge of the lock files, which uses histogram, took 38 s, and 0.9 s with `-X diff-algorithm=myers`), and JGit is 10
  to 20 times slower than git. Reading the blobs, cutting the parts, the marker scan, the rows, Save and reopening a
  saved file take milliseconds at any size.
- **Scrolling stays at 5 to 10 ms a frame at any size**, as the lists draw only what's on screen. What rebuilds every
  row grows with the file: a pick, Undo, switching layouts.
- **Memory is about 16 times the file** for code-like text (fewer, longer lines hold less). Leaf's heap is a quarter
  of the RAM by default: 2 GB on an 8 GB Mac.
- **Found on the way:** the merge diffed each side twice (JGit's merge, then the parts), and now keeps JGit's diffs
  (`KeptDiffs`), which halved the load. And opening a saved file with thousands of parts (a 2.5 MB lock file has
  3,899) overflowed the stack: `pickedFrom` recursed once per part. Only exceptions are caught, so the error left the
  resolver on "Loading…". It's a loop now.

Decided (2026-10-14):
- **10 MB stays.** Below it, every action took under 100 ms and the resolver held under 200 MB.
- **Comparing the versions gets 2 seconds** (`MAX_RESOLVER_COMPARE_TIME`). Past it, the file gets the cards, as text
  that's too large does, and they say "Too many changes to compare here: comparing the versions took over 2 seconds."
  A size limit low enough for lock files (about 2 MB) would have kept the three panes from a 10 MB source file that
  opens in 0.15 s. JGit's diffs can't be stopped from outside, but they compare lines all along, so the merge's
  comparator reads the clock every 1,024 lines and throws past the time (`comparatorWithin`); nothing is left running.
  A deleted file's diff on its card stops the same way, and the card then shows the sizes alone.
- **Raising the limit later** would need the rows built once rather than on every pick, and fewer objects per line.

## 11. Words marked and colours while editing (step 6, decided 2026-10-15)

Rows tinted whole say that a line changed, not what in it changed, and the base, which would show that, is usually
hidden. Other tools mark the words: IntelliJ the words each side changed against the base (characters on request),
VS Code's merge editor the same, Meld the changes within lines against the middle pane. Leaf's own diff pane marks
characters, with Google's diff-match-patch and its semantic cleanup, in the lines of a hunk that it pairs.

Decided, from options and a mockup:
- **Each side against the base.** A mark always means "this side changed this", in a change and in a conflict alike,
  and the result's lines keep the marks of the side that they come from. Ours against theirs, the other option, only
  says something inside a conflict, and can mostly be read off the two sides' marks.
- **Words, not characters.** On the mockup's lines, characters marked `POS` of `"POST"`, the `6` of `60_000`, and
  `sub` of `subtotal`. A word is a run of letters, digits and `_`, a run of spaces, or any other character alone. Words
  alike between two changes are part of them when they're no longer than either change, which is diff-match-patch's
  semantic cleanup on words: `1.2.3` to `1.3.0` marks `3.0`, where the diff alone matched the `3` and marked `.0`.
  Never across lines.
- **A line changed whole gets no marks**, as its colour already says it; the diff pane does the same. Nor do lines
  typed, or the base's lines.
- **Colour:** a stronger shade of the line's own: ours' blue, theirs' purple, a conflict's orange, a resolved
  conflict's green.
- **No setting**, as the diff pane has none.
- **The part being edited keeps the rows' colours** for keywords, annotations and comments as it's typed. Marks leave
  it while it's typed, and come back with the rows when editing finishes.

How it works:
- `changedWords` (`domain/conflicts/ChangedWords.kt`) diffs a chunk's words, the side's lines against the base's, with
  JGit's histogram diff, then joins the short stretches alike. `ChangedWords` keeps them per chunk and side, worked out
  the first time that a row of the chunk is drawn. Picks and lines typed keep the chunks' lines, so one serves a file
  for as long as it's shown. A row finds its chunk by its line, with a binary search on the chunks' starts.
- The rows draw marks through the diff pane's `DiffText`, which marks a `MatchLine`'s inserted text; it took a colour
  for that (`insertedColor`), which was the diff pane's green. The editor's `EditorColours` is `TabsAsSpaces` with the
  syntax highlighter's spans, line by line.
- **Limits:** a chunk of more than 2,000 words, the base's and the side's together, gets no marks. Its diff runs while
  the rows are drawn, and its time grows with the square of the words that differ: on an M4 Pro, 3,200 words that all
  differ took 28 ms, and 1,920 took 11. A part over 100,000 characters stays plain while it's edited, as its colours
  are worked out on each change. With the cache warm, scrolling the 2.5 MB lock file and a 9.4 MB source file took the
  same time with marks as without (7 ms a frame).
- **Found on the way:** the marks first reached the cells through a `CompositionLocal`. Opening another file then
  froze the offscreen harness's view: the list scrolls to the first conflict from a `LaunchedEffect`, which in an
  offscreen scene lays the list out before the composition is applied ("pending composition has not been applied"),
  and the rows read the local's new value from it. The cells take the marks as a parameter instead, as they take the
  syntax highlighter.
