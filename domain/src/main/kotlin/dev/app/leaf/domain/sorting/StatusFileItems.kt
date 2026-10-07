// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType

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
