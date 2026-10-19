// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.ignore.IgnoreContext
import dev.app.leaf.domain.interfaces.IGetIgnoreContextGitAction
import javax.inject.Inject

/** Reads what the ignore dialog shows when it opens (fork-only). */
class GetIgnoreContextUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getIgnoreContextGitAction: IGetIgnoreContextGitAction,
) {
    suspend operator fun invoke(): Either<IgnoreContext, AppError> = useCaseExecutor.execute { repositoryPath ->
        getIgnoreContextGitAction(repositoryPath)
    }
}
