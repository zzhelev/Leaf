package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.network.isSslVerify
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IDownloadLfsObjectGitAction
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.repositories.LfsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lfs.lib.Constants
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Repository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject

class DownloadLfsObjectGitAction @Inject constructor(
    private val lfsRepository: LfsRepository,
    private val provideLfsCredentialsGitAction: ProvideLfsCredentialsGitAction,
) : IDownloadLfsObjectGitAction {
    /**
     * Downloads [lfsObject] to a temporary file next to its place in `lfs/objects`, and moves it there once its size
     * and SHA-256 match [oid] (fork-only). Anything in that place counts as the object: a download that broke off used
     * to stay there, truncated, and the next one appended to it.
     */
    override suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        lfsObject: LfsObject,
        oid: AnyLongObjectId,
    ): Either<Unit, LfsError> {
        val downloadUrl = lfsObject.actions?.download?.href ?: return Either.Ok(Unit)
        val headers = lfsObject.actions?.download?.header.orEmpty()
        val sslVerify = repository.config.isSslVerify(downloadUrl)

        val mediaFile = Lfs(repository).getMediaFile(oid)
        // In the same folder, so that the move is a rename
        val download = mediaFile.resolveSibling("${mediaFile.fileName}.${UUID.randomUUID()}.tmp")

        try {
            val result = if (headers.containsKey(NetworkConstants.AUTH_HEADER)) {
                lfsRepository.downloadObject(
                    downloadUrl = downloadUrl,
                    outPath = download,
                    headers = headers,
                    username = null,
                    password = null,
                    sslVerify = sslVerify,
                )
            } else {
                provideLfsCredentialsGitAction(repository, lfsServer) { user, password ->
                    lfsRepository.downloadObject(
                        downloadUrl = downloadUrl,
                        outPath = download,
                        headers = headers,
                        username = user,
                        password = password,
                        sslVerify = sslVerify,
                    )
                }
            }

            if (result is Either.Err) {
                return result
            }

            return withContext(Dispatchers.IO) {
                when (val corruption = corruption(download, oid, lfsObject.size)) {
                    null -> {
                        Files.move(download, mediaFile, StandardCopyOption.ATOMIC_MOVE)
                        Either.Ok(Unit)
                    }

                    else -> Either.Err(corruption)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(download)
            }
        }
    }

    /** Why [file] isn't the LFS object [oid] of [size] bytes, or null when it is. */
    private fun corruption(file: Path, oid: AnyLongObjectId, size: Long): LfsError.CorruptDownload? {
        val receivedSize = Files.size(file)

        if (receivedSize != size) {
            return LfsError.CorruptDownload(oid.name(), expectedSize = size, receivedSize)
        }

        val digest = Constants.newMessageDigest()

        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }

        val receivedOid = LongObjectId.fromRaw(digest.digest())

        return if (receivedOid == oid) {
            null
        } else {
            LfsError.CorruptDownload(oid.name(), expectedSize = size, receivedSize, receivedOid.name())
        }
    }
}
