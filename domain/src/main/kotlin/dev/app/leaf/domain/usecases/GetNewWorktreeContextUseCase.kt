// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetWorktreeCreationSettingsGitAction
import dev.app.leaf.domain.models.NewWorktreeContext
import dev.app.leaf.domain.models.WorktreeCreationSettings
import dev.app.leaf.domain.models.usersByBranch
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import kotlinx.coroutines.flow.first
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

/** What the new worktree dialog needs to know about the repository (fork-only), read when it opens. */
class GetNewWorktreeContextUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getWorktreeCreationSettingsGitAction: IGetWorktreeCreationSettingsGitAction,
) {
    suspend operator fun invoke(): Either<NewWorktreeContext, AppError> = useCaseExecutor.execute { repositoryPath ->
        val list = repositoryDataRepository.worktrees.first { it !is DataState.Loading }.dataOrNull()
            ?: return@execute Either.Err(GenericError("The worktree list couldn't be read"))
        val mainWorktree = list.mainWorktree
            ?: return@execute Either.Err(GenericError("The worktree list has no main worktree"))

        val branches = repositoryDataRepository.localBranches.first { it !is DataState.Loading }.dataOrNull().orEmpty()
        val currentBranch = repositoryDataRepository.currentBranch.first { it !is DataState.Loading }.dataOrNull()

        Either.Ok(
            NewWorktreeContext(
                mainWorktree = mainWorktree,
                baseBranch = list.baseBranch,
                currentBranch = currentBranch?.takeIf { it.name != Constants.HEAD },
                currentCommit = list.worktrees.firstOrNull { it.worktree.isCurrent }?.worktree?.headSha,
                localBranches = branches.map { it.name }.toSet(),
                branchesInUse = list.usersByBranch().keys,
                settings = getWorktreeCreationSettingsGitAction(repositoryPath).okOrNull() ?: WorktreeCreationSettings(),
            )
        )
    }
}
