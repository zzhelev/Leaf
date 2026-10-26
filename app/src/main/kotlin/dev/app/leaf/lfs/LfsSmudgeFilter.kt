package dev.app.leaf.lfs

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.lfs.FetchLfsObjectGitAction
import dev.app.leaf.data.git.lfs.LfsSmudgeSettings
import dev.app.leaf.data.git.lfs.MAX_LFS_POINTER_SIZE
import dev.app.leaf.data.git.lfs.lfsStore
import dev.app.leaf.data.git.lfs.parseLfsPointer
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.attributes.FilterCommand
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lfs.errors.LfsException
import org.eclipse.jgit.lib.Repository
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.file.Files

private const val TAG = "LfsSmudgeFilter"

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
    private val fetchLfsObjectGitAction: FetchLfsObjectGitAction,
    private val loginShellEnvironment: LoginShellEnvironment,
) : FilterCommand(
    if (input.markSupported()) input else BufferedInputStream(input),
    output,
) {
    init {
        // As git-lfs does, anything but a pointer goes out as it came in, byte for byte, an empty file and a pointer
        // that a merge's markers broke included (fork-only)
        val source = this.`in`
        val head = source.readNBytes(MAX_LFS_POINTER_SIZE + 1)
        val pointer = parseLfsPointer(head, allowExtensions = false)

        this.`in` = if (pointer == null) {
            SequenceInputStream(ByteArrayInputStream(head), source)
        } else {
            source.close()
            objectOrPointer(repository, pointer, head)
        }
    }

    /**
     * The object that [pointer] points to, from the store, downloaded first when the store lacks it. Or [pointerBytes]
     * as they are, where git-lfs would write the pointer: `GIT_LFS_SKIP_SMUDGE`, and with `lfs.skipdownloaderrors` a
     * download that fails (fork-only).
     */
    private fun objectOrPointer(repository: Repository, pointer: LfsPointer, pointerBytes: ByteArray): InputStream {
        val environment = System.getenv() + loginShellEnvironment.variablesBlocking()
        val settings = LfsSmudgeSettings(repository.config, environment)

        if (settings.skipsSmudge) {
            return ByteArrayInputStream(pointerBytes)
        }

        val store = repository.lfsStore()
        // Like git-lfs, a file of another size isn't the object: a download that broke off left it, before downloads
        // were checked
        val media = store.find(pointer.oid, pointer.size) ?: try {
            downloadLfsResource(repository, pointer)
            store.find(pointer.oid, pointer.size)
                ?: throw LfsException("LFS object ${pointer.oid.name()} isn't in the store after its download")
        } catch (e: LfsException) {
            if (settings.skipsDownloadErrors) {
                printError(TAG, "Writing the pointer, as lfs.skipdownloaderrors says: ${e.message}")
                return ByteArrayInputStream(pointerBytes)
            }

            throw e
        }

        return Files.newInputStream(media)
    }

    private fun downloadLfsResource(
        repository: Repository,
        lfsPointer: LfsPointer,
    ) = runBlocking {
        val lfsServer = getLfsUrlGitAction(repository, null)
            ?: throw LfsException("No LFS server was found for the repository")

        // The choice cards of a conflict download with the same code (fork-only). Its failure used to be dropped, and
        // the checkout then read whatever the download left
        val download = fetchLfsObjectGitAction(repository, lfsServer, lfsPointer.oid, lfsPointer.size)

        if (download is Either.Err) {
            throw LfsException("Downloading LFS object ${lfsPointer.oid.name()} failed with error: ${download.error}")
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