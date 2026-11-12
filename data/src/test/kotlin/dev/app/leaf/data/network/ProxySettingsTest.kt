// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testGitSettings
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.network.ProxyChoice
import dev.app.leaf.domain.network.ProxyConfigEntry
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val URL = "http://lfs.invalid/team/repo.git/info/lfs/objects/batch"

/** Where the proxy settings come from: the repository's config as Leaf opens it, the user's config, the environment. */
@DisabledOnOs(OS.WINDOWS)
class ProxySettingsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }

    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }

    /** git reads [globalConfig] and no system config, and the machine's proxy variables are set to nothing. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to File(root, "home").path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
        ) + listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy")
            .associateWith { "" }
    }

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    private fun http(port: Int, source: String) = ProxyChoice.Http("127.0.0.1", port, null, source)

    @Test
    fun `a repository's entries, with the user's under them`(): Unit = runBlocking {
        globalConfig.writeText("[http \"http://lfs.invalid\"]\n\tproxy = 127.0.0.1:1\n[http]\n\tproxy = 127.0.0.1:2\n")
        Git.init().setDirectory(File(root, "repository")).call().use { git ->
            git.repository.config.apply {
                setString("http", null, "proxy", "127.0.0.1:3")
                save()
            }
            val settings = ProxySettings({ emptyMap() }, { emptyList() })

            assertEquals(
                listOf(ProxyConfigEntry(null, "127.0.0.1:3"), ProxyConfigEntry("http://lfs.invalid", "127.0.0.1:1")),
                git.repository.config.proxyEntries(),
            )
            assertEquals(http(1, "http.http://lfs.invalid.proxy"), settings.forRequest(git.repository.config, URL))
            assertEquals(http(3, "http.proxy"), settings.forRequest(git.repository.config, "http://other.invalid/x"))
        }
    }

    @Test
    fun `an entry with an empty value is there, with an empty value`() {
        val config = Config().apply {
            fromText("[http \"http://lfs.invalid\"]\n\tproxy =\n[http \"http://other.invalid\"]\n\tproxy\n[http]\n\tproxy = 127.0.0.1:2\n")
        }

        assertEquals(
            listOf(
                ProxyConfigEntry(null, "127.0.0.1:2"),
                ProxyConfigEntry("http://lfs.invalid", ""),
                ProxyConfigEntry("http://other.invalid", ""),
            ),
            config.proxyEntries(),
        )
        // So it hides http.proxy, as in git-lfs, and the variables apply
        val settings = ProxySettings({ mapOf("HTTP_PROXY" to "127.0.0.1:4") }, { emptyList() })
        assertEquals(http(4, "HTTP_PROXY"), runBlocking { settings.forRequest(config, URL) })
    }

    @Test
    fun `a proxy that a file named by includeIf sets`(): Unit = runBlocking {
        val included = File(root, "proxy.gitconfig").apply { writeText("[http]\n\tproxy = 127.0.0.1:5\n") }
        globalConfig.writeText("[includeIf \"gitdir:${File(root, "work").path}/\"]\n\tpath = ${included.path}\n")
        val gitDir = Git.init().setDirectory(File(root, "work/repository")).call().use { it.repository.directory }
        val jgit = testJGit(includedConfig = GitIncludedConfig(testGitCli(shellVariables), LoginShellEnvironment { shellVariables }))
        val settings = ProxySettings({ emptyMap() }, { emptyList() })

        val choice = jgit.provide(gitDir.path) { git -> settings.forRequest(git.repository.config, URL) }

        assertEquals(Either.Ok(http(5, "http.proxy")), choice)
    }

    @Test
    fun `without a repository, the user's config as git reads it`(): Unit = runBlocking {
        val included = File(root, "proxies.gitconfig").apply {
            writeText("[http \"http://lfs.invalid\"]\n\tproxy = 127.0.0.1:6\n")
        }
        globalConfig.writeText("[include]\n\tpath = ${included.path}\n")
        // A setting that only git reads, from the environment
        val variables = shellVariables + mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.proxy",
            "GIT_CONFIG_VALUE_0" to "127.0.0.1:7",
        )
        val settings = ProxySettings(LoginShellEnvironment { variables }, testGitCli(variables))

        assertEquals(http(6, "http.http://lfs.invalid.proxy"), settings.forRequestWithoutRepository(URL))
        assertEquals(http(7, "http.proxy"), settings.forRequestWithoutRepository("http://other.invalid/x"))
    }

    @Test
    fun `without a repository, the environment that the login shell sets up`(): Unit = runBlocking {
        val settings = ProxySettings(
            LoginShellEnvironment { shellVariables + mapOf("HTTPS_PROXY" to "127.0.0.1:8") },
            testGitCli(shellVariables),
        )

        assertEquals(http(8, "HTTPS_PROXY"), settings.forRequestWithoutRepository("https://lfs.invalid/x"))
    }

    @Test
    fun `without a repository or a usable git, JGit's user config`(): Unit = runBlocking {
        globalConfig.writeText("[http]\n\tproxy = 127.0.0.1:9\n")
        val withoutGit = GitCli(
            GitExecutableLocator(ProcessRunner()),
            ProcessRunner(),
            testGitSettings(File(tempDir, "no-git").path),
            LoginShellEnvironment { shellVariables },
        )
        val settings = ProxySettings(LoginShellEnvironment { shellVariables }, withoutGit)

        assertEquals(http(9, "http.proxy"), settings.forRequestWithoutRepository(URL))
    }

    @Test
    fun `the proxy entries among git's config list`() {
        val entries = listOf(
            "http.proxy" to "a",
            "http.https://example.com/x.proxy" to "b",
            "http.https://example.com.proxy" to null,
            "http.sslverify" to "false",
            "http.https://example.com.sslverify" to "false",
            "http..proxy" to "c",
            "remote.origin.proxy" to "d",
            "http.proxyauthmethod" to "basic",
        )

        assertEquals(
            listOf(
                ProxyConfigEntry(null, "a"),
                ProxyConfigEntry("https://example.com/x", "b"),
                ProxyConfigEntry("https://example.com", ""),
            ),
            proxyEntriesOf(entries),
        )
    }
}
