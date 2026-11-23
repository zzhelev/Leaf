# Editing a hunk in place (B1)

Plan, 2026-11-23, approved with the recommended decisions and built the same day. Option B1 of the Design artifact "Leaf
edit files", which the user picked for most quick edits (B2, the whole file, may follow). How it was built, and what
differs from this plan, is in CLAUDE.md, "Git operations", Editing a hunk in place: the resolver's editor pieces were made
`internal` rather than moved to `ui/editing/`, and "Edit these lines" is in the unified view's line menu only.

## 1. What it does

- In an Unstaged diff, each hunk's header gets **Edit**, before Discard hunk. A line's right-click menu gets **Edit
  these lines**, which starts with the caret on that line.
- While a hunk is edited, its header shows "Editing", **Cancel** and **Done**; Stage hunk and Discard hunk go. The
  hunk's removed lines stay above, dimmed, as a reminder of what was there. Its new side, the added and context lines as
  the working file has them, becomes a text field: its line numbers, the code's colours, and a green bar on each line
  that differs from the old version, worked out again as you type.
- **Done** (⌘↵, ⌘S) writes the lines into the working file. **Cancel** (Esc) leaves the file as it was.
- One hunk at a time.

## 2. Where it's offered

- Unstaged only, for a text file that's modified or new (untracked, or added with `git add -N`).
- Not in Staged: that would edit the index, not the file. Not in a commit's diff or a comparison. Not for a file with
  conflicts (the resolver edits those), a binary file, a symbolic link, or a hunk of a diff that ignores whitespace
  (its context lines aren't the file's).
- Unified and split. In split, the edited hunk shows as in unified, across both columns (B3, the split view's right
  side as the field, is a separate option).
- During a merge, rebase, cherry-pick or revert too, unlike the hunk buttons: fixing code at a stop is common.

## 3. Writing the file

- Read the working file's bytes, and find the hunk's new-side lines in it by the hunk's start and line count.
- Check they're the bytes that the diff showed, apart from a `\r` before the `\n` (`core.autocrlf` gives the diff `LF`
  where the file has `CRLF`). If they aren't, the file changed after the diff was shown: nothing is written, the field
  stays open with what was typed, and an error says so.
- Replace those lines with the typed ones, in the lines' charset (UTF-8 when they're valid UTF-8, else ISO-8859-1, which
  keeps every byte, as the resolver's `LineFormat` does), each with the line ending that the replaced lines had, and the
  last without one when the file ended without one. Characters that the charset can't hold can't be typed.
- Write a temporary file next to it and move it into place, keeping the file's mode (the executable bit). Nothing of git
  runs: it's the working file.
- Keep the replaced lines in the discard store, as a new kind (`DiscardKind.EDIT`), so that the strip says "Edited
  lines 38–48 of RestoreWorkingFileGitAction.kt." with **Undo**, and "Recently discarded" lists it. Undo merges the
  lines back as a discard's Undo does, so later edits in other places stay.
- Then the status refreshes and the diff reloads.

## 4. While a hunk is edited

- The diff pane keeps the diff it showed when editing started; refreshes go on underneath. An agent writing the file
  can't move the hunk from under the caret, and Done's check catches a file that changed.
- Leaving with typed changes asks first, as the conflict resolver does: **Save**, **Discard**, **Keep editing**. That's
  another file or commit, a worktree switch, closing the diff or the tab, and quitting (the quit dialog lists the file).
  Without typed changes, leaving ends the edit.
- Keys: the field takes typing and has its own undo; Esc cancels, and asks first when something was typed; ⌘↵ and ⌘S
  are Done. Find and the tab's other shortcuts work as before.

## 5. Reuse

- The conflict resolver's editor pieces (`ui/conflicts/ResolverEditor.kt`: the field with its line numbers, tabs shown
  as four spaces, the code's colours while typing, sideways scrolling without wrap) move to a shared `ui/editing/`
  package, and `LineFormat` with its line helpers from `domain/conflicts/ResolverEditing.kt` to `domain/editing/`. Fork
  code only, so no upstream file is rewritten.

## 6. Steps

1. **Domain:** the hunk's edit (`HunkEdit`: where its lines are, how they're written) and `editedFile(bytes, edit,
   text)`, with the check. Tests: CRLF, Latin-1, a last line without a line ending, a new file, a file that changed, all
   lines deleted.
