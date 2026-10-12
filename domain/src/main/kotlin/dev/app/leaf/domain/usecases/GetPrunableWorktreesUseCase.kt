// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetPrunableWorktreesGitAction
import javax.inject.Inject

/** What `git worktree prune` would remove (fork-only), for its dialog, one line of git's per worktree. */
class GetPrunableWorktreesUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getPrunableWorktreesGitAction: IGetPrunableWorktreesGitAction,
) {
    suspend operator fun invoke(): Either<List<String>, AppError> = useCaseExecutor.execute { repositoryPath ->
        getPrunableWorktreesGitAction(repositoryPath)
    }
}
