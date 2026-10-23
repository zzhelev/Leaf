// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse

/**
 * Which branches the "Delete branches" dialog is about (fork-only, upstream issue 116): local branches, or the branches
 * of one remote, which it deletes on the server.
 *
 * @param branches the branches as the side panel lists them. Their tips aren't used: the dialog reads them again.
 * @param mergedOnly "Delete merged branches": [branches] are every local branch, or every branch of [remote], and the
 * dialog lists the ones merged into the base, with those that can't be deleted dimmed, and not the others.
 * @param remote the remote whose branches [branches] are (`refs/remotes/<remote>/x`), null for local branches.
 */
data class BranchesDeletionRequest(
    val branches: List<Branch>,
    val mergedOnly: Boolean = false,
    val remote: String? = null,
)

/**
 * A branch and the commit it pointed to when the dialog read it. Deleting it checks that it still does: a local branch
 * here, a remote's branch on the server, which then refuses when someone pushed to it.
 */
data class BranchToDelete(
    /** The full name, `refs/heads/x`, or the remote-tracking branch `refs/remotes/origin/x` of a remote's branch. */
    val name: String,
    /** The commit's full hash. */
    val tip: String,
    /**
     * For a remote's branch deleted anyway after someone pushed to it: the commit the server said it has, which the
     * lease then names, while [tip] stays what the remote-tracking branch points to.
     */
    val serverTip: String? = null,
) {
    /** The commit the server must still have for the delete to go through. */
    val leaseTip: String get() = serverTip ?: tip

    /** The name without `refs/heads/`, or without `refs/remotes/<remote>/`: the branch's name on its remote. */
    val simpleName: String
        get() = if (name.startsWith(REMOTE_BRANCHES)) {
            name.removePrefix(REMOTE_BRANCHES).substringAfter('/')
        } else {
            name.removePrefix(LOCAL_BRANCHES)
        }

    val shortTip: String get() = tip.take(SHORT_HASH_LENGTH)
}

private const val LOCAL_BRANCHES = "refs/heads/"
private const val REMOTE_BRANCHES = "refs/remotes/"

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

    /** A remote's `HEAD`, which only names the remote's default branch: there's no such branch to delete. */
    data object RemoteHead : BranchKept

    /**
     * The remote's default branch, the one its `HEAD` names, which servers refuse to delete. When the remote has no
     * `HEAD` ([isNamedByRemote] false), the branch with the base branch's name, which the others are compared to.
     */
    data class RemoteDefaultBranch(val isNamedByRemote: Boolean) : BranchKept

    /**
     * The server has another commit for the branch than the one the dialog showed, [now] (null when Leaf couldn't ask):
     * someone pushed to it since the last fetch. The lease refused it, so nothing was deleted, and the user can delete
     * it anyway, that commit then being the lease. A branch that was gone already counts as deleted.
     */
    data class ChangedOnRemote(val now: String?) : BranchKept {
        val shortNow: String? get() = now?.take(SHORT_HASH_LENGTH)
    }

    /** The server refused to delete the branch, with git's [reason], such as `protected branch hook declined`. */
    data class RefusedByRemote(val reason: String) : BranchKept

    /** The user stopped git before the server deleted the branch: asked afterwards, the server still has it. */
    data object StillOnRemote : BranchKept
}

/** What deleting one branch did. */
sealed interface BranchDeletionOutcome {
    val branch: BranchToDelete

    data class Deleted(override val branch: BranchToDelete) : BranchDeletionOutcome

    data class Kept(override val branch: BranchToDelete, val reason: BranchKept) : BranchDeletionOutcome
}

/**
 * What deleting several branches did, in the order they were given. A branch that stays never stops the others.
 *
 * @param remoteMessage what the server said while deleting a remote's branches, or local branches' copies on theirs,
 * git's `remote:` lines, such as why a hook refused. Null when it said nothing.
 * @param copies what happened to the copies of the local branches that were deleted on their remotes too
 * ([BranchCopy]), by the local branch's full name.
 * @param copyErrors why the copies on a remote couldn't be deleted at all, such as a server out of reach, by remote.
 * @param wasStopped the user stopped git while it deleted on a server. The server was asked afterwards what it still
 * had, which [outcomes] (a remote's branches) or [copies] say.
 */
