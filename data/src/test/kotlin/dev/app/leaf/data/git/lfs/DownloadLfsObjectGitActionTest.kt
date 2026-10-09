// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.network.createHttpClient
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.Actions
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.lfs.RemoteObjectAccessInfo
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.Constants
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The built-in LFS client's download ([DownloadLfsObjectGitAction]) from a [FakeLfsServer]: an object reaches
 * `lfs/objects` only once its size and SHA-256 are the pointer's.
 */
class DownloadLfsObjectGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var server: FakeLfsServer
    private lateinit var git: Git

    private val content = "LFS content\n".repeat(1000).toByteArray()
    private val oid = LongObjectId.fromRaw(Constants.newMessageDigest().digest(content))

    private val mediaFile get() = Lfs(git.repository).getMediaFile(oid).toFile()
    private val stored get() = File(tempDir, "server/${oid.name()}")

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        server = FakeLfsServer(File(tempDir, "server"))
        git = Git.init().setDirectory(File(tempDir, "work")).call()
        stored.writeBytes(content)
    }

    @AfterEach
    fun tearDown() {
        git.close()
        server.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a download is checked, then moved into place`(): Unit = runBlocking {
        assertEquals(Either.Ok(Unit), download())

        assertEquals(content.toList(), mediaFile.readBytes().toList())
        assertEquals(listOf(oid.name()), filesNextToObject())
    }

    @Test
    fun `a download that breaks off leaves nothing, and the next one starts over`(): Unit = runBlocking {
        server.dropDownloadsAfter = content.size / 2

        // Ktor's CIO engine ends the body without an error when the connection drops
        assertEquals(
            Either.Err(LfsError.CorruptDownload(oid.name(), content.size.toLong(), receivedSize = content.size / 2L)),
            download(),
        )
        assertFalse(mediaFile.exists())
        assertEquals(emptyList<String>(), filesNextToObject())

        server.dropDownloadsAfter = null

        assertEquals(Either.Ok(Unit), download())
        assertEquals(content.toList(), mediaFile.readBytes().toList())
    }

    @Test
    fun `a download of another size is refused`(): Unit = runBlocking {
        stored.writeBytes(content.copyOf(100))

        assertEquals(
            Either.Err(LfsError.CorruptDownload(oid.name(), expectedSize = content.size.toLong(), receivedSize = 100)),
            download(),
        )
        assertFalse(mediaFile.exists())
        assertEquals(emptyList<String>(), filesNextToObject())
    }

    @Test
    fun `a download of the right size that isn't the object is refused`(): Unit = runBlocking {
        val other = content.copyOf().apply { this[0] = 'X'.code.toByte() }
        stored.writeBytes(other)

        assertEquals(
            Either.Err(
                LfsError.CorruptDownload(
                    oid.name(),
                    expectedSize = content.size.toLong(),
                    receivedSize = content.size.toLong(),
                    receivedOid = LongObjectId.fromRaw(Constants.newMessageDigest().digest(other)).name(),
                )
            ),
            download(),
        )
        assertFalse(mediaFile.exists())
        assertEquals(emptyList<String>(), filesNextToObject())
    }

    @Test
    fun `a truncated object that an earlier download left is replaced`(): Unit = runBlocking {
        mediaFile.parentFile.mkdirs()
        mediaFile.writeBytes(content.copyOf(100))

        assertEquals(Either.Ok(Unit), download())

        assertEquals(content.toList(), mediaFile.readBytes().toList())
        assertEquals(listOf(oid.name()), filesNextToObject())
    }

    private suspend fun download(): Either<Unit, LfsError> {
        val action = DownloadLfsObjectGitAction(
            NetworkLfsRepository(LfsNetworkDataSource(createHttpClient())),
            ProvideLfsCredentialsGitAction(
                credentialsCacheRepository = CredentialsCacheRepository(),
                credentialsStateManager = CredentialsStateManager(),
                credentialHelpers = CredentialHelpers(
                    shellManager = ShellManager(),
                    gitCredentialsManagerProvider = mockk(relaxed = true),
                    loginShellEnvironment = LoginShellEnvironment { emptyMap() },
                    gitCli = testGitCli(),
                ),
            ),
        )
        val lfsObject = LfsObject(
            oid = oid.name(),
            size = content.size.toLong(),
            actions = Actions(download = RemoteObjectAccessInfo("${server.url}/objects/${oid.name()}")),
        )

        return action(git.repository, LfsServer(server.url, null), lfsObject, oid)
    }

    private fun filesNextToObject(): List<String> = mediaFile.parentFile.list().orEmpty().sorted()
}
