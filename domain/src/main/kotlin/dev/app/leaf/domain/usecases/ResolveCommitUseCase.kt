// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IResolveCommitGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

/** The commit that a typed revision names, for the list that picks a comparison's side (fork-only). */
class ResolveCommitUseCase @Inject constructor(
    private val resolveCommitGitAction: IResolveCommitGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(revision: String) = useCaseExecutor.execute<Commit?> { repositoryPath ->
        resolveCommitGitAction(repositoryPath, revision)
    }
}
