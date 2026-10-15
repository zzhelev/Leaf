// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.lfs.AuthenticateLfsServerWithSshGitAction
import dev.app.leaf.data.git.lfs.DownloadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.FetchLfsObjectGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.ProvideLfsCredentialsGitAction
import dev.app.leaf.data.git.testAppSettings
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.network.createHttpClient
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsRequest.LfsCredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsObjectDownloadError
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.PreviewImage
import dev.app.leaf.domain.models.VersionPreview
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.Constants
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/**
 * Downloading a Git LFS object from a conflict's choice card ([DownloadLfsVersionGitAction], `conflict-resolver.md`,
 * section 9) with the built-in LFS client, from a [FakeLfsServer].
 */
class DownloadLfsVersionGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private lateinit var server: FakeLfsServer

    private val credentialsStateManager = CredentialsStateManager()

    /** What the login shell adds: git ignores the developer's own config, credential helpers included. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").apply { createNewFile() }.absolutePath,
            "HOME" to tempDir.absolutePath,
        )
    }

    /** What the stand-in for the app's `GetLfsUrlGitAction` was asked: the remote, null for the current branch's. */
    private val serverRequests = mutableListOf<String?>()

    /** The LFS server that the repository has for each remote, null for the current branch's. */
    private var servers: (String?) -> LfsServer? = { LfsServer(server.url, remoteUrl = null) }

    private val contents = mapOf(
        "base" to "base's photo".toByteArray(),
        "main" to "main's photo".toByteArray(),
        "feature" to Random(1).nextBytes(1024 * 1024),
    )
    private val objects = contents.mapValues { (_, bytes) -> LfsObject(oidOf(bytes), bytes.size.toLong()) }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        server = FakeLfsServer(File(tempDir, "server"))
    }

    @AfterEach
    fun tearDown() {
        server.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `theirs' object is downloaded, then shows as an image, and opening the file again finds it`(): Unit =
        runBlocking {
            mergePhotos(onServer = listOf("feature"))
            val progress = mutableListOf<Long>()

            val result = download(objects.getValue("feature")) { progress += it }

            val expected = VersionPreview.Lfs(
                objects.getValue("feature"),
                downloaded = true,
                image = PreviewImage(contents.getValue("feature")),
            )

            assertEquals(Either.Ok(expected), result)
            assertEquals(contents.getValue("feature").toList(), mediaFile("feature").readBytes().toList())
            assertEquals(listOf<String?>(null), serverRequests)
            // In steps, up to the whole object
            assertTrue(progress.size > 1 && progress == progress.sorted(), "$progress")
            assertEquals(objects.getValue("feature").size, progress.last())
            assertEquals(expected, load().preview?.theirs)
        }

    @Test
    fun `an object that isn't an image shows as downloaded, without bytes`(): Unit = runBlocking {
        git.mergeContents(repository, "video.mp4" to { side -> pointer(side) })
        storeOnServer("feature")

        assertEquals(
            Either.Ok(VersionPreview.Lfs(objects.getValue("feature"), downloaded = true)),
            download(objects.getValue("feature"), path = "video.mp4"),
        )
    }

    @Test
    fun `an object already in the store isn't downloaded again`(): Unit = runBlocking {
        mergePhotos(onServer = emptyList())
        mediaFile("main").apply { parentFile.mkdirs() }.writeBytes(contents.getValue("main"))

        val result = download(objects.getValue("main"))

        assertEquals(
            Either.Ok(VersionPreview.Lfs(objects.getValue("main"), true, PreviewImage(contents.getValue("main")))),
            result,
        )
        assertEquals(emptyList<String>(), server.requests)
    }

    @Test
    fun `an object that the server doesn't have says what the server answered, and nothing is stored`(): Unit =
        runBlocking {
            mergePhotos(onServer = emptyList())

            assertEquals(
                Either.Err(LfsObjectDownloadError(LfsError.ObjectRefused(404, "Object does not exist"))),
                download(objects.getValue("feature")),
            )
            assertFalse(mediaFile("feature").exists())
        }

    @Test
    fun `without an LFS server, nothing is asked`(): Unit = runBlocking {
        mergePhotos(onServer = listOf("feature"))
        servers = { null }

        assertEquals(Either.Err(LfsObjectDownloadError(LfsError.NoServer)), download(objects.getValue("feature")))
        assertEquals(emptyList<String>(), server.requests)
    }

    @Test
    fun `when the current branch has no LFS server, as a rebase's detached HEAD, origin's is asked, as git-lfs does`():
        Unit = runBlocking {
        mergePhotos(onServer = listOf("feature"))
        git.run(repository, "remote", "add", "fork", "https://example.com/fork.git")
        git.run(repository, "remote", "add", "origin", "https://example.com/origin.git")
        servers = { remote -> LfsServer(server.url, remoteUrl = null).takeIf { remote == "refs/remotes/origin" } }

        assertTrue(download(objects.getValue("feature")) is Either.Ok)
        assertEquals(listOf(null, "refs/remotes/origin"), serverRequests)
    }

    @Test
    fun `with one remote that isn't origin, that one's is asked`(): Unit = runBlocking {
        mergePhotos(onServer = listOf("feature"))
        git.run(repository, "remote", "add", "upstream", "https://example.com/upstream.git")
        servers = { remote -> LfsServer(server.url, remoteUrl = null).takeIf { remote == "refs/remotes/upstream" } }

        assertTrue(download(objects.getValue("feature")) is Either.Ok)
        assertEquals(listOf(null, "refs/remotes/upstream"), serverRequests)
    }

    @Test
    fun `closing the credentials dialog says so, and nothing is stored`(): Unit = runBlocking {
        mergePhotos(onServer = listOf("feature"))
        server.credentials = "user" to "secret"

        val responder = launch(Dispatchers.Default) {
            credentialsStateManager.credentialsState.filterIsInstance<LfsCredentialsRequest>().first()
            credentialsStateManager.credentialsDenied()
        }

        val result = withTimeout(10_000) { download(objects.getValue("feature")) }

        responder.join()
        assertEquals(Either.Err(LfsObjectDownloadError(LfsError.CredentialsDenied)), result)
        assertFalse(mediaFile("feature").exists())
    }

    @Test
    fun `a cancelled download leaves nothing next to the store`(): Unit = runBlocking {
        mergePhotos(onServer = listOf("feature"))

        val job = launch(Dispatchers.Default) {
            // Cancelled as soon as the first bytes arrived
            download(objects.getValue("feature")) { cancel() }
        }

        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(mediaFile("feature").exists())
        assertEquals(emptyList<String>(), mediaFile("feature").parentFile.list().orEmpty().toList())
    }

    /** `photo.png` with a pointer to each side's object, merged into a conflict; [onServer]'s objects are stored. */
    private fun mergePhotos(onServer: List<String>) {
        git.mergeContents(repository, "photo.png" to { side -> pointer(side) })
        onServer.forEach(::storeOnServer)
    }

    private fun pointer(side: String) = "version https://git-lfs.github.com/spec/v1\n" +
        "oid sha256:${objects.getValue(side).oid}\nsize ${objects.getValue(side).size}\n"

    private fun storeOnServer(side: String) = File(tempDir, "server/${objects.getValue(side).oid}")
        .writeBytes(contents.getValue(side))

    private fun mediaFile(side: String): File = withRepository {
        Lfs(it).getMediaFile(LongObjectId.fromString(objects.getValue(side).oid)).toFile()
    }

    private suspend fun download(
        obj: LfsObject,
        path: String = "photo.png",
        onProgress: (Long) -> Unit = {},
    ): Either<VersionPreview.Lfs, GitError> {
        val lfsRepository = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))
        val credentials = ProvideLfsCredentialsGitAction(
            credentialsCacheRepository = CredentialsCacheRepository(),
            credentialsStateManager = credentialsStateManager,
            credentialHelpers = CredentialHelpers(
                shellManager = ShellManager(),
                gitCredentialsManagerProvider = mockk(relaxed = true),
                loginShellEnvironment = LoginShellEnvironment { shellVariables },
                gitCli = testGitCli(shellVariables),
            ),
            appSettingsService = testAppSettings(),
        )
        val ssh = AuthenticateLfsServerWithSshGitAction(
            AskpassProcessRunner(
                ProcessRunner(),
                AskpassHelper { null },
                credentialsStateManager,
                CredentialsCacheRepository(),
            ),
            testGitCli(shellVariables),
            LoginShellEnvironment { shellVariables },
        )
        val getLfsUrl = object : IGetLfsUrlGitAction {
            override suspend fun invoke(repository: Repository, remoteName: String?): LfsServer? {
                serverRequests += remoteName
                return servers(remoteName)
            }
        }
        val action = DownloadLfsVersionGitAction(
            testJGit(),
            getLfsUrl,
            FetchLfsObjectGitAction(
                GetLfsObjectsGitAction(lfsRepository, credentials),
                ssh,
                DownloadLfsObjectGitAction(lfsRepository, credentials),
            ),
        )

        return action(File(repository, ".git").path, path, obj, onProgress)
    }

    private suspend fun load() = testJGit().let { jgit ->
        assertOk(GetConflictedFileGitAction(jgit, testPartialCloneFetch(jgit))(File(repository, ".git").path, "photo.png"))
    }

    private fun <T> withRepository(block: (Repository) -> T): T =
        FileRepositoryBuilder().setGitDir(File(repository, ".git")).build().use(block)

    private fun oidOf(bytes: ByteArray) = LongObjectId.fromRaw(Constants.newMessageDigest().digest(bytes)).name()

    private fun <T> assertOk(result: Either<T, *>): T {
        assertTrue(result is Either.Ok, "$result")
        return (result as Either.Ok).value
    }
}
