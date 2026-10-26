// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsServer
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

/**
 * Downloads one LFS object into the repository's LFS store with Leaf's built-in LFS client ([FetchLfsObjectsGitAction]).
 * JGit's checkouts download with it, file by file, and so does a conflict's choice card.
 */
class FetchLfsObjectGitAction @Inject constructor(
    getLfsObjectsGitAction: GetLfsObjectsGitAction,
    authenticateLfsServerWithSshGitAction: AuthenticateLfsServerWithSshGitAction,
    downloadLfsObjectGitAction: DownloadLfsObjectGitAction,
) {
    private val fetchLfsObjectsGitAction = FetchLfsObjectsGitAction(
        getLfsObjectsGitAction,
        authenticateLfsServerWithSshGitAction,
        downloadLfsObjectGitAction,
    )

    /**
     * Downloads [oid], of [size] bytes, from [lfsServer]. [onProgress] gets the bytes received so far. A failure is
     * returned, the user's closing of the credentials dialog too ([LfsError.CredentialsDenied]).
     */
    suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        oid: AnyLongObjectId,
        size: Long,
        onProgress: (Long) -> Unit = {},
    ): Either<Unit, LfsError> {
        val failures = fetchLfsObjectsGitAction(
            repository,
            lfsServer,
            listOf(LfsObjectBatch(oid.name(), size)),
        ) { _, receivedBytes -> onProgress(receivedBytes) }

        return failures[oid.name()]?.let { Either.Err(it) } ?: Either.Ok(Unit)
    }
}
