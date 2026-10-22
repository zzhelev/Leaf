// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.cherrypick.BranchCherryPick
import dev.app.leaf.domain.cherrypick.MAX_CHERRY_PICK_COMMITS
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetBranchCherryPickCommitsGitAction
import dev.app.leaf.domain.interfaces.IGetCherryPickSequenceGitAction
import dev.app.leaf.domain.interfaces.IGetCurrentBranchGitAction
import dev.app.leaf.domain.interfaces.IGetRepositoryStateGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreePathGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.models.Branch
import javax.inject.Inject

/**
 * Reads what cherry-picking [Branch]'s commits onto the current branch needs (fork-only), for its dialog: the commits,
 * which of them can't be picked, and the worktree's state and changes. The menu's count may be stale, so they're read
 * again.
 */
class GetBranchCherryPickUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getCurrentBranchGitAction: IGetCurrentBranchGitAction,
    private val getWorktreePathGitAction: IGetWorktreePathGitAction,
    private val getBranchCherryPickCommitsGitAction: IGetBranchCherryPickCommitsGitAction,
    private val getRepositoryStateGitAction: IGetRepositoryStateGitAction,
    private val getCherryPickSequenceGitAction: IGetCherryPickSequenceGitAction,
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
) {
    suspend operator fun invoke(branch: Branch): Either<BranchCherryPick, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            val currentBranch = getCurrentBranchGitAction(repositoryPath).bind()
                ?: return@execute Either.Err(GenericError("The repository has no commits yet"))
            val workingTree = getWorktreePathGitAction(repositoryPath).bind()
            val commits = getBranchCherryPickCommitsGitAction(repositoryPath, branch, MAX_CHERRY_PICK_COMMITS).bind()

            Either.Ok(
                BranchCherryPick(
                    branch = branch,
                    currentBranch = currentBranch.name,
                    workingTree = workingTree,
                    candidates = commits.candidates,
                    commitCount = commits.commitCount,
                    repositoryState = getRepositoryStateGitAction(repositoryPath).bind(),
                    isSequenceInProgress = getCherryPickSequenceGitAction(repositoryPath).okOrNull() != null,
                    status = getWorktreeStatusGitAction(workingTree).okOrNull(),
                )
            )
        }
}
