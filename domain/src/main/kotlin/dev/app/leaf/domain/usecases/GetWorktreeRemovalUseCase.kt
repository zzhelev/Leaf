// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetBranchMergedGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRemoval
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val LOCAL_BRANCH_PREFIX = "refs/heads/"

/**
 * Reads what removing a worktree would lose (fork-only), for its dialog: its changes, when it last changed, and whether
 * its branch is merged into the base. The worktree list may be stale, so these are read again.
 */
class GetWorktreeRemovalUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
    private val getWorktreeActivityGitAction: IGetWorktreeActivityGitAction,
    private val getBranchMergedGitAction: IGetBranchMergedGitAction,
) {
    suspend operator fun invoke(worktree: Worktree): Either<WorktreeRemoval, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            val list = repositoryDataRepository.worktrees.first { it !is DataState.Loading }.dataOrNull()
                ?: return@execute Either.Err(GenericError("The worktree list couldn't be read"))

            val worktrees = list.worktrees.map { it.worktree }
            val mainWorktree = list.mainWorktree
                ?: return@execute Either.Err(GenericError("The worktree list has no main worktree"))

            // The list may be newer than the row that was clicked, for example locked since
            val current = worktrees.firstOrNull { it.path == worktree.path } ?: worktree
            val isMissing = current.prunable != null
            val base = list.baseBranch
            val branch = current.branch?.takeIf { it.startsWith(LOCAL_BRANCH_PREFIX) && !isBaseBranch(it, base) }

            Either.Ok(
                WorktreeRemoval(
                    worktree = current,
                    mainWorktree = mainWorktree,
                    status = if (isMissing) null else getWorktreeStatusGitAction(current.path).okOrNull(),
                    lastChange = if (isMissing) null else getWorktreeActivityGitAction(current.path).okOrNull(),
                    branch = branch?.removePrefix(LOCAL_BRANCH_PREFIX),
                    baseBranch = base,
                    branchMerged = if (branch != null && base != null) {
                        getBranchMergedGitAction(repositoryPath, base, branch).okOrNull()
                    } else {
                        null
                    },
                )
            )
        }

    /**
     * Whether [branch] is the base branch [base], or the local branch of a remote base (`main` for `origin/main`), which
     * removing a worktree never offers to delete.
     */
    private fun isBaseBranch(branch: String, base: String?): Boolean {
        if (base == null) return false

        val name = branch.removePrefix(LOCAL_BRANCH_PREFIX)

        return branch == base || (base.startsWith("refs/remotes/") && base.endsWith("/$name"))
    }
}
