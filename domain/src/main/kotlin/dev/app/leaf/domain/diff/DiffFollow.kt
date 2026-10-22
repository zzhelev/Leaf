// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.diff

import dev.app.leaf.domain.extensions.filePath
import org.eclipse.jgit.diff.DiffEntry

/**
 * The entry of [changes], a commit's changed files, that shows the file at [path] in that commit: the one with that path,
 * or else the one that moved the file there from [path]. Null when the commit doesn't touch the file. A copy isn't a
 * move: its source is still where it was.
 *
 * This is how an open diff follows the selection from commit to commit. The path is the one the user opened, so a file
 * that an older commit knew under another name is shown only by the commit that renamed it.
 */
fun followedEntry(path: String, changes: List<DiffEntry>): DiffEntry? =
    changes.firstOrNull { it.filePath == path }
        ?: changes.firstOrNull { it.changeType == DiffEntry.ChangeType.RENAME && it.oldPath == path }
