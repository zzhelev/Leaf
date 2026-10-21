// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.commitmessage.CommitTemplate
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetCommitTemplateGitAction
import javax.inject.Inject

/** git's commit template for the tab's worktree, which an empty commit field offers to start from, or null (fork-only). */
class GetCommitTemplateUseCase @Inject constructor(
    private val getCommitTemplateGitAction: IGetCommitTemplateGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): CommitTemplate? = useCaseExecutor.execute { repositoryPath ->
        getCommitTemplateGitAction(repositoryPath)
    }.okOrNull()
}
