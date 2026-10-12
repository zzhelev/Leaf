// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetBranchComparisonGitAction
import dev.app.leaf.domain.interfaces.IGetBranchMergedGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeMerge
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.worktrees.branchToMerge
import dev.app.leaf.domain.worktrees.mergeTarget
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val LOCAL_BRANCH_PREFIX = "refs/heads/"

/**
 * Reads what merging a worktree's branch into the base's local branch needs (fork-only), for its dialog: where the
 * merge runs and what's there, the commits it brings, and what the branch's worktree keeps out of it. The worktree list
 * may be stale, so these are read again.
 */
class GetWorktreeMergeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
    private val getWorktreeActivityGitAction: IGetWorktreeActivityGitAction,
    private val getBranchComparisonGitAction: IGetBranchComparisonGitAction,
    private val getBranchMergedGitAction: IGetBranchMergedGitAction,
    private val runGitCommandGitAction: IRunGitCommandGitAction,
) {
    suspend operator fun invoke(worktree: Worktree): Either<WorktreeMerge, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            val list = repositoryDataRepository.worktrees.first { it !is DataState.Loading }.dataOrNull()
                ?: return@execute Either.Err(GenericError("The worktree list couldn't be read"))
            val localBranches = repositoryDataRepository.localBranches.first { it !is DataState.Loading }.dataOrNull()
                ?: return@execute Either.Err(GenericError("The branches couldn't be read"))

            val worktrees = list.worktrees.map { it.worktree }
            // The list may be newer than the row that was clicked, for example on another branch since
            val current = worktrees.firstOrNull { it.path == worktree.path } ?: worktree
            val target = mergeTarget(list.baseBranch, localBranches.map { it.name })
                ?: return@execute Either.Err(GenericError("The base branch has no local branch to merge into"))
            val branch = current.branchToMerge(list.baseBranch, target)
                ?: return@execute Either.Err(GenericError("${current.path} has no branch to merge"))
            val tabWorktree = worktrees.firstOrNull { it.isCurrent }
                ?: return@execute Either.Err(GenericError("The worktree list has no worktree for this tab"))

            val targetWorktree = worktrees.firstOrNull { it.branch == target }
            val targetUser = worktrees.firstOrNull {
                val use = it.useOf(target)
                use == WorktreeBranchUse.Rebasing || use == WorktreeBranchUse.Bisecting
            }
            val comparison = getBranchComparisonGitAction(repositoryPath, target, branch).bind()

            Either.Ok(
                WorktreeMerge(
                    worktree = current,
                    branch = branch,
                    branchArgument = branchArgument(tabWorktree.path, branch),
                    target = target,
                    targetWorktree = targetWorktree,
                    targetStatus = targetWorktree?.takeIf { it.prunable == null }?.let { statusOf(it) },
                    targetUser = targetUser,
                    tabWorktree = tabWorktree,
                    tabStatus = if (targetWorktree == null) statusOf(tabWorktree) else null,
                    branchStatus = current.takeIf { it.prunable == null }?.let { statusOf(it) },
                    lastChange = if (current.prunable == null) {
                        getWorktreeActivityGitAction(current.path).okOrNull()
                    } else {
                        null
                    },
                    commits = comparison.commits,
                    commitCount = comparison.ahead,
                    branchMerged = getBranchMergedGitAction(repositoryPath, target, branch).okOrNull(),
                )
            )
        }

    private suspend fun statusOf(worktree: Worktree) = getWorktreeStatusGitAction(worktree.path).okOrNull()

    /**
     * How `git merge` names [branch]: its short name, which reads better and gives git's usual message ("Merge branch
     * 'agent1'"), unless git takes it for something else, such as a tag of the same name. Then its full name.
     */
    private suspend fun branchArgument(folder: String, branch: String): String {
        val shortName = branch.removePrefix(LOCAL_BRANCH_PREFIX)
        val resolved = runGitCommandGitAction(folder, listOf("rev-parse", "--symbolic-full-name", shortName))
            .okOrNull()
            ?.trim()

        return if (resolved == branch) shortName else branch
    }
}
