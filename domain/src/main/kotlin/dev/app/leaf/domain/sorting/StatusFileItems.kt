// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.hasConflicts

/** How a Staged or Unstaged entry sorts by change type. */
val StatusType.fileChangeKind: FileChangeKind
    get() = when (this) {
        StatusType.ADDED -> FileChangeKind.Added
        StatusType.MODIFIED -> FileChangeKind.Modified
        StatusType.REMOVED -> FileChangeKind.Deleted
        StatusType.CONFLICTING -> FileChangeKind.Conflicting
    }

/** The entries of a Staged or Unstaged pane as files to sort, keyed by path, with `#2` and so on for a repeated path. */
fun List<StatusEntry>.toFileItems(): List<FileItem<StatusEntry>> {
    val pathCounts = HashMap<String, Int>()

    return map { entry ->
        val count = pathCounts.merge(entry.filePath, 1, Int::plus) ?: 1

        FileItem(
            item = entry,
            key = if (count == 1) entry.filePath else "${entry.filePath}#$count",
            path = entry.filePath,
            kind = entry.statusType.fileChangeKind,
        )
    }
}

/** The entries inside [folderPath], at any depth. */
fun List<StatusEntry>.inFolder(folderPath: String): List<StatusEntry> =
    filter { it.filePath.startsWith("$folderPath/") }

/**
 * The unstaged entries that discarding changes restores. A new (untracked) file has no earlier version to go back to,
 * so it is left alone, as a single new file offers "Delete file" instead of discarding. So is a file with conflicts
 * ([hasConflicts]), which "Use ours" and "Use theirs" resolve.
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
 * files go along, as before, and checking them out changes nothing.
 */
fun List<StatusEntry>.selectionDiscard() = DiscardedFiles(
    entries = filterNot { it.hasConflicts },
    keptNewFiles = 0,
    keptConflicted = count { it.hasConflicts },
)
