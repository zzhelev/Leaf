// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetAheadBehindGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.worktrees.branchToRebase
import dev.app.leaf.domain.worktrees.mergeTarget
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Reads what rebasing a worktree's branch onto the base needs (fork-only), for its dialog: the worktree's changes and
 * last change, and how many commits the rebase replays and brings in. The worktree list may be stale, so these are read
 * again.
 */
class GetWorktreeRebaseUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
    private val getWorktreeActivityGitAction: IGetWorktreeActivityGitAction,
    private val getAheadBehindGitAction: IGetAheadBehindGitAction,
    private val runGitCommandGitAction: IRunGitCommandGitAction,
) {
    suspend operator fun invoke(worktree: Worktree): Either<WorktreeRebase, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            val list = repositoryDataRepository.worktrees.first { it !is DataState.Loading }.dataOrNull()
                ?: return@execute Either.Err(GenericError("The worktree list couldn't be read"))
            val localBranches = repositoryDataRepository.localBranches.first { it !is DataState.Loading }.dataOrNull()
                ?: return@execute Either.Err(GenericError("The branches couldn't be read"))

            // The list may be newer than the row that was clicked, for example on another branch since
            val current = list.worktrees.map { it.worktree }.firstOrNull { it.path == worktree.path } ?: worktree
            val base = list.baseBranch
                ?: return@execute Either.Err(GenericError("The worktrees have no base branch"))
            val mergeTarget = mergeTarget(base, localBranches.map { it.name })
            val branch = current.branchToRebase(base, mergeTarget)
                ?: return@execute Either.Err(GenericError("${current.path} has no branch to rebase"))
            val aheadBehind = getAheadBehindGitAction(repositoryPath, base, branch).bind()
            val isMissing = current.prunable != null

            Either.Ok(
                WorktreeRebase(
                    worktree = current,
                    branch = branch,
                    base = base,
                    baseArgument = runGitCommandGitAction.refArgument(repositoryPath, base),
                    status = if (isMissing) null else getWorktreeStatusGitAction(current.path).okOrNull(),
                    lastChange = if (isMissing) null else getWorktreeActivityGitAction(current.path).okOrNull(),
                    commitCount = aheadBehind.ahead,
                    baseCommitCount = aheadBehind.behind,
                )
            )
        }
}
