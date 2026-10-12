// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.TextMerge

interface IWriteConflictResultGitAction {
    /**
     * Writes [merge]'s result into [file]'s working file, with markers for the conflicts still open, and returns the
     * id that git gives the bytes written, for the next write's check. Nothing is staged.
     *
     * Refuses when the index's versions of the file changed since [file] was loaded, and when the working file did,
     * unless [overwriteChanges]. [file]'s content must be text.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        file: ConflictedFile,
        merge: TextMerge,
        overwriteChanges: Boolean,
    ): Either<String, GitError>
}
