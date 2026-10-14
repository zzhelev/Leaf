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
     *
     * [keepWorkingFile]: where [side] has a file, the working file is staged as it is, when it's there, as `git
     * mergetool` keeps the modified version of a file that the other side deleted. Edits made to it since the merge
     * stay.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        filePaths: List<String>,
        side: ConflictSide,
        keepWorkingFile: Boolean = false,
    ): Either<Unit, GitError>
}
