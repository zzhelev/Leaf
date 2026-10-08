// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ICheckoutRemoteBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class CheckoutRemoteBranchUseCase @Inject constructor(
    private val checkoutRemoteBranchGitAction: ICheckoutRemoteBranchGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(remoteBranch: Branch, fastForward: Boolean) {
        // A branch checkout, as it often checks out an existing local branch
        useCaseExecutor.executeLaunch(
            taskType = TaskType.CheckoutBranch,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            checkoutRemoteBranchGitAction(repositoryPath, remoteBranch, fastForward)
        }
    }
}
