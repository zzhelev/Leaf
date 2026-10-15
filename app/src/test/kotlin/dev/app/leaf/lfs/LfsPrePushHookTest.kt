// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.lfs

import com.sun.net.httpserver.HttpServer
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.lfs.AuthenticateLfsServerWithSshGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.GetLfsUrlSettingGitAction
import dev.app.leaf.data.git.lfs.ProvideLfsCredentialsGitAction
import dev.app.leaf.data.git.lfs.UploadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.VerifyUploadLfsObjectGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.network.createHttpClient
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.errors.LfsException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import javax.inject.Provider

/** Where the pre-push hook of a push through JGit uploads LFS objects, when git-lfs doesn't. */
class LfsPrePushHookTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    /** The paths that the LFS server was asked, which answers 404 to all. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            requests += exchange.requestURI.path
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
    }

    @BeforeEach
    fun setUp() {
        // Keeps the developer's git config, and its url.<base>.insteadOf, out of the test
        val configDir = File(tempDir, "config")
        SystemReader.setInstance(object : SystemReader.Delegate(originalReader) {
            override fun openUserConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "user.gitconfig"), fs)

            override fun openSystemConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

            override fun openJGitConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "jgit.config"), fs)
        })
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `objects go up to the remote's push URL, or to lfs pushurl, as git-lfs uploads them`() {
        val url = "http://127.0.0.1:${server.address.port}"

        Git.init().setDirectory(File(tempDir, "repo")).setInitialBranch("main").call().use { git ->
            val oid = MessageDigest.getInstance("SHA-256").digest("photo".toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(git.repository.workTree, "photo.bin")
                .writeText("version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize 5\n")
            git.add().addFilepattern("photo.bin").call()
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.commit().setMessage("Photo").setAuthor(identity).setCommitter(identity).call()
            git.remoteAdd().setName("origin").setUri(URIish("$url/fetch/repo")).call()
            git.repository.config.apply { setString("remote", "origin", "pushurl", "$url/push/repo") }.save()

            assertThrows<LfsException> { push(git) }
            assertEquals(listOf("/push/repo.git/info/lfs/objects/batch"), requests)

            git.repository.config.apply { setString("lfs", null, "pushurl", "$url/lfs-push") }.save()
            requests.clear()

            assertThrows<LfsException> { push(git) }
            assertEquals(listOf("/lfs-push/objects/batch"), requests)
        }
    }

    /** Runs the hook of a push of `main` to `origin`, which fails at the server's 404. */
    private fun push(git: Git) {
        val shellEnvironment = LoginShellEnvironment(ProcessRunner())
        val jgit = JGit(Provider { error("Only used on Windows") }, shellEnvironment)
        val settings = mockk<AppSettingsRepository> {
            every { gitExecutablePath } returns flowOf(File(tempDir, "no-git").path)
        }
        val withoutGit = GitCli(
            GitExecutableLocator(ProcessRunner()),
            ProcessRunner(),
            AppSettingsService(settings),
            shellEnvironment,
        )
        val lfsRepository = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))
        val credentialsStateManager = CredentialsStateManager()
        // Not asked: the server answers 404, not 401
        val credentials = ProvideLfsCredentialsGitAction(
            CredentialsCacheRepository(),
            credentialsStateManager,
            CredentialHelpers(ShellManager(), mockk(relaxed = true), shellEnvironment, withoutGit),
            AppSettingsService(settings),
        )
        val hook = LfsPrePushHook(
            git.repository,
            null,
            null,
            GetLfsUrlGitAction(
                GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
                GetLfsUrlSettingGitAction(withoutGit),
            ),
            GetLfsObjectsGitAction(lfsRepository, credentials),
            UploadLfsObjectGitAction(lfsRepository, credentials),
            VerifyUploadLfsObjectGitAction(lfsRepository, credentials),
            AuthenticateLfsServerWithSshGitAction(
                AskpassProcessRunner(
                    ProcessRunner(),
                    AskpassHelper { null },
                    credentialsStateManager,
                    CredentialsCacheRepository(),
                ),
                withoutGit,
                shellEnvironment,
            ),
        )

        hook.setRemoteName("origin")
        hook.setRefs(listOf(RemoteRefUpdate(git.repository, "refs/heads/main", "refs/heads/main", false, null, null)))
        hook.call()
    }
}
