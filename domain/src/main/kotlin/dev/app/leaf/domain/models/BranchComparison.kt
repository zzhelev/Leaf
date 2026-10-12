// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.eclipse.jgit.diff.DiffEntry

/** A comparison lists at most this many commits. More would only make a list too long to read. */
const val MAX_COMPARED_COMMITS = 1000

/**
 * What [branch] would bring into [base] (fork-only): the commits it has and the base doesn't, as `git log base..branch`
 * lists them, and the changes since their merge base, as `git diff base...branch` shows them. Both are full refs.
 *
 * @param mergeBase null when the two have no commit in common. [changes] is then empty, as git has nothing to compare.
 * @param commits oldest first, at most [MAX_COMPARED_COMMITS]. [ahead] counts them all.
 * @param behind the commits that [base] has and [branch] doesn't.
 * @param changes by path, from the merge base's tree to the branch's.
 */
data class BranchComparison(
    val base: String,
    val branch: String,
    val mergeBase: String?,
    val commits: List<Commit>,
    val ahead: Int,
    val behind: Int,
    val changes: List<DiffEntry>,
)
