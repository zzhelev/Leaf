// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.hasConflicts
import dev.app.leaf.domain.models.isIntentToAddFile

/** How a Staged or Unstaged entry sorts by change type. */
val StatusType.fileChangeKind: FileChangeKind
    get() = when (this) {
        StatusType.ADDED -> FileChangeKind.Added
        StatusType.MODIFIED -> FileChangeKind.Modified
        StatusType.REMOVED -> FileChangeKind.Deleted
        StatusType.CONFLICTING -> FileChangeKind.Conflicting
        StatusType.RENAMED -> FileChangeKind.Renamed
    }

/**
 * The group of an Unstaged entry (fork-only, upstream issue 345). Unstaged's added files are the ones git doesn't track:
 * a new file that is staged is in Staged, which has no groups. A file added with `git add -N` is in Changes, where
 * `git status` lists it ("Changes not staged for commit: new file"), as the index has its path.
 */
val StatusEntry.fileGroup: FileGroup
    get() = when (statusType) {
        StatusType.CONFLICTING -> FileGroup.Conflicts
        StatusType.ADDED -> if (isIntentToAdd) FileGroup.Changes else FileGroup.Untracked
        StatusType.MODIFIED, StatusType.REMOVED, StatusType.RENAMED -> FileGroup.Changes
    }

/**
 * The entries of a Staged or Unstaged pane as files to sort, keyed by path, with `#2` and so on for a repeated path. A
 * staged rename's row shows where it came from, as a commit's does (`docs/fork/status-renames.md`).
 */
fun List<StatusEntry>.toFileItems(): List<FileItem<StatusEntry>> {
    val pathCounts = HashMap<String, Int>()

    return map { entry ->
        val count = pathCounts.merge(entry.filePath, 1, Int::plus) ?: 1

        FileItem(
            item = entry,
            key = if (count == 1) entry.filePath else "${entry.filePath}#$count",
            path = entry.filePath,
            kind = entry.statusType.fileChangeKind,
            renamedFrom = entry.renamedFrom,
        )
    }
}

/** The entries inside [folderPath], at any depth. */
fun List<StatusEntry>.inFolder(folderPath: String): List<StatusEntry> =
    filter { it.filePath.startsWith("$folderPath/") }

/**
 * The unstaged entries that discarding changes restores. A new file has no earlier version to go back to, so it is left
 * alone, as a single new file offers "Delete file" instead of discarding: an untracked one, and one added with
 * `git add -N`, which git's restore would empty. So is a file with conflicts ([hasConflicts]), which "Use ours" and
 * "Use theirs" resolve.
 */
fun List<StatusEntry>.discardable(): List<StatusEntry> = filter { it.statusType != StatusType.ADDED && !it.hasConflicts }

/**
 * What discarding unstaged changes does: it restores [entries], and leaves alone [keptNewFiles] new files and
 * [keptConflicted] files with conflicts, which its dialog says.
 */
data class DiscardedFiles(val entries: List<StatusEntry>, val keptNewFiles: Int, val keptConflicted: Int)

/** What discarding these entries, the unstaged files that a folder row stands for, does: [discardable]. */
fun List<StatusEntry>.folderDiscard() = DiscardedFiles(
    entries = discardable(),
    keptNewFiles = count { it.statusType == StatusType.ADDED },
    keptConflicted = count { it.hasConflicts },
)

/**
 * What discarding these entries, the selected unstaged files, does: the files with conflicts are left as they are. New
 * files go along, as before, and checking them out changes nothing, but for those added with `git add -N`, which it
 * would empty (fork-only): they are left as they are, as a single one has no Discard.
 */
fun List<StatusEntry>.selectionDiscard() = DiscardedFiles(
    entries = filterNot { it.hasConflicts || it.isIntentToAddFile },
    keptNewFiles = 0,
    keptConflicted = count { it.hasConflicts },
)
