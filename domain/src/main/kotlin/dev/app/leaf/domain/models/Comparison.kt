// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.eclipse.jgit.diff.DiffEntry

/** A comparison lists at most this many commits per side. More would only make a list too long to read. */
const val MAX_COMPARED_COMMITS = 1000

/** Which changes a comparison of A and B lists (fork-only). */
enum class ComparisonMode {
    /**
     * What B changed since it branched off A, as `git diff A...B` shows it: from their merge base's tree to B's. What A
     * did since is left out. With no merge base there is nothing to compare, as git refuses `A...B` then.
     */
    SINCE_SPLIT,

    /** Every difference between the two snapshots, as `git diff A B` shows it: from A's tree to B's. */
    ALL,
}

/** How A's and B's histories meet (fork-only). */
enum class ComparisonRelation {
    /** The same commit. */
    SAME,

    /** B has every commit of A's, and more: both modes list the same changes. */
    A_IS_ANCESTOR,

    /** A has every commit of B's, and more: B changed nothing since they split. */
    B_IS_ANCESTOR,

    /** Each has commits that the other lacks, after a merge base. */
    DIVERGED,

    /** No commit in common, as with an orphan branch. */
    UNRELATED,
}

/**
 * Two commits compared (fork-only): the commits that each has and the other lacks, as `git log A..B` and `B..A` list
 * them, and the changes of [mode]. [a] and [b] are what was asked for: full refs (`refs/heads/agent1`,
 * `refs/remotes/origin/main`) or commit ids.
 *
 * @param mergeBase null when the two have no commit in common. With several (a criss-cross merge), the first that JGit
 * finds, as git takes one too.
 * @param commitsOnlyInB oldest first, the newest [MAX_COMPARED_COMMITS] of them. [countOnlyInB] counts them all.
 * @param commitsOnlyInA the same for A.
 * @param changes by path, with moved files as renames. Empty for [ComparisonMode.SINCE_SPLIT] without a merge base.
 * @param untrackedPaths when B is the working tree: the new files of [changes] that git doesn't track yet.
 */
data class Comparison(
    val a: String,
    val b: String,
    val mode: ComparisonMode,
    val relation: ComparisonRelation,
    val mergeBase: String?,
    val commitsOnlyInB: List<Commit>,
    val countOnlyInB: Int,
    val commitsOnlyInA: List<Commit>,
    val countOnlyInA: Int,
    val changes: List<DiffEntry>,
    val untrackedPaths: Set<String> = emptySet(),
)
