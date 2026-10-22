// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.cherrypick

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.squashMessage

/** The name git gives the current branch when HEAD is detached. */
private const val DETACHED_HEAD = "HEAD"

private const val SHORT_HASH_LENGTH = 7

/**
 * The most commits that the dialog lists and picks. A branch with more is far from the current branch, and is better
 * merged or rebased than cherry-picked.
 */
const val MAX_CHERRY_PICK_COMMITS = 500

/**
 * How a branch compares to the tab's HEAD, for its menu's cherry-pick item (fork-only).
 *
 * @param count the commits that the branch has and HEAD doesn't, as `git rev-list --count HEAD..<branch>` counts them,
 * or null when git can tell that there are some but can't count them (before git 2.41, which `%(ahead-behind)` needs).
 * @param tipIsMerge the branch's tip is a merge commit, which `git cherry-pick` refuses without `-m`.
 */
data class BranchAhead(val count: Int?, val tipIsMerge: Boolean)

/** [BranchAhead] of each branch that has commits HEAD lacks, by full ref (`refs/heads/x`, `refs/remotes/origin/x`). */
data class BranchesAhead(val byRef: Map<String, BranchAhead> = emptyMap()) {
    fun of(branch: Branch): BranchAhead? = byRef[branch.name]
}

/** What a branch's menu offers to cherry-pick onto the current branch (fork-only). */
sealed interface BranchCherryPickItem {
    /** The branch's only commit that the current branch lacks, its tip, picked at once as a commit row's item does. */
    data class OneCommit(val hash: String) : BranchCherryPickItem {
        val shortHash: String get() = hash.take(SHORT_HASH_LENGTH)
    }

    /** Several commits, or some that git couldn't count ([count] null): the dialog lists them first. */
    data class Commits(val count: Int?) : BranchCherryPickItem
}

/**
 * The cherry-pick item of [branch]'s menu, or null for none: on the current branch, on a detached HEAD (there's no
 * branch to copy onto, as for Merge and Rebase), and when the branch has nothing to pick. A branch whose only new
 * commit is a merge has nothing either.
 */
fun branchCherryPickItem(branch: Branch, currentBranch: Branch?, ahead: BranchAhead?): BranchCherryPickItem? {
    if (currentBranch == null || currentBranch.name == DETACHED_HEAD || branch.name == currentBranch.name) {
        return null
    }

    ahead ?: return null
    val count = ahead.count ?: return BranchCherryPickItem.Commits(null)

    return when {
        count <= 0 -> null
        count == 1 && ahead.tipIsMerge -> null
        count == 1 -> BranchCherryPickItem.OneCommit(branch.hash)
        else -> BranchCherryPickItem.Commits(count)
    }
}

/** Why a commit of the branch isn't picked. */
enum class CherryPickLeftOut {
    /** A merge commit, which `git cherry-pick` refuses without `-m`, the parent to take the changes from. */
    MERGE,

    /** The current branch already has its changes (`git log --cherry-mark` marks it `=`): it would come out empty. */
    ALREADY_APPLIED,
}

data class CherryPickCandidate(val commit: Commit, val leftOut: CherryPickLeftOut? = null) {
    val canPick: Boolean get() = leftOut == null
}

/**
 * What cherry-picking a branch's commits onto the current branch needs (fork-only), for its dialog. Read when the
 * dialog opens, as the menu's count may be stale.
 *
 * @param currentBranch the branch checked out in the tab's worktree, a full ref, or `HEAD` when it's detached.
 * @param workingTree the tab's worktree, where git runs.
 * @param candidates the branch's commits that the current branch lacks, oldest first, the order git applies them.
 * Empty when there are more than [MAX_CHERRY_PICK_COMMITS].
 * @param commitCount how many commits the branch has that the current branch lacks.
 * @param isSequenceInProgress a cherry-pick or revert of several commits waits in the worktree ([CherryPickSequence]).
 * @param status the worktree's changes, null when git couldn't read them.
 */
data class BranchCherryPick(
    val branch: Branch,
    val currentBranch: String,
    val workingTree: String,
    val candidates: List<CherryPickCandidate>,
    val commitCount: Int,
    val repositoryState: RepositoryState,
    val isSequenceInProgress: Boolean,
    val status: WorktreeStatus?,
) {
    /** The commits that can be picked, oldest first. */
    val pickable: List<Commit> get() = candidates.filter { it.canPick }.map { it.commit }
}

/** Why the dialog's button is disabled. */
sealed interface CherryPickRefusal {
    /** HEAD is detached: there's no branch to copy the commits onto. */
    data object DetachedHead : CherryPickRefusal

    /** Every commit is left out ([CherryPickLeftOut]), or there's none. */
    data object NothingToPick : CherryPickRefusal

    data class TooManyCommits(val count: Int) : CherryPickRefusal

    /**
     * A merge, rebase, cherry-pick or revert waits to be finished. A squash merge's staged changes are refused as
     * [StagedChanges].
     */
    data object OperationInProgress : CherryPickRefusal

