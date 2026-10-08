// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

private const val NOT_FOR_MERGE = "not-for-merge"

/**
 * A line of the `FETCH_HEAD` file that `git fetch` writes: `<object id>\t[not-for-merge]\t<description>`.
 *
 * Fetching the remote that the current branch tracks marks its upstream for merge, and so does fetching a ref named on
 * the command line. git writes those lines first. The [description] is what `git pull` puts in its merge message:
 * `branch 'main' of https://example.com/team/repo`.
 */
data class FetchHeadEntry(val objectId: String, val forMerge: Boolean, val description: String)

fun parseFetchHead(text: String): List<FetchHeadEntry> {
    return text.lineSequence()
        .map { it.split('\t', limit = 3) }
        .filter { it.size == 3 }
        .map { (objectId, merge, description) -> FetchHeadEntry(objectId, merge != NOT_FOR_MERGE, description) }
        .toList()
}
