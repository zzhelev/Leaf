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
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

private const val TAG = "FetchLfsObjectsGitAction"

/**
 * Downloads LFS objects into the repository's LFS store with Leaf's built-in LFS client (fork-only): asks an SSH
 * remote where its LFS server is (`git-lfs-authenticate`), asks the server's batch API for all of them in one request,
 * then downloads each, checked against its size and SHA-256. git's LFS filter downloads a checkout's files with it, and
 * [FetchLfsObjectGitAction] one object.
 */
class FetchLfsObjectsGitAction @Inject constructor(
    private val getLfsObjectsGitAction: GetLfsObjectsGitAction,
    private val authenticateLfsServerWithSshGitAction: AuthenticateLfsServerWithSshGitAction,
    private val downloadLfsObjectGitAction: DownloadLfsObjectGitAction,
) {
    /**
     * Downloads [objects] from [lfsServer], one after another, and returns those that it couldn't, by oid, with why.
     * The user's closing of the credentials dialog fails them all ([LfsError.CredentialsDenied]). [onProgress] gets how
     * many objects are done and the bytes received in all.
     */
    suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        objects: List<LfsObjectBatch>,
        onProgress: (done: Int, receivedBytes: Long) -> Unit = { _, _ -> },
    ): Map<String, LfsError> {
        val failures = linkedMapOf<String, LfsError>()
        val done = mutableSetOf<String>()

        fun failTheRest(error: LfsError) {
            for (requested in objects) {
                if (requested.oid !in done && requested.oid !in failures) {
                    failures[requested.oid] = error
                }
            }
        }

        try {
            download(repository, lfsServer, objects, failures, done, onProgress)
        } catch (e: CancellationException) {
            // The credentials dialog throws one when it's closed; a cancelled download goes on cancelling
            currentCoroutineContext().ensureActive()
            failTheRest(LfsError.CredentialsDenied)
        } catch (e: Exception) {
            // ssh's failures (LfsException), with its messages, and the connection's, such as an unknown host
            printError(TAG, "Downloading ${objects.size} LFS objects failed", e)
            failTheRest(LfsError.RequestFailed(e.message ?: e.javaClass.simpleName))
        }

        return failures
    }

    private suspend fun download(
        repository: Repository,
        lfsServer: LfsServer,
        objects: List<LfsObjectBatch>,
        failures: MutableMap<String, LfsError>,
        done: MutableSet<String>,
        onProgress: (done: Int, receivedBytes: Long) -> Unit,
    ) {
        if (objects.isEmpty()) {
            return
        }

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

        val answered = when (
            val batch = getLfsObjectsGitAction(
                repository,
                server,
                operationType = OperationType.DOWNLOAD,
                lfsObjectBatches = objects,
                branch = repository.fullBranch,
                headers = headers,
            )
        ) {
            is Either.Err -> {
                objects.forEach { failures[it.oid] = batch.error }
                return
            }
            is Either.Ok -> batch.value.objects
        }

        var received = 0L

        for (requested in objects) {
            // A server that answers one object with another id, for a single object, is taken at its word
            val lfsObject = answered.firstOrNull { it.oid == requested.oid }
                ?: answered.singleOrNull()?.takeIf { objects.size == 1 }
            val refusal = lfsObject?.error
            val receivedBefore = received

            val result = when {
                refusal != null -> Either.Err(LfsError.ObjectRefused(refusal.code, refusal.message))
                lfsObject?.actions?.download == null -> Either.Err(LfsError.NoDownloadOffered)
                else -> downloadLfsObjectGitAction(
                    repository,
                    server,
                    lfsObject,
                    LongObjectId.fromString(requested.oid),
                ) { bytes -> onProgress(done.size, receivedBefore + bytes) }
            }

            when (result) {
                is Either.Err -> failures[requested.oid] = result.error
                is Either.Ok -> received += requested.size
            }

            done += requested.oid
            onProgress(done.size, received)
        }
    }
}
