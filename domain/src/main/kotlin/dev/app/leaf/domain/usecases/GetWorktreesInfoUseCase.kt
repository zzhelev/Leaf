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
import dev.app.leaf.domain.interfaces.IGetDefaultBaseBranchGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreesGitAction
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

private const val TAG = "GetWorktreesInfoUseCase"

/** Each worktree's status and comparison run git; this many processes run at once. */
private const val MAX_CONCURRENT_WORKTREES = 4

/**
 * Lists the repository's worktrees with their changes, how they compare to the base branch, and their last commit.
 * Only the list itself is required: a part that fails for one worktree is left null for it.
 */
class GetWorktreesInfoUseCase @Inject constructor(
    private val getWorktreesGitAction: IGetWorktreesGitAction,
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
    private val getAheadBehindGitAction: IGetAheadBehindGitAction,
    private val getDefaultBaseBranchGitAction: IGetDefaultBaseBranchGitAction,
    private val getCommitTimesGitAction: IGetCommitTimesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): Either<WorktreeList, AppError> = useCaseExecutor.execute { repositoryPath ->
        val worktrees = getWorktreesGitAction(repositoryPath).bind()

        val baseBranch = getDefaultBaseBranchGitAction(repositoryPath)
            .onErr { printError(TAG, "Could not find the base branch: $it") }
            .okOrNull()

        val commitTimes = getCommitTimesGitAction(repositoryPath, worktrees.mapNotNull { it.headSha })
            .onErr { printError(TAG, "Could not read the commit times: $it") }
            .okOrNull()
            .orEmpty()

        val semaphore = Semaphore(MAX_CONCURRENT_WORKTREES)

        val infos = coroutineScope {
            worktrees.map { worktree ->
                async {
                    semaphore.withPermit {
                        WorktreeInfo(
                            worktree = worktree,
                            status = status(worktree),
                            aheadBehindBase = aheadBehindBase(repositoryPath, worktree, baseBranch),
                            lastCommitTime = worktree.headSha?.let { commitTimes[it] },
                        )
                    }
                }
            }.awaitAll()
        }

        Either.Ok(WorktreeList(baseBranch, infos))
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
}
