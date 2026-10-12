// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictSide

interface IResolveConflictGitAction {
    /**
     * Resolves the conflicts of [filePaths] (relative to the working tree) by taking [side]'s version of each, and
     * stages the result: the file as that side has it, or its deletion when that side deleted it.
     */
    suspend operator fun invoke(repositoryPath: String, filePaths: List<String>, side: ConflictSide): Either<Unit, GitError>
}
