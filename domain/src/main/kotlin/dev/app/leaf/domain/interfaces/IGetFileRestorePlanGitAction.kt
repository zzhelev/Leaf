// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.restore.FileRestorePlan

interface IGetFileRestorePlanGitAction {
    /**
     * Looks at what restoring [filePath] from [commit] would replace, and whether it can be done. Writes nothing. A
     * bare repository, which has no working tree, is an error.
     */
    suspend operator fun invoke(repositoryPath: String, commit: String, filePath: String): Either<FileRestorePlan, GitError>
}
