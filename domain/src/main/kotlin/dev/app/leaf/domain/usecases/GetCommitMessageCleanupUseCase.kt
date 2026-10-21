// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetCommitMessageCleanupGitAction
import javax.inject.Inject

/** How git cleans up the tab's commit messages, or git's default when the config can't be read. */
class GetCommitMessageCleanupUseCase @Inject constructor(
    private val getCommitMessageCleanupGitAction: IGetCommitMessageCleanupGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): MessageCleanup = useCaseExecutor.execute { repositoryPath ->
        getCommitMessageCleanupGitAction(repositoryPath)
    }.okOrNull() ?: MessageCleanup.DEFAULT
}
