// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.models.Commit

/** Where [commit] is in a file's [commits], or -1 when it isn't there. */
fun positionOf(commits: List<Commit>, commit: Commit?): Int =
    if (commit == null) -1 else commits.indexOfFirst { it.hash == commit.hash }

/**
 * The commit [offset] rows below [selected] in a file's [commits], which are newest first, so a negative offset is a
 * newer commit. Null past either end. With no commit selected, or one that isn't in the list, the newest.
 */
fun adjacentCommit(commits: List<Commit>, selected: Commit?, offset: Int): Commit? {
    val index = positionOf(commits, selected)

    return if (index < 0) commits.firstOrNull() else commits.getOrNull(index + offset)
}
