// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetMergeToolGitAction
import javax.inject.Inject

/** The merge tool that "Open in external merge tool" runs, or null when none is set or git can't tell. */
class GetMergeToolUseCase @Inject constructor(
    private val getMergeToolGitAction: IGetMergeToolGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): String? = useCaseExecutor.execute { repositoryPath ->
        getMergeToolGitAction(repositoryPath)
    }.okOrNull()
}
