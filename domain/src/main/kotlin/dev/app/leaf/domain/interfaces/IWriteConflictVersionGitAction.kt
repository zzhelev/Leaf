// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IWriteConflictVersionGitAction {
    /**
     * Writes a version of a conflicted file, the blob [objectId], into [folder] under [fileName]'s name, so that an app
     * can open it. A Git LFS pointer whose object was downloaded is written as the object. Returns the file's path.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        objectId: String,
        fileName: String,
        folder: String,
    ): Either<String, GitError>
}
