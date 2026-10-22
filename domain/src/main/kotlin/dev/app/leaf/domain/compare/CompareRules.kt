// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.compare

import dev.app.leaf.domain.log.LogRef
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonRelation
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository

/** One side of a comparison (fork-only, `docs/fork/compare.md`, section 3.1). */
sealed interface CompareSide {
    /** What git compares: a full ref, or a commit id. */
    val revision: String

    /** The commit it pointed to when it was picked. */
    val commit: String

    /** How the comparison names it: a branch's short name, a tag's, or the commit's short id. */
    val name: String

    /** A branch, which the comparison follows when it moves. [ref] is its full name. */
    data class Branch(val ref: String, override val commit: String) : CompareSide {
        override val revision: String get() = ref
        override val name: String get() = Repository.shortenRefName(ref)
    }

    /** A commit, which stays where it is. [tag], the full name of a tag at it, only names it. */
    data class Commit(override val commit: String, val tag: String? = null) : CompareSide {
        override val revision: String get() = commit
        override val name: String get() = tag?.let { Repository.shortenRefName(it) } ?: commit.take(7)
    }

    /**
     * The working tree (7.2): the files on disk, with what isn't committed yet, as `git diff A` compares them. [commit]
     * is HEAD's, and [branch] the branch checked out (a full ref), or null for a detached HEAD. It's always B.
     */
    data class WorkingTree(override val commit: String, val branch: String?) : CompareSide {
        override val revision: String get() = Constants.HEAD
        override val name: String get() = "Working tree"

        /** The branch checked out, or the commit of a detached HEAD, which the working tree is on. */
        val onName: String get() = branch?.let { Repository.shortenRefName(it) } ?: commit.take(7)
    }
}

/**
 * The side that ⌘-click takes on a log row: the row's first branch, in the order of its chips ([refs], from
 * `logRefs`), so the current branch before others and local branches before remote ones. A detached HEAD isn't a
 * branch to follow. A row without a branch is its commit, named by its first tag.
 */
fun compareSideOf(commit: String, refs: List<LogRef>): CompareSide {
    val branch = refs.firstNotNullOfOrNull { ref ->
        when (ref) {
            is LogRef.LocalBranch -> ref.branch
            is LogRef.RemoteBranch -> ref.branch
            is LogRef.TagRef -> null
        }?.takeIf { it.name != Constants.HEAD }
    }

    return if (branch != null) {
        CompareSide.Branch(branch.name, commit)
    } else {
        CompareSide.Commit(commit, tag = refs.firstNotNullOfOrNull { (it as? LogRef.TagRef)?.tag?.name })
    }
}

/**
 * Two sides that the user picked, as A and B before git has compared them: the older commit first, by commit date
 * ([firstDate], [secondDate], in any unit). Equal dates keep the order they were picked in. Once git has compared them,
 * an ancestor that ended up as B goes first ([putsAncestorFirst]).
 */
fun inPickedOrder(
    first: CompareSide,
    firstDate: Long,
    second: CompareSide,
    secondDate: Long,
): Pair<CompareSide, CompareSide> = if (secondDate < firstDate) second to first else first to second

/**
 * Whether a comparison that just started should swap its sides: when B turned out to be A's ancestor, as commit dates
 * can disagree with history (a wrong clock, a date set by hand), and a menu puts the current branch first whatever its
 * age. An ancestor goes first, so that the changes read forwards, from the older snapshot to the newer.
 */
val ComparisonRelation.putsAncestorFirst: Boolean get() = this == ComparisonRelation.B_IS_ANCESTOR

/**
 * The mode a comparison starts in: since they split when both sides are branches, all differences otherwise. The working
 * tree counts as its branch: against a branch, since they split is what it and its uncommitted work would bring.
 */
fun defaultMode(a: CompareSide, b: CompareSide): ComparisonMode =
    if (a is CompareSide.Branch && (b is CompareSide.Branch || b is CompareSide.WorkingTree)) {
        ComparisonMode.SINCE_SPLIT
    } else {
        ComparisonMode.ALL
    }

/** The switch between the two modes shows only for sides that diverged: otherwise there is one answer. */
val ComparisonRelation.showsModeSwitch: Boolean get() = this == ComparisonRelation.DIVERGED

/**
 * The switch, when B is the working tree (7.2), whose relation is HEAD's: when A has commits that HEAD lacks, since they
 * split is the uncommitted work alone, and all differences shows A's newer commits as undone too.
 */
fun ComparisonRelation.showsModeSwitch(bIsWorkingTree: Boolean): Boolean =
    showsModeSwitch || (bIsWorkingTree && this == ComparisonRelation.B_IS_ANCESTOR)

/**
 * The mode a comparison of sides in this relation shows, when [chosen] is asked for. Since they split needs history of
 * B's own after a merge base: without a merge base, or when B is A's ancestor, it would list nothing, so the
 * comparison shows all differences. When A is B's ancestor, the two modes list the same changes, so [chosen] stays.
 */
fun ComparisonRelation.modeToShow(chosen: ComparisonMode): ComparisonMode = when (this) {
    ComparisonRelation.SAME, ComparisonRelation.A_IS_ANCESTOR, ComparisonRelation.DIVERGED -> chosen
    ComparisonRelation.B_IS_ANCESTOR, ComparisonRelation.UNRELATED -> ComparisonMode.ALL
}

/**
 * [modeToShow], when B is the working tree: HEAD being A's ancestor still leaves the uncommitted changes since they
 * split, so the mode stays the one chosen. Without a merge base, all differences, as for any comparison.
 */
fun ComparisonRelation.modeToShow(chosen: ComparisonMode, bIsWorkingTree: Boolean): ComparisonMode =
    if (bIsWorkingTree && this == ComparisonRelation.B_IS_ANCESTOR) chosen else modeToShow(chosen)

/** The relation once A and B change places. */
val ComparisonRelation.swapped: ComparisonRelation
    get() = when (this) {
        ComparisonRelation.A_IS_ANCESTOR -> ComparisonRelation.B_IS_ANCESTOR
        ComparisonRelation.B_IS_ANCESTOR -> ComparisonRelation.A_IS_ANCESTOR
        ComparisonRelation.SAME, ComparisonRelation.DIVERGED, ComparisonRelation.UNRELATED -> this
    }

/**
 * How two commits' histories meet, from the commits that only A has ([countOnlyInA]) and only B has ([countOnlyInB]),
 * and whether they have a merge base.
 */
fun comparisonRelation(countOnlyInA: Int, countOnlyInB: Int, hasMergeBase: Boolean): ComparisonRelation = when {
    countOnlyInA == 0 && countOnlyInB == 0 -> ComparisonRelation.SAME
    countOnlyInA == 0 -> ComparisonRelation.A_IS_ANCESTOR
    countOnlyInB == 0 -> ComparisonRelation.B_IS_ANCESTOR
    !hasMergeBase -> ComparisonRelation.UNRELATED
    else -> ComparisonRelation.DIVERGED
}