data class BranchesDeletion(
    val outcomes: List<BranchDeletionOutcome>,
    val remoteMessage: String? = null,
    val copies: Map<String, BranchDeletionOutcome> = emptyMap(),
    val copyErrors: Map<String, AppError> = emptyMap(),
    val wasStopped: Boolean = false,
) {
    val deleted: List<BranchToDelete>
        get() = outcomes.filterIsInstance<BranchDeletionOutcome.Deleted>().map { it.branch }

    val kept: List<BranchDeletionOutcome.Kept>
        get() = outcomes.filterIsInstance<BranchDeletionOutcome.Kept>()

    /**
     * A remote's branches that someone pushed to since the last fetch, each with the commit the server has now, which
     * deleting them anyway takes as its lease.
     */
    val changedOnRemote: List<BranchToDelete>
        get() = kept.mapNotNull { it.changedTo() }

    /** The same for the copies, by local branch: each copy's remote, and the copy with the server's commit. */
    val changedCopies: Map<String, Pair<String, BranchToDelete>>
        get() = copies.mapNotNull { (local, outcome) ->
            val changed = (outcome as? BranchDeletionOutcome.Kept)?.changedTo() ?: return@mapNotNull null

            local to (changed.name.removePrefix(REMOTE_BRANCHES).substringBefore('/') to changed)
        }.toMap()

    /** This result with [updated] in place of the outcomes of the same branches, and the server's [message] added. */
    fun withOutcomes(updated: List<BranchDeletionOutcome>, message: String? = null): BranchesDeletion {
        val byName = updated.associateBy { it.branch.name }

        return copy(
            outcomes = outcomes.map { byName[it.branch.name] ?: it },
            remoteMessage = listOfNotNull(remoteMessage, message).joinToString("\n").ifBlank { null },
        )
    }

    /** The copies that their remotes deleted, each once. */
    val deletedCopies: List<BranchToDelete>
        get() = copies.values.filterIsInstance<BranchDeletionOutcome.Deleted>().map { it.branch }.distinct()

    /**
     * One line for each deleted branch with its tip: `git branch <name> <tip>` brings a local one back, and
     * `git push <remote> <tip>:refs/heads/<name>` a remote's. Copies come after, named with their remote, `origin/x`.
     */
    fun deletedList(): String =
        (deleted.map { "${it.simpleName} ${it.tip}" } + deletedCopies.map { "${it.remoteAndName} ${it.tip}" })
            .joinToString("\n")
}

/** The branch with the commit its server has now as its lease, when someone pushed to it since the fetch. */
private fun BranchDeletionOutcome.Kept.changedTo(): BranchToDelete? =
    (reason as? BranchKept.ChangedOnRemote)?.now?.let { branch.copy(serverTip = it) }

/** A remote-tracking branch's remote and name, `origin/x`. */
val BranchToDelete.remoteAndName: String
    get() = name.removePrefix(REMOTE_BRANCHES)

/**
 * What the dialog read about one branch.
 *
 * @param refusal why the branch can't be deleted, null when it can: a worktree uses a local branch
 * ([BranchKept.UsedByWorktree]); a remote's is its `HEAD` or its default branch.
 * @param merged how the branch got into the base, null when it didn't, or there's no base.
 * @param commitsOnlyHere the commits that no ref outside the selected branches has, which deleting the branch leaves
 * on no branch or tag. Counted against the refs outside the whole selection, so that two selected branches at one
 * commit don't both look safe.
 * @param lastCommit who made the branch's tip and when, read for a remote's branches, which others push too.
 * @param upstream a local branch's upstream, when its remote-tracking branch exists: its copy on the remote.
 */
data class BranchDeletionInfo(
    val branch: BranchToDelete,
    val refusal: BranchKept?,
    val merged: MergedIntoBase?,
    val commitsOnlyHere: Int,
    val lastCommit: TipCommit? = null,
    val upstream: BranchUpstream? = null,
)

/** A local branch's upstream: [remote]'s remote-tracking branch [trackingBranch] (`refs/remotes/origin/x`). */
data class BranchUpstream(val remote: String, val trackingBranch: String)

/**
 * A local branch's copy on its remote, its upstream as last fetched, which the dialog can delete on the server with the
 * branch. [info] is the remote-tracking branch's, read as the remote's own dialog reads it, and [kind] what that dialog
 * would say. It [goes] with its branch when the user asks for copies, unless it's the remote's `HEAD` or default branch,
 * which servers refuse to delete: Leaf marks the risky ones ([isRisky]) and leaves the choice to the user, who can
 * protect a branch on the server.
 */
data class BranchCopy(
    val remote: String,
    val info: BranchDeletionInfo,
    val kind: BranchDeletionKind,
    val looksLongLived: Boolean,
    val goes: Boolean,
) {
    /** Not merged into the remote's default branch, where a server can close its pull requests, or named like one kept for good. */
    val isRisky: Boolean get() = goes && (kind !is BranchDeletionKind.Merged || looksLongLived)
}

/** The author of a branch's tip commit, and its commit time in milliseconds since the epoch. */
data class TipCommit(val author: String, val timeMillis: Long)

