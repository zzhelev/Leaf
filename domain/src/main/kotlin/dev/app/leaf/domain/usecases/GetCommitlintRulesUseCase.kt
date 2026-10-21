// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetCommitlintRulesGitAction
import javax.inject.Inject

/** The commitlint config of the tab's worktree, for the Conventional Commits suggestions, or null (fork-only). */
class GetCommitlintRulesUseCase @Inject constructor(
    private val getCommitlintRulesGitAction: IGetCommitlintRulesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): CommitlintRules? = useCaseExecutor.execute { repositoryPath ->
        getCommitlintRulesGitAction(repositoryPath)
    }.okOrNull()
}
