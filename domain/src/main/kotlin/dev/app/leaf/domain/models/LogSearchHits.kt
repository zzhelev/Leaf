// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * A commit that the log search found (fork-only, upstream issue 277). [depth] is its place in the walk, counted from the
 * newest commit, which is about how many commits the log has to load to show it.
 */
data class SearchHit(val hash: String, val depth: Int)

/** What a walk of the history found: the [hits], in the log's order, and whether it stopped at the limit for them. */
data class FoundCommits(val hits: List<SearchHit>, val reachedHitLimit: Boolean)
