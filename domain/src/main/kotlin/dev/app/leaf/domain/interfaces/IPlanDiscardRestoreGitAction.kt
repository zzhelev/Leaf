// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.discards.DiscardRestorePlan
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IPlanDiscardRestoreGitAction {
    /** How Undo would bring back each file of the discard [recordId], without changing anything. */
    suspend operator fun invoke(repositoryPath: String, recordId: String): Either<DiscardRestorePlan, GitError>
}
