// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.common.printError
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.IGetAheadBehindGitAction
import dev.app.leaf.domain.interfaces.IGetCommitTimesGitAction
import dev.app.leaf.domain.interfaces.IGetMergedIntoBaseGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeBaseBranchGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreesGitAction
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreesUntouchedDays
import dev.app.leaf.domain.worktrees.branchToMarkMerged
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

private const val TAG = "GetWorktreesInfoUseCase"

/** Each worktree's status and comparison run git; this many processes run at once. */
private const val MAX_CONCURRENT_WORKTREES = 4

private const val DAY_IN_MS = 24 * 60 * 60 * 1000L

/**
 * Lists the repository's worktrees with their changes, how they compare to the base branch, their last commit, whether
 * their work is in the base, and since when nothing changed in them. Only the list itself is required: a part that
 * fails for one worktree is left null for it.
 */
class GetWorktreesInfoUseCase @Inject constructor(
    private val getWorktreesGitAction: IGetWorktreesGitAction,
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
    private val getAheadBehindGitAction: IGetAheadBehindGitAction,
    private val getWorktreeBaseBranchGitAction: IGetWorktreeBaseBranchGitAction,
    private val getCommitTimesGitAction: IGetCommitTimesGitAction,
    private val getMergedIntoBaseGitAction: IGetMergedIntoBaseGitAction,
    private val getWorktreeActivityGitAction: IGetWorktreeActivityGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): Either<WorktreeList, AppError> = useCaseExecutor.execute { repositoryPath ->
        val worktrees = getWorktreesGitAction(repositoryPath).bind()

        val base = getWorktreeBaseBranchGitAction(repositoryPath)
            .onErr { printError(TAG, "Could not find the base branch: $it") }
            .okOrNull()
            ?: WorktreeBaseBranch(automatic = null)
        val baseBranch = base.effective

        val commitTimes = getCommitTimesGitAction(repositoryPath, worktrees.mapNotNull { it.headSha })
            .onErr { printError(TAG, "Could not read the commit times: $it") }
            .okOrNull()
            .orEmpty()

        // The settings' days apply in the tab (withIdle), so a change there shows without reading the worktrees again
        val untouchedBefore = System.currentTimeMillis() - WorktreesUntouchedDays.MIN_DAYS * DAY_IN_MS
        val semaphore = Semaphore(MAX_CONCURRENT_WORKTREES)

        val infos = coroutineScope {
            worktrees.map { worktree ->
                async {
                    semaphore.withPermit {
                        val aheadBehindBase = aheadBehindBase(repositoryPath, worktree, baseBranch)

                        WorktreeInfo(
                            worktree = worktree,
                            status = status(worktree),
                            aheadBehindBase = aheadBehindBase,
                            lastCommitTime = worktree.headSha?.let { commitTimes[it] },
                            merged = merged(repositoryPath, worktree, baseBranch, aheadBehindBase),
                            idleSince = idleSince(worktree, untouchedBefore),
                        )
                    }
                }
            }.awaitAll()
        }

        Either.Ok(WorktreeList(base, infos))
    }

    private suspend fun status(worktree: Worktree) = when {
        worktree.isBare || worktree.prunable != null -> null
        else -> getWorktreeStatusGitAction(worktree.path)
            .onErr { printError(TAG, "Could not read the status of ${worktree.path}: $it") }
            .okOrNull()
    }

    private suspend fun aheadBehindBase(repositoryPath: String, worktree: Worktree, baseBranch: String?): AheadBehind? {
        // A worktree that rebases or bisects is detached, but its work is on that branch
        val target = worktree.branch ?: worktree.rebasingBranch ?: worktree.bisectingBranch ?: worktree.headSha

        if (baseBranch == null || target == null || target == baseBranch) {
            return null
        }

        return getAheadBehindGitAction(repositoryPath, baseBranch, target)
            .onErr { printError(TAG, "Could not compare ${worktree.path} to $baseBranch: $it") }
            .okOrNull()
    }

    private suspend fun merged(
        repositoryPath: String,
        worktree: Worktree,
        baseBranch: String?,
        aheadBehindBase: AheadBehind?,
    ): MergedIntoBase? {
        val branch = worktree.branchToMarkMerged(baseBranch)

        if (branch == null || baseBranch == null || aheadBehindBase == null) {
            return null
        }

        return getMergedIntoBaseGitAction(repositoryPath, baseBranch, branch, aheadBehindBase.ahead, worktree.startCommit)
            .onErr { printError(TAG, "Could not tell whether ${worktree.path} is merged into $baseBranch: $it") }
            .okOrNull()
    }

    /** When something last changed in a linked worktree, if that was before [notBefore]. */
    private suspend fun idleSince(worktree: Worktree, notBefore: Long): Long? {
        if (worktree.isMain || worktree.isBare || worktree.prunable != null) {
            return null
        }

        val lastChange = getWorktreeActivityGitAction(worktree.path, notBefore)
            .onErr { printError(TAG, "Could not read when ${worktree.path} last changed: $it") }
            .okOrNull()

        return lastChange?.takeIf { it < notBefore }
    }
}
