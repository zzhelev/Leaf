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
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.isPreviewImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

/** The remote that git-lfs asks when the current branch has none. */
private const val DEFAULT_REMOTE = "origin"

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

        val server = raiseErrorIfNull(lfsServer(repository)) { LfsObjectDownloadError(LfsError.NoServer) }

        fetchLfsObjectGitAction(repository, server, LongObjectId.fromString(obj.oid), obj.size, onProgress)
            .mapErr { LfsObjectDownloadError(it) }
            .bind()

        withContext(Dispatchers.IO) { lfsVersion(repository, obj, image) }
    }

    /**
     * The LFS server, as the checkout's built-in client finds it: `lfs.url` in `.lfsconfig`, or the current branch's
     * remote, or the only remote. Else, as git-lfs does, `origin`: a rebase's HEAD is detached, so it has no branch.
     */
    private suspend fun lfsServer(repository: Repository): LfsServer? {
        getLfsUrlGitAction(repository, null)?.let { return it }

        val remotes = repository.remoteNames
        val remote = DEFAULT_REMOTE.takeIf { it in remotes } ?: remotes.singleOrNull() ?: return null

        return getLfsUrlGitAction(repository, Constants.R_REMOTES + remote)
    }
}
