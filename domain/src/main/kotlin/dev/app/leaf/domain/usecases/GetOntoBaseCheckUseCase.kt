// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetOntoBaseCheckGitAction
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import javax.inject.Inject

/** What putting a squashed branch on the latest base would do, when its dialog opens (fork-only). */
class GetOntoBaseCheckUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getOntoBaseCheckGitAction: IGetOntoBaseCheckGitAction,
) {
    suspend operator fun invoke(line: BranchLine): Either<OntoBaseCheck?, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            getOntoBaseCheckGitAction(repositoryPath, line)
        }
}
