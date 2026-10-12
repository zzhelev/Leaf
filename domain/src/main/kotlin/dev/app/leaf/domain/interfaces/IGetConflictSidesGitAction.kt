// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictSides

interface IGetConflictSidesGitAction {
    /** What ours and theirs are in the operation that the worktree of [repositoryPath] is in the middle of. */
    suspend operator fun invoke(repositoryPath: String): Either<ConflictSides, GitError>
}
