// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetWorkingTreeComparisonGitAction
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import javax.inject.Inject

/** A commit or a branch compared with the working tree (fork-only, `docs/fork/compare.md`, 7.2). */
class GetWorkingTreeComparisonUseCase @Inject constructor(
    private val getWorkingTreeComparisonGitAction: IGetWorkingTreeComparisonGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(a: String, mode: ComparisonMode) = useCaseExecutor.execute<Comparison> { repositoryPath ->
        getWorkingTreeComparisonGitAction(repositoryPath, a, mode)
    }
}
