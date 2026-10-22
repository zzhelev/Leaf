// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IDeleteBranchesGitAction {
    /**
     * Deletes the local [branches], each one only if it still points to the commit it was read at. Always with force:
     * the user chose each branch in a dialog that said what it loses. One that stays, because a worktree uses it, it's
     * gone or it moved, never stops the others: see [dev.app.leaf.domain.branches.BranchKept]. The result is an error
     * only when the repository couldn't be read.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        branches: List<BranchToDelete>,
    ): Either<BranchesDeletion, GitError>
}