2. **Data:** `WriteHunkEditGitAction`, its use case, the discard record and Undo. Tests against git: after writing,
   `git diff` shows exactly the typed lines, and Undo gives the file back.
3. **UI:** the shared editor pieces, Edit and Edit these lines, the field in the hunk (unified and split), the keys, the
   kept diff, asking before leaving and when quitting. Tests rendered offscreen, and the real tab through the Dagger
   graph in a temporary harness.
4. **Docs and a manual run** against a demo repository.

## 7. Decisions

1. Leaving with typed changes: ask (Save, Discard, Keep editing), as the resolver does. *Recommended.* Or keep the typed
   text as a draft per file, which comes back when the file's diff shows again.
2. Undo after Done, through the discard store. *Recommended.* Or none: the editor's own undo ends with Done.
3. Split view: the edited hunk across both columns, as in unified. *Recommended.* Or the right side only (B3).
4. During a merge or rebase: offered. *Recommended.* Or hidden, as the hunk buttons are.
5. Staged: no Edit. *Recommended.*

# Editing the whole file (B2)

Plan, 2026-11-23, approved with the recommended decisions and built the same day. Option B2 of the same Design
artifact: the working file whole, to see how a change sits in the rest of the file and where its names are used. Built
on B1's pieces. How it was built is in CLAUDE.md, "Git operations", Editing the whole file. The size limit came out at
2,000 lines.

## 8. What it does

- The diff pane turns into an editor of the working file: its header has the file's name, an "Edited" dot while
  something isn't saved, **Back to diff** and **Save**.
- Every line of the file, with its numbers and the code's colours, worked out again a moment after typing stops, as the
  conflict resolver's field does.
- A marker before each line that differs from the index, as the Unstaged diff compares: green for an added line, blue
  for a changed one, and a red notch where lines were removed. A strip down the right edge marks the same changes over
  the whole file, and where the view is; a click there scrolls to it.
- A click on a red or blue marker shows the old lines, with **Put it back**, which puts them in place of the new ones in
  the text (nothing is saved until Save).
- The name under the caret is marked wherever else it appears in the file, as whole words, and the right edge's strip
  marks them too.
- **Save** (⌘S) writes the file and stays in the editor; **Back to diff** (Esc) leaves it, asking about lines not saved,
  as B1 does. ⌘↵ is Save and back.

## 9. Where it's offered

- As B1's Edit: an Unstaged text file, modified or new, without conflicts. Not Staged or a commit's file.
- From the file name's ⌄ menu in the diff's header ("Edit the whole file"), the Unstaged list's right-click menu, and
  a link in B1's editing header ("Whole file"), which carries over what was typed in the hunk.
- Up to a size that stays smooth while typing, measured when it's built; past it, the menu item says it's too long and
  offers the editor instead.

## 10. Saving

- As B1: Save writes the file only when it's still as it was when editing started (or last saved), else nothing is
  written and the text stays. The file is written next to itself and moved into place, with its mode.
- Line endings: a line that didn't change keeps its own; a new line takes the file's (the first one's). The charset is
  the file's, as B1's. A last line without a line ending stays without one unless lines are added after it.
- What a Save replaces is kept for Undo, as B1's Done is (`DiscardKind.EDIT`).

## 11. Steps

1. **Domain:** the whole file's edit (lines and their endings, the charset) and the bytes that a Save writes, keeping
   unchanged lines' bytes; the changes against the index as markers, and the old lines of each; the occurrences of a
   word.
2. **Data:** reading the working file and its index version, and the write (B1's, with the whole-file check).
3. **UI:** the editor in the diff pane's place, markers, the strip on the right, the old lines' popover, occurrences,
   colours, Save and leaving. Measure the field with long files to set the limit.
4. **Docs and a manual run.**

## 12. Decisions

1. Markers compare with the index, as Unstaged does. *Recommended.* Or with HEAD, which would also mark staged changes.
2. Put it back for changed lines as well as removed ones. *Recommended.* Or removed lines only, as drawn.
3. Occurrences of the name under the caret, in the text and in the right edge's strip. *Recommended.* Or none.
4. Opening it from the file name's menu, the Unstaged list and B1's header. *Recommended.* Or a button in the diff's
   header, which the menu was chosen over (C3).
5. Save stays in the editor, and ⌘↵ saves and goes back. *Recommended.* Or Save goes back to the diff each time.
