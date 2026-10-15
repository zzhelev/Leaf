// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.extensions.isHttpOrHttps
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

private const val TAG = "FetchLfsObjectGitAction"

/**
 * Downloads one LFS object into the repository's LFS store with Leaf's built-in LFS client: asks an SSH remote where
 * its LFS server is (`git-lfs-authenticate`), asks the server's batch API for the object, then downloads it, checked
 * against its size and SHA-256. The checkout's smudge filter downloads with it, and so does a conflict's choice card.
 */
class FetchLfsObjectGitAction @Inject constructor(
    private val getLfsObjectsGitAction: GetLfsObjectsGitAction,
    private val authenticateLfsServerWithSshGitAction: AuthenticateLfsServerWithSshGitAction,
    private val downloadLfsObjectGitAction: DownloadLfsObjectGitAction,
) {
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
    ): Either<Unit, LfsError> = try {
        download(repository, lfsServer, oid, size, onProgress)
    } catch (e: CancellationException) {
        // The credentials dialog throws one when it's closed; a cancelled download goes on cancelling
        currentCoroutineContext().ensureActive()
        Either.Err(LfsError.CredentialsDenied)
    } catch (e: Exception) {
        // ssh's failures (LfsException), with its messages, and the connection's, such as an unknown host
        printError(TAG, "Downloading the LFS object ${oid.name()} failed", e)
        Either.Err(LfsError.RequestFailed(e.message ?: e.javaClass.simpleName))
    }

    private suspend fun download(
        repository: Repository,
        lfsServer: LfsServer,
        oid: AnyLongObjectId,
        size: Long,
        onProgress: (Long) -> Unit,
    ): Either<Unit, LfsError> {
        val batch = listOf(LfsObjectBatch(oid.name(), size))
        val (server, headers) = if (lfsServer.url.isHttpOrHttps()) {
            lfsServer to emptyMap()
        } else {
            val answer = authenticateLfsServerWithSshGitAction(
                repository = repository,
                lfsServerUrl = lfsServer.url,
                operationType = OperationType.DOWNLOAD,
            )

            LfsServer(answer.href, lfsServer.remoteUrl) to answer.header
        }

        val objects = getLfsObjectsGitAction(
            repository,
            server,
            operationType = OperationType.DOWNLOAD,
            lfsObjectBatches = batch,
            branch = repository.fullBranch,
            headers = headers,
        )

        val answered = when (objects) {
            is Either.Err -> return objects
            is Either.Ok -> objects.value.objects
        }
        // There should be only one LFS object
        val lfsObject = answered.firstOrNull { it.oid == oid.name() } ?: answered.firstOrNull()

        val refusal = lfsObject?.error

        return when {
            refusal != null -> Either.Err(LfsError.ObjectRefused(refusal.code, refusal.message))
            lfsObject?.actions?.download == null -> Either.Err(LfsError.NoDownloadOffered)
            else -> downloadLfsObjectGitAction(repository, server, lfsObject, oid, onProgress)
        }
    }
}
