// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse

/**
 * Which local branches the "Delete branches" dialog is about (fork-only, upstream issue 116).
 *
 * @param branches the branches as the side panel lists them. Their tips aren't used: the dialog reads them again.
 * @param mergedOnly "Delete merged branches": [branches] are every local branch, and the dialog lists the ones merged
 * into the base, with those that can't be deleted dimmed, and not the others.
 */
data class BranchesDeletionRequest(
    val branches: List<Branch>,
    val mergedOnly: Boolean = false,
)

/** A local branch and the commit it pointed to when the dialog read it. Deleting it checks that it still does. */
data class BranchToDelete(
    /** The full name, `refs/heads/x`. */
    val name: String,
    /** The commit's full hash. */
    val tip: String,
) {
    val simpleName: String get() = name.removePrefix("refs/heads/")
    val shortTip: String get() = tip.take(SHORT_HASH_LENGTH)
}

private const val SHORT_HASH_LENGTH = 7

/** Why a branch isn't deleted. */
sealed interface BranchKept {
    /**
     * A worktree uses the branch: it has it checked out, or rebases it or bisects from it. [isHere] is the tab's own
     * worktree. git refuses to delete such a branch even with `-D`, as that worktree would be left on one that's gone.
     */
    data class UsedByWorktree(val path: String, val use: WorktreeBranchUse, val isHere: Boolean) : BranchKept

    /** The branch was deleted, or renamed, after the dialog read it. */
    data object NoLongerExists : BranchKept

    /** The branch points to another commit than the dialog showed, so what the user decided on isn't what's there. */
    data class Moved(val now: String) : BranchKept {
        val shortNow: String get() = now.take(SHORT_HASH_LENGTH)
    }

    /** Git or the file system refused. */
    data class Failed(val message: String) : BranchKept
}

/** What deleting one branch did. */
sealed interface BranchDeletionOutcome {
    val branch: BranchToDelete

    data class Deleted(override val branch: BranchToDelete) : BranchDeletionOutcome

    data class Kept(override val branch: BranchToDelete, val reason: BranchKept) : BranchDeletionOutcome
}

/** What deleting several branches did, in the order they were given. A branch that stays never stops the others. */
data class BranchesDeletion(val outcomes: List<BranchDeletionOutcome>) {
    val deleted: List<BranchToDelete>
        get() = outcomes.filterIsInstance<BranchDeletionOutcome.Deleted>().map { it.branch }

    val kept: List<BranchDeletionOutcome.Kept>
        get() = outcomes.filterIsInstance<BranchDeletionOutcome.Kept>()

    /** One line for each deleted branch with its tip, `git branch <name> <tip>` brings it back. */
    fun deletedList(): String = deleted.joinToString("\n") { "${it.simpleName} ${it.tip}" }
}

/**
 * What the dialog read about one local branch.
 *
 * @param refusal why the branch can't be deleted, null when it can: a worktree uses it.
 * @param merged how the branch got into the base, null when it didn't, or there's no base.
 * @param commitsOnlyHere the commits that no ref outside the selected branches has, which deleting the branch leaves
 * on no branch or tag. Counted against the refs outside the whole selection, so that two selected branches at one
 * commit don't both look safe.
 */
data class BranchDeletionInfo(
    val branch: BranchToDelete,
    val refusal: BranchKept.UsedByWorktree?,
    val merged: MergedIntoBase?,
    val commitsOnlyHere: Int,
)

/** What the dialog says about a branch, which also decides whether it's checked at first. */
sealed interface BranchDeletionKind {
    /** Can't be deleted. */
    data class Refused(val reason: BranchKept.UsedByWorktree) : BranchDeletionKind

    /** Its commits are in the base, or its changes are (a squash merge, whose [commitsOnlyHere] end up on no branch). */
    data class Merged(val into: MergedIntoBase, val commitsOnlyHere: Int) : BranchDeletionKind

    /** Not merged into the base, but every one of its commits is on another branch or tag too. */
    data object OnOtherRefs : BranchDeletionKind

    /** Not merged, and [commitsOnlyHere] commits are on no other branch or tag. */
    data class Unmerged(val commitsOnlyHere: Int) : BranchDeletionKind

    /** Whether deleting loses nothing that another branch or tag doesn't have, or that the base doesn't have in effect. */
    val isSafe: Boolean get() = this is Merged || this is OnOtherRefs
}

/**
 * What the dialog read: the branches' [infos], and [base], the full name of the branch that worktrees are compared to
 * and "merged" means merged into (null when there is none).
 */
data class BranchDeletionPlan(
    val base: String?,
    val infos: List<BranchDeletionInfo>,
    val mergedOnly: Boolean = false,
) {
    /**
     * The base's own branch is among the branches that can be deleted: the others can't count as merged into it, as it
     * may go too, so [kindOf] leaves "merged" out and they stand on their commits alone. A base that a worktree uses,
     * the current branch included, stays, and what is merged into it is still merged.
     */
    private val baseIsSelected: Boolean = base != null && infos.any { it.branch.name == base && it.refusal == null }

    fun kindOf(info: BranchDeletionInfo): BranchDeletionKind {
        val refusal = info.refusal
        val merged = info.merged

        return when {
            refusal != null -> BranchDeletionKind.Refused(refusal)
            merged != null && !baseIsSelected -> BranchDeletionKind.Merged(merged, info.commitsOnlyHere)
            info.commitsOnlyHere == 0 -> BranchDeletionKind.OnOtherRefs
            else -> BranchDeletionKind.Unmerged(info.commitsOnlyHere)
        }
    }

    /**
     * The rows the dialog lists, in the order given: every branch, or with [mergedOnly] the merged ones and the refused
     * ones that are merged, so that the user sees why those stay.
     */
    val rows: List<BranchDeletionInfo> =
        if (mergedOnly) infos.filter { it.merged != null && !baseIsSelected } else infos

    /** The branches that are checked when the dialog opens: the ones that lose nothing. */
    fun checkedAtFirst(): Set<String> =
        rows.filter { kindOf(it).isSafe }.map { it.branch.name }.toSet()

    /** Whether the user can check [info]'s branch. */
    fun canCheck(info: BranchDeletionInfo): Boolean = info.refusal == null
}

/** The branches to delete from what the user checked, in the dialog's order. Branches that can't be deleted are left out. */
fun BranchDeletionPlan.toDelete(checked: Set<String>): List<BranchToDelete> =
    rows.filter { it.branch.name in checked && canCheck(it) }.map { it.branch }
