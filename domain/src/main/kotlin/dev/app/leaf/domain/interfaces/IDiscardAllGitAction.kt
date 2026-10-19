// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.StatusEntry

interface IDiscardAllGitAction {
    /**
     * Restores the unstaged changes of [restore] and deletes [newFiles], untracked files. The entries are what the
     * dialog counted, so only those that the status still lists as they were are touched: a file that was staged,
     * committed or conflicted since is left alone.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        restore: List<StatusEntry>,
        newFiles: List<StatusEntry>,
    ): Either<Unit, GitError>
}
