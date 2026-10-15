// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.VersionPreview

interface IDownloadLfsVersionGitAction {
    /**
     * Downloads [obj], the object of a Git LFS pointer at the conflicted [path], into the repository's LFS store with
     * Leaf's built-in LFS client, unless it's there already. Returns the version as its choice card then shows it.
     * [onProgress] gets the bytes received so far.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        path: String,
        obj: LfsObject,
        onProgress: (Long) -> Unit,
    ): Either<VersionPreview.Lfs, GitError>
}
