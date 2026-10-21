// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.commitmessage.appendTrailer
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IAddTrailerGitAction
import javax.inject.Inject

/** [message] with a trailer added where git puts it (fork-only), as the commit field's "Add co-author" does. */
class AddTrailerUseCase @Inject constructor(
    private val addTrailerGitAction: IAddTrailerGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(message: String, trailer: String): String = useCaseExecutor.execute { repositoryPath ->
        addTrailerGitAction(repositoryPath, message, trailer)
    }.okOrNull() ?: appendTrailer(message, trailer)
}
