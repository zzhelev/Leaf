// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Commit

/** The most commits the log search keeps (fork-only, upstream issue 277). The walk stops there, and the counter says "10,000+". */
const val MAX_SEARCH_HITS = 10_000

/**
 * How far down the log the search looks, in commits. A hit that far down is shown by loading the log to it, at about
 * 0.85 KB a commit (a probe on a 200,000-commit repository: 165 MB for all of them), so the search stops where the log
 * is allowed to go.
 */
const val MAX_SEARCH_DEPTH = 200_000

/**
 * Whether a commit matches what is typed in the log's search: the term is in its message, its author's or its
 * committer's name, or its hash, ignoring case. [lowercaseTerm] is the term in lower case, so that a search over many
 * commits lowers it once. The search of the loaded commits and the walk of the whole history both use it.
 */
fun matchesLogSearch(
    lowercaseTerm: String,
    hash: String,
    message: String,
    authorName: String?,
    committerName: String?,
): Boolean =
    hash.lowercase().contains(lowercaseTerm) ||
        authorName.orEmpty().lowercase().contains(lowercaseTerm) ||
        committerName.orEmpty().lowercase().contains(lowercaseTerm) ||
        message.lowercase().contains(lowercaseTerm)

fun Commit.matchesLogSearch(lowercaseTerm: String): Boolean =
    matchesLogSearch(lowercaseTerm, hash, message, author.name, committer.name)
