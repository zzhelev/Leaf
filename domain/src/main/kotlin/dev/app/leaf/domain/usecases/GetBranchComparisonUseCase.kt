// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetBranchComparisonGitAction
import dev.app.leaf.domain.models.BranchComparison
import javax.inject.Inject

/** What a worktree's branch would bring into the base (fork-only), for the review of its changes. */
class GetBranchComparisonUseCase @Inject constructor(
    private val getBranchComparisonGitAction: IGetBranchComparisonGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(base: String, branch: String) = useCaseExecutor.execute<BranchComparison> { repositoryPath ->
        getBranchComparisonGitAction(repositoryPath, base, branch)
    }
}
