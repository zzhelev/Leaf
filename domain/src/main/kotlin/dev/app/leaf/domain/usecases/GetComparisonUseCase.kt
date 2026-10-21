// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetComparisonGitAction
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import javax.inject.Inject

/** Two commits or branches compared (fork-only), for a comparison and for the review of a worktree's changes. */
class GetComparisonUseCase @Inject constructor(
    private val getComparisonGitAction: IGetComparisonGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(a: String, b: String, mode: ComparisonMode) =
        useCaseExecutor.execute<Comparison> { repositoryPath ->
            getComparisonGitAction(repositoryPath, a, b, mode)
        }
}
