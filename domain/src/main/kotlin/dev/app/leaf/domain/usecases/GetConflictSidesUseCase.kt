// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetConflictSidesGitAction
import dev.app.leaf.domain.models.ConflictSides
import javax.inject.Inject

/** What ours and theirs are in the operation that stopped at conflicts, or null when they can't be read. */
class GetConflictSidesUseCase @Inject constructor(
    private val getConflictSidesGitAction: IGetConflictSidesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): ConflictSides? = useCaseExecutor.execute { repositoryPath ->
        getConflictSidesGitAction(repositoryPath)
    }.okOrNull()
}
