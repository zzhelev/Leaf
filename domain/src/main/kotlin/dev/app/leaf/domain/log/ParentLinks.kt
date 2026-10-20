// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Commit

/** A commit with this many parents or fewer shows them all. */
const val MAX_PARENT_ROWS = 4

/** A commit with more parents shows this many, and the rest behind "Show N more parents". */
const val COLLAPSED_PARENT_ROWS = 3

/**
 * One parent in a commit's details (fork-only, upstream issue 270).
 *
 * [commit] is the parent as the log has loaded it, or null while it is older than what the log loaded so far. That
 * decides what the row can say, and whether a click has to load more of the log before it can select the parent.
 */
data class ParentLink(
    val hash: String,
    /** Where git lists it among the commit's parents, from 1: the first is the branch that was merged into. */
    val position: Int,
    val commit: Commit?,
) {
    val shortHash: String get() = hash.take(SHORT_HASH_LENGTH)
    val isLoaded: Boolean get() = commit != null

    /** The subject line, or null when the parent isn't loaded or has no message. */
    val subject: String? get() = commit?.shortMessage?.takeIf { it.isNotBlank() }
}

/**
 * What the commit details show about a commit's parents.
 *
 * [shown] are the rows that are always there, in git's order, and [hidden] the ones behind "Show N more parents", which
 * only a commit with more than [MAX_PARENT_ROWS] parents has. A commit with none is a root commit.
 */
data class ParentLinks(
    val shown: List<ParentLink>,
    val hidden: List<ParentLink> = emptyList(),
) {
    val isRoot: Boolean get() = shown.isEmpty()

    /** A merge numbers its rows, as they stand for different branches. A commit with one parent needs no number. */
    val isNumbered: Boolean get() = shown.size + hidden.size > 1

    val all: List<ParentLink> get() = shown + hidden
}

/**
 * The parents that [commit]'s details list, for [loaded] to say which of them the log has.
 *
 * A stash lists only its first parent, the commit that it was made on: its other parents are the commits that git
 * records the index and the untracked files in, which the log doesn't show. Hashes that are blank or repeat an
 * earlier one are left out, so a damaged list can't give a row that jumps nowhere.
 */
fun parentLinks(commit: Commit, isStash: Boolean, loaded: (String) -> Commit?): ParentLinks {
    val hashes = commit.parentsHashes
        .filter { it.isNotBlank() }
        .distinct()
        .let { if (isStash) it.take(1) else it }

    val links = hashes.mapIndexed { index, hash -> ParentLink(hash, position = index + 1, commit = loaded(hash)) }

    return if (links.size <= MAX_PARENT_ROWS) {
        ParentLinks(shown = links)
    } else {
        ParentLinks(shown = links.take(COLLAPSED_PARENT_ROWS), hidden = links.drop(COLLAPSED_PARENT_ROWS))
    }
}

private const val SHORT_HASH_LENGTH = 7