    /**
     * Staged changes or conflicts: git refuses to cherry-pick onto staged changes, and with `--no-commit` would mix
     * them into the commits' changes.
     */
    data class StagedChanges(val files: Int) : CherryPickRefusal

    data object StatusUnknown : CherryPickRefusal

    /** Every commit was unticked. */
    data object NoneSelected : CherryPickRefusal
}

/** Why picking [selected] (commit hashes) can't run, or null when it can. The first that applies, in this order. */
fun BranchCherryPick.refusal(selected: Set<String>): CherryPickRefusal? {
    val stagedOrConflicted = status?.let { it.staged + it.conflicted } ?: 0

    return when {
        currentBranch == DETACHED_HEAD -> CherryPickRefusal.DetachedHead
        commitCount > MAX_CHERRY_PICK_COMMITS -> CherryPickRefusal.TooManyCommits(commitCount)
        pickable.isEmpty() -> CherryPickRefusal.NothingToPick
        repositoryState != RepositoryState.SAFE || isSequenceInProgress ->
            CherryPickRefusal.OperationInProgress

        status == null -> CherryPickRefusal.StatusUnknown
        stagedOrConflicted > 0 -> CherryPickRefusal.StagedChanges(stagedOrConflicted)
        pickable.none { it.hash in selected } -> CherryPickRefusal.NoneSelected
        else -> null
    }
}

/**
 * The commits of [selected] that can be picked, in the branch's order, whatever order [selected] has: git applies them
 * in the order it's given them, and a commit before its parent would conflict.
 */
fun BranchCherryPick.picked(selected: Set<String>): List<Commit> = pickable.filter { it.hash in selected }

/**
 * What confirming the dialog runs, in the tab's worktree [workingTree]: `git cherry-pick` with [commits] by full hash,
 * in the branch's order. [squashMessage], for a pick as one commit, goes to `SQUASH_MSG` afterwards, which the commit
 * field then shows, as after a squash merge.
 */
data class CherryPickPlan(val workingTree: String, val commits: List<String>, val squashMessage: String?) {
    val noCommit: Boolean get() = squashMessage != null

    /** The same, as the steps that the dialog shows as commands. */
    val steps: List<WorktreeStep>
        get() = buildList {
            add(WorktreeStep.Git(workingTree, cherryPickArguments(commits, noCommit), mayStopAtConflicts = true))

            if (squashMessage != null) {
                add(WorktreeStep.WriteSquashMessage(workingTree, squashMessage))
            }
        }
}

/**
 * `cherry-pick` and its options for [commits]. With [noCommit], their changes are staged together (`--no-commit`). git
 * stops at a commit's conflicts, and keeps the rest in its sequencer for `--continue`.
 */
fun cherryPickArguments(commits: List<String>, noCommit: Boolean): List<String> =
    listOf("cherry-pick") + (if (noCommit) listOf("--no-commit") else emptyList()) + commits

/** The plan that picks [selected], as one commit with [asOneCommit], or null when none of them can be picked. */
fun BranchCherryPick.cherryPickPlan(selected: Set<String>, asOneCommit: Boolean): CherryPickPlan? {
    val commits = picked(selected).takeIf { it.isNotEmpty() } ?: return null
    val message = if (asOneCommit) squashMessage(branch.simpleNameWithRemote, commits, commits.size) else null

    return CherryPickPlan(workingTree, commits.map { it.hash }, message)
}

/**
 * A cherry-pick of several commits that git stopped before its end (fork-only): `git cherry-pick A B C` stopped at
 * B's conflicts, at a commit that came out empty, or because local changes were in the way. git keeps the commits
 * still to pick in `<git dir>/sequencer/todo`, and goes on with `--continue`, `--skip` or `--abort`. A single
 * cherry-pick has no sequencer.
 *
 * @param remaining the abbreviated hashes of the commits in git's todo: the one it stopped at, then the rest.
 * @param noCommit started with `--no-commit`: the picked commits are staged, not committed.
 */
data class CherryPickSequence(val remaining: List<String>, val noCommit: Boolean)

private val WHITESPACE = Regex("""\s+""")

/** `no-commit = true` in the sequencer's `opts`, which is git config. */
private val NO_COMMIT_OPTION = Regex(
    """^\s*no-commit\s*=\s*true\s*$""",
    setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
)

/**
 * The cherry-pick that git's sequencer files describe, or null when [todo] has no commit, or isn't a cherry-pick's
 * (a revert's has `revert` lines, which Leaf leaves as before).
 */
fun cherryPickSequenceOf(todo: String, opts: String?): CherryPickSequence? {
    val lines = todo.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    if (lines.isEmpty()) {
        return null
    }

    val hashes = lines.map { line ->
        val parts = line.split(WHITESPACE, limit = 3)

        if (parts.size < 2 || (parts[0] != "pick" && parts[0] != "p")) {
            return null
        }

        parts[1]
    }

    return CherryPickSequence(hashes, noCommit = opts?.let { NO_COMMIT_OPTION.containsMatchIn(it) } == true)
}
