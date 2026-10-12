// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictedFile

interface IGetConflictedFileGitAction {
    /**
     * Loads the conflicted file at [path] (relative to the working tree) for the resolver: its versions in the index,
     * merged when they're text, and a look at the working file. Fails with `ConflictError.NotConflicted` when it has no
     * conflicts.
     */
    suspend operator fun invoke(repositoryPath: String, path: String): Either<ConflictedFile, GitError>
}
