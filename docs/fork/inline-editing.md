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
