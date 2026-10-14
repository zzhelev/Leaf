// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.RenamedFile

interface IResolveRenamedFileGitAction {
    /**
     * Resolves [file], renamed differently on each side, by keeping the new names of the sides in [keep] (one, or
     * both): the old name and a new name not kept leave the index and the working tree, as `git rm` does. A name kept
     * is staged as its working file has it, unless that has conflict markers: it then becomes a conflict of its content,
     * the base from the old name, ours from HEAD and theirs from the commit applied.
     */
    suspend operator fun invoke(repositoryPath: String, file: RenamedFile, keep: Set<ConflictSide>): Either<Unit, GitError>
}
