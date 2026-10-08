// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetRemoteBranchCheckoutGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RemoteBranchCheckout
import javax.inject.Inject

class GetRemoteBranchCheckoutUseCase @Inject constructor(
    private val getRemoteBranchCheckoutGitAction: IGetRemoteBranchCheckoutGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(remoteBranch: Branch): Either<RemoteBranchCheckout, AppError> {
        return useCaseExecutor.execute { repositoryPath ->
            getRemoteBranchCheckoutGitAction(repositoryPath, remoteBranch)
        }
    }
}
