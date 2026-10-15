// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.lfs.FetchLfsObjectGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsObjectDownloadError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.mapErr
import dev.app.leaf.domain.errors.raiseErrorIfNull
import dev.app.leaf.domain.interfaces.IDownloadLfsVersionGitAction
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.isPreviewImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lfs.lib.LongObjectId
import javax.inject.Inject

/**
 * Downloads the Git LFS object of one version of a conflicted file, from its choice card (`conflict-resolver.md`,
 * section 9), with Leaf's built-in LFS client, then reads the version again as the card shows it: an image, or a file
 * to open in the default app.
 */
class DownloadLfsVersionGitAction @Inject constructor(
    private val jgit: JGit,
    private val getLfsUrlGitAction: IGetLfsUrlGitAction,
    private val fetchLfsObjectGitAction: FetchLfsObjectGitAction,
) : IDownloadLfsVersionGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        path: String,
        obj: LfsObject,
        onProgress: (Long) -> Unit,
    ): Either<VersionPreview.Lfs, GitError> = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val image = isPreviewImage(path)
        val version = withContext(Dispatchers.IO) { lfsVersion(repository, obj, image) }

        // Another card, or a checkout, may have downloaded it meanwhile
        if (version.downloaded) {
            return@provide version
        }

        // The checkout's server, as git-lfs picks it. A rebase's HEAD is detached, so it has no branch, and git-lfs
        // then takes remote.lfsdefault, the only remote, or origin
        val server = raiseErrorIfNull(getLfsUrlGitAction(repository, null)) {
            LfsObjectDownloadError(LfsError.NoServer)
        }

        fetchLfsObjectGitAction(repository, server, LongObjectId.fromString(obj.oid), obj.size, onProgress)
            .mapErr { LfsObjectDownloadError(it) }
            .bind()

        withContext(Dispatchers.IO) { lfsVersion(repository, obj, image) }
    }
}
