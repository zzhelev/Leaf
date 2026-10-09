package dev.app.leaf.lfs

import dev.app.leaf.data.git.lfs.AuthenticateLfsServerWithSshGitAction
import dev.app.leaf.data.git.lfs.DownloadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.extensions.isHttpOrHttps
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.attributes.FilterCommand
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lfs.errors.LfsException
import org.eclipse.jgit.lib.Repository
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

private const val MAX_COPY_BYTES = 1024 * 1024 * 256

@AssistedFactory
interface LfsSmudgeFilterFactory {
    fun create(repository: Repository, input: InputStream, output: OutputStream): LfsSmudgeFilter
}

class LfsSmudgeFilter @AssistedInject constructor(
    @Assisted repository: Repository,
    @Assisted input: InputStream,
    @Assisted output: OutputStream,
    private val getLfsUrlGitAction: GetLfsUrlGitAction,
    private val getLfsObjectsGitAction: GetLfsObjectsGitAction,
    private val authenticateLfsServerWithSshGitAction: AuthenticateLfsServerWithSshGitAction,
    private val downloadLfsObjectGitAction: DownloadLfsObjectGitAction,
) : FilterCommand(
    if (input.markSupported()) input else BufferedInputStream(input),
    output,
) {
    init {
        var from: InputStream? = input
        try {
            val res = LfsPointer.parseLfsPointer(from)
            if (res != null) {
                val oid = res.oid
                val lfs = Lfs(repository)
                val mediaFile = lfs.getMediaFile(oid)
                // Like git-lfs, a file of another size isn't the object: a download that broke off left it, before
                // downloads were checked (fork-only)
                if (!Files.exists(mediaFile) || Files.size(mediaFile) != res.size) {
                    downloadLfsResource(repository, res)
                }
                this.`in` = Files.newInputStream(mediaFile)
            } else {
                // Not swapped; stream was reset, don't close!
                from = null
            }
        } finally {
            from?.close()
        }
    }

    private fun downloadLfsResource(
        repository: Repository,
        lfsPointer: LfsPointer,
    ) = runBlocking {

        val lfsServer = getLfsUrlGitAction(repository, null) ?: throw Exception("LFS Url not found")
        val isHttpUrl = lfsServer.url.isHttpOrHttps()

        val lfsObjectBatches = listOf(LfsObjectBatch(lfsPointer.oid.name(), lfsPointer.size))

        val lfsObjects: Either<LfsObjects, LfsError>
        val finalServer: LfsServer

        if (isHttpUrl) {
            finalServer = lfsServer
            lfsObjects = getLfsObjectsGitAction(
                repository,
                lfsServer,
                operationType = OperationType.DOWNLOAD,
                lfsObjectBatches = lfsObjectBatches,
                branch = repository.fullBranch,
                headers = emptyMap(),
            )
        } else {
            val lfsServerInfo = authenticateLfsServerWithSshGitAction(
                repository = repository,
                lfsServerUrl = lfsServer.url,
                operationType = OperationType.DOWNLOAD
            )

            finalServer = LfsServer(lfsServerInfo.href, lfsServer.remoteUrl)

            lfsObjects = getLfsObjectsGitAction(
                repository,
                finalServer,
                operationType = OperationType.DOWNLOAD,
                lfsObjectBatches = lfsObjectBatches,
                branch = repository.fullBranch,
                headers = lfsServerInfo.header,
            )
        }

        when (lfsObjects) {
            is Either.Err -> throw LfsException("Gettings LFS objects failed with error: ${lfsObjects.error}")
            is Either.Ok -> {

                val lfsObject = lfsObjects.value.objects.firstOrNull() // There should be only one LFS object

                if (lfsObject != null) {
                    val download = downloadLfsObjectGitAction(
                        repository = repository,
                        lfsServer = finalServer,
                        lfsObject = lfsObject,
                        lfsPointer.oid,
                    )

                    // Its failure used to be dropped, and the checkout then read whatever the download left (fork-only)
                    if (download is Either.Err) {
                        throw LfsException(
                            "Downloading LFS object ${lfsPointer.oid.name()} failed with error: ${download.error}"
                        )
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun run(): Int {
        try {
            var totalRead = 0
            var length = 0
            if (`in` != null) {
                val buf = ByteArray(8192)
                while ((`in`.read(buf).also { length = it }) != -1) {
                    out.write(buf, 0, length)
                    totalRead += length

                    // when threshold reached, loop back to the caller.
                    // otherwise we could only support files up to 2GB (int
                    // return type) properly. we will be called again as long as
                    // we don't return -1 here.
                    if (totalRead >= MAX_COPY_BYTES) {
                        // leave streams open - we need them in the next call.
                        return totalRead
                    }
                }
            }

            if (totalRead == 0 && length == -1) {
                // we're totally done :) cleanup all streams
                `in`.close()
                out.close()
                return length
            }

            return totalRead
        } catch (e: IOException) {
            `in`.close() // clean up - we swapped this stream.
            out.close()
            throw e
        }
    }
}