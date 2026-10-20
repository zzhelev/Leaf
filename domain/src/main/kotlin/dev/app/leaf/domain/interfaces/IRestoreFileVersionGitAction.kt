// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IRestoreFileVersionGitAction {
    /**
     * Writes [filePath] as it was in [commit] into the working tree, as `git restore --source=<commit> --worktree`
     * does: the index is left alone, so nothing is staged. The file is looked at again first, and refused (a
     * [dev.app.leaf.domain.errors.RestoreFileError]) where git would delete or overwrite something that isn't the file.
     * When git fails, the working file is put back as it was.
     */
    suspend operator fun invoke(repositoryPath: String, commit: String, filePath: String): Either<Unit, GitError>
}