/**
 * The remote whose branches a plan is about: its [name], the [url] it fetches from (null when it has none), and when
 * it was last fetched from ([lastFetchedMillis], null when Leaf can't tell), which says how old what the dialog shows is.
 */
data class DeletionRemote(val name: String, val url: String?, val lastFetchedMillis: Long?)

/** What the dialog says about a branch, which also decides whether it's checked at first. */
sealed interface BranchDeletionKind {
    /** Can't be deleted. */
    data class Refused(val reason: BranchKept) : BranchDeletionKind

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
 * What the dialog read: the branches' [infos], and [base], the full name of the branch that "merged" means merged into
 * (null when there is none): the one worktrees are compared to, or for a [remote]'s branches the remote's default
 * branch (`refs/remotes/origin/main`). Local branches' [copies] on their remotes, by the local branch's full name,
 * which git couldn't read when it can't run.
 */
data class BranchDeletionPlan(
    val base: String?,
    val infos: List<BranchDeletionInfo>,
    val mergedOnly: Boolean = false,
    val remote: DeletionRemote? = null,
    val copies: Map<String, BranchCopy> = emptyMap(),
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

    /**
     * Whether [info]'s branch is checked when the dialog opens, and by "Check: safe": it loses nothing. A remote's
     * must be merged, as one whose commits are only on other branches, the user's own included, may have a pull
     * request open, and not [looksLongLived], as it may be merged and still in use.
     */
    fun isCheckedAtFirst(info: BranchDeletionInfo): Boolean {
        val kind = kindOf(info)

        return if (remote != null) kind is BranchDeletionKind.Merged && !looksLongLived(info) else kind.isSafe
    }

    /** A remote's branch whose name says that it's kept for good, such as `develop` or `release/2.0`. */
    fun looksLongLived(info: BranchDeletionInfo): Boolean = remote != null && looksLongLived(info.branch.simpleName)

    /** The branches that are checked when the dialog opens. */
    fun checkedAtFirst(): Set<String> =
        rows.filter { isCheckedAtFirst(it) }.map { it.branch.name }.toSet()

    /** Whether the user can check [info]'s branch. */
    fun canCheck(info: BranchDeletionInfo): Boolean = info.refusal == null
}

/** The branches to delete from what the user checked, in the dialog's order. Branches that can't be deleted are left out. */
fun BranchDeletionPlan.toDelete(checked: Set<String>): List<BranchToDelete> =
    rows.filter { it.branch.name in checked && canCheck(it) }.map { it.branch }

/** The copies that go with the [checked] branches, by the local branch's full name, in the dialog's order. */
fun BranchDeletionPlan.copiesToDelete(checked: Set<String>): Map<String, BranchCopy> =
    toDelete(checked).mapNotNull { branch -> copies[branch.name]?.takeIf { it.goes }?.let { branch.name to it } }.toMap()

/** Whether a branch that can be deleted has a copy that can go with it: the dialog then offers to delete copies. */
val BranchDeletionPlan.offersCopies: Boolean
    get() = rows.any { canCheck(it) && copies[it.branch.name]?.goes == true }

/**
 * The copies that this plan, read for a remote's remote-tracking branches, says about the local branches whose
 * upstreams they are ([upstreams]: local branch to remote-tracking branch, full names).
 */
fun BranchDeletionPlan.copiesFor(upstreams: Map<String, String>): Map<String, BranchCopy> {
    val remoteName = remote?.name ?: return emptyMap()
    val byName = infos.associateBy { it.branch.name }

    return upstreams.mapNotNull { (local, tracking) ->
        val info = byName[tracking] ?: return@mapNotNull null

        local to BranchCopy(remoteName, info, kindOf(info), looksLongLived(info), goes = canCheck(info))
    }.toMap()
}

/** Names that teams keep for good, compared without case: a branch merged into the base may still be in use. */
private val LONG_LIVED_NAMES = setOf(
    "main", "master", "trunk", "develop", "development", "dev", "next", "stable",
    "staging", "stage", "production", "prod", "live", "qa", "uat", "gh-pages",
)

/** Prefixes of names that teams keep for good, such as `release/2.0`, compared without case. */
private val LONG_LIVED_PREFIXES = listOf("release/", "releases/", "release-", "hotfix/", "support/", "maintenance/")

/**
 * Whether a branch's [name] (without `refs/heads/` or the remote) looks like one that's kept for good. Only what's
 * checked when the dialog opens depends on it: such a branch can still be checked.
 */
fun looksLongLived(name: String): Boolean {
    val lower = name.lowercase()

    return lower in LONG_LIVED_NAMES || LONG_LIVED_PREFIXES.any { lower.startsWith(it) }
}
