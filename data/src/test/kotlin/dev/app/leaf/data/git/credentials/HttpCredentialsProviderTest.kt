// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.remote_operations.HandleTransportGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsRequest.HttpCredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import javax.inject.Provider

private const val REMOTE_URL = "https://example.invalid/team/project.git"
private const val EXPECTED_INPUT = "protocol=https\nhost=example.invalid\n"
private const val PORT_URL = "https://example.invalid:8443/team/project.git"

/**
 * The credentials that [HttpCredentialsProvider] gives for HTTPS remotes on macOS and Linux: from the credential
 * helpers it starts, or from Leaf's in-memory cache when there is no helper.
 */
@DisabledOnOs(OS.WINDOWS)
class HttpCredentialsProviderTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val credentialsStateManager = CredentialsStateManager()

    /** Leaf's in-memory cache, which [HttpCredentialsProvider] uses when no credential helper is configured. */
    private val credentialsCache = CredentialsCacheRepository()

    /** Holds the helpers and the tools they run, on no PATH unless a test puts it there, like Homebrew's folder. */
    private val tools by lazy { File(tempDir, "tools").apply { mkdirs() } }

    /**
     * What the login shell adds: its PATH includes [tools], and git ignores the developer's own config. Git's `store`
     * and `cache` helpers keep their credentials in the test's folder: `~/.git-credentials` and
     * `$XDG_CACHE_HOME/git/credential/socket`.
     */
    private val shellVariables by lazy {
        mapOf(
            "PATH" to "${tools.absolutePath}:${System.getenv("PATH")}",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").apply { createNewFile() }.absolutePath,
            "HOME" to tempDir.absolutePath,
            "XDG_CONFIG_HOME" to File(tempDir, "xdg-config").absolutePath,
            // Short, as a socket's path can't be longer than 104 bytes on macOS
            "XDG_CACHE_HOME" to tempDir.absolutePath,
        )
    }

    /** The user's git config, which git reads also without a repository. */
    private val globalConfig by lazy { File(shellVariables.getValue("GIT_CONFIG_GLOBAL")) }

    /** What Leaf asked the user each time: the user name it showed, if any, and whether it asked for the password. */
    private val requests = mutableListOf<HttpCredentialsRequest>()

    /** The repositories that [createProvider] opened. */
    private val repositories = mutableListOf<Git>()

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        repositories.forEach { it.close() }
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a helper given by name runs as git credential-name, found on the login shell's PATH`() {
        createHelper("git-credential-leaf-test")

        val answer = requestCredentials(helper = "leaf-test")

        assertEquals(Answer("helper-user", "helper-password"), answer)
        assertEquals(listOf("get"), File(tools, "git-credential-leaf-test.args").readLines())
        assertEquals(EXPECTED_INPUT, File(tools, "git-credential-leaf-test.input").readText())
    }

    @Test
    fun `a shell command helper finds its command on the login shell's PATH`() {
        createHelper("leaf-gh")

        val answer = requestCredentials(helper = "!leaf-gh auth git-credential")

        assertEquals(Answer("helper-user", "helper-password"), answer)
        assertEquals(listOf("auth git-credential get"), File(tools, "leaf-gh.args").readLines())
        assertEquals(EXPECTED_INPUT, File(tools, "leaf-gh.input").readText())
    }

    @Test
    fun `without the login shell's PATH the same helper isn't found, so Leaf asks for the credentials`() {
        createHelper("leaf-gh")

        val answer = requestCredentials(helper = "!leaf-gh auth git-credential", shellVariables = emptyMap())

        assertEquals(Answer("prompted-user", "prompted-password"), answer)
        assertFalse(File(tools, "leaf-gh.args").exists())
    }

    @Test
    fun `a helper given by its path runs tools from the login shell's PATH`() {
        File(tools, "leaf-credentials-tool").writeExecutable(
            "#!/bin/sh\nprintf 'username=tool-user\\npassword=tool-password\\n'\n"
        )
        val helper = File(tempDir, "helpers/leaf-helper").writeExecutable(
            "#!/bin/sh\ncat > /dev/null\n[ \"\$1\" = get ] && leaf-credentials-tool\n"
        )

        val answer = requestCredentials(helper = helper.absolutePath)

        assertEquals(Answer("tool-user", "tool-password"), answer)
    }

    @Test
    fun `manager runs git-credential-manager from the login shell's PATH`() {
        createHelper("git-credential-manager")
        val managerProvider = object : IGitCredentialsManagerProvider {
            override fun loadPath(): String = error("Git finds the credential manager on macOS and Linux")
        }

        val answer = requestCredentials(helper = "manager", gitCredentialsManagerProvider = managerProvider)

        assertEquals(Answer("helper-user", "helper-password"), answer)
    }

    @Test
    fun `credentials that Leaf asks for are stored with the helper once the server accepts them`() {
        // Answers no get, so that Leaf asks, and then stores what it was given
        File(tools, "git-credential-leaf-test").writeExecutable(
            "#!/bin/sh\ncat > \"\$(dirname \"\$0\")/\$1.input\"\n"
        )

        val answer = requestCredentials(helper = "leaf-test", serverAccepts = true)

        assertEquals(Answer("prompted-user", "prompted-password"), answer)
        assertEquals(
            "${EXPECTED_INPUT}username=prompted-user\npassword=prompted-password\n",
            File(tools, "store.input").readText(),
        )
    }

    @Test
    fun `a helper that exits without reading its input leads to asking the user, not to an error`() {
        val shellManager = object : IShellManager by ShellManager() {
            override fun runCommandProcess(
                command: List<String>,
                directory: File?,
                environment: Map<String, String>,
            ): Process = ExitedProcess
        }

        val answer = requestCredentials(helper = "leaf-test", shellManager = shellManager)

        assertEquals(Answer("prompted-user", "prompted-password"), answer)
    }

    @Test
    fun `store gives back saved credentials, with an equals sign in the password`() {
        runGit(
            listOf("credential-store", "store"),
            input = "${EXPECTED_INPUT}username=saved-user\npassword=saved=password==\n",
        )

        val answer = requestCredentials(helper = "store")

        assertEquals(Answer("saved-user", "saved=password=="), answer)
    }

    @Test
    fun `store saves the credentials that Leaf asks for, and gives them back next time`() {
        assertEquals(
            Answer("prompted-user", "prompted-password"),
            requestCredentials(helper = "store", serverAccepts = true),
        )
        assertEquals(
            "https://prompted-user:prompted-password@example.invalid\n",
            File(tempDir, ".git-credentials").readText(),
        )

        val answer = requestCredentials(helper = "store", promptAnswer = Answer("asked-again", "asked-again"))

        assertEquals(Answer("prompted-user", "prompted-password"), answer)
    }

    @Test
    fun `cache keeps the credentials that Leaf asks for, and gives them back next time`() {
        try {
            assertEquals(
                Answer("prompted-user", "prompted-password"),
                requestCredentials(helper = "cache", serverAccepts = true),
            )
            awaitCachedCredentials()

            val answer = requestCredentials(helper = "cache", promptAnswer = Answer("asked-again", "asked-again"))

            assertEquals(Answer("prompted-user", "prompted-password"), answer)
        } finally {
            // Stops the cache daemon that the first store started
            runCatching { runGit(listOf("credential-cache", "exit"), input = "") }
        }
    }

    @Test
    fun `credentials that the server rejects are erased with the helper, which gets them as git sends them`() {
        createRecordingHelper(answer = Answer("helper-user", "helper=password"))
        val provider = createProvider(helper = "leaf-test", useHttpPath = true)

        assertEquals(Answer("helper-user", "helper=password"), provider.requestCredentials())
        provider.reset(URIish(REMOTE_URL))
        // JGit resets only after a get, but a second reset must not erase them again
        provider.reset(URIish(REMOTE_URL))

        assertEquals(listOf("get", "erase"), File(tools, "operations").readLines())
        val getInput = File(tools, "get.input").readText()
        assertTrue(getInput.contains("\npath="), getInput)
        assertEquals(
            "${getInput}username=helper-user\npassword=helper=password\n",
            File(tools, "erase.input").readText(),
        )
    }

    @Test
    fun `store forgets credentials that the server rejects, so Leaf asks for new ones and stores them`() {
        runGit(
            listOf("credential-store", "store"),
            input = "${EXPECTED_INPUT}username=saved-user\npassword=wrong=password\n",
        )
        val credentialsFile = File(tempDir, ".git-credentials")
        val provider = createProvider(helper = "store")

        assertEquals(Answer("saved-user", "wrong=password"), provider.requestCredentials())
        provider.reset(URIish(REMOTE_URL))

        assertEquals("", credentialsFile.readText())

        val answer = provider.requestCredentials(Answer("new-user", "new-password"), serverAccepts = true)

        assertEquals(Answer("new-user", "new-password"), answer)
        assertEquals("https://new-user:new-password@example.invalid\n", credentialsFile.readText())
    }

    @Test
    fun `credentials that Leaf asked for and the server rejects are erased, and never stored, as git does`() {
        createRecordingHelper(answer = null)
        val provider = createProvider(helper = "leaf-test")

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials())
        provider.reset(URIish(REMOTE_URL))

        assertEquals(listOf("get", "erase"), File(tools, "operations").readLines())
        assertEquals(
            "${EXPECTED_INPUT}username=prompted-user\npassword=prompted-password\n",
            File(tools, "erase.input").readText(),
        )
    }

    @Test
    fun `credentials that git stores for a URL with a port and a path are found by Leaf`() {
        storeWithGit(PORT_URL, Answer("saved-user", "saved-password"))
        // Stored after it, so listed first: what Leaf would find without the port, or without the path
        storeWithGit("https://example.invalid/team/project.git", Answer("no-port", "no-port"))
        storeWithGit("https://example.invalid:8443/team/other.git", Answer("other-path", "other-path"))

        val answer = createProvider(helper = "store", useHttpPath = true).requestCredentials(url = PORT_URL)

        assertEquals(Answer("saved-user", "saved-password"), answer)
    }

    @Test
    fun `credentials that Leaf stores for a URL with a port and a path are found by git`() {
        val provider = createProvider(helper = "store", useHttpPath = true)

        assertEquals(
            Answer("prompted-user", "prompted-password"),
            provider.requestCredentials(url = PORT_URL, serverAccepts = true),
        )

        assertEquals(Answer("prompted-user", "prompted-password"), fillWithGit(PORT_URL))
    }

    @Test
    fun `credentials that git stores for a URL with a port and a path are erased when the server rejects them`() {
        storeWithGit(PORT_URL, Answer("saved-user", "wrong-password"))
        storeWithGit("https://example.invalid:8443/team/other.git", Answer("other-path", "other-path"))
        val provider = createProvider(helper = "store", useHttpPath = true)

        assertEquals(Answer("saved-user", "wrong-password"), provider.requestCredentials(url = PORT_URL))
        provider.reset(URIish(PORT_URL))

        assertEquals(null, fillWithGit(PORT_URL))
        assertEquals(Answer("other-path", "other-path"), fillWithGit("https://example.invalid:8443/team/other.git"))
    }

    @Test
    fun `credential settings for a URL apply when it has the remote's port, as in git`() {
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        val provider = createProvider(helper = null) {
            // For the default port, so not for the remote
            setString("credential", "https://example.invalid", "helper", "leaf-wrong")
            setString("credential", "https://EXAMPLE.invalid:8443/team", "helper", "leaf-test")
            setString("credential", "https://*.invalid:8443", "useHttpPath", "true")
        }

        assertEquals(Answer("helper-user", "helper-password"), provider.requestCredentials(url = PORT_URL))
        assertEquals(
            "protocol=https\nhost=example.invalid:8443\npath=team/project.git\n",
            File(tools, "get.input").readText(),
        )
    }

    @Test
    fun `a URL with a newline in its path doesn't reach the helper, as git refuses it`() {
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        val provider = createProvider(helper = "leaf-test", useHttpPath = true)

        // The helper would read a second host, and give that server's credentials
        val answer = provider.requestCredentials(url = "https://example.invalid/team%0Ahost=other.invalid/project.git")

        assertEquals(null, answer)
        assertFalse(File(tools, "operations").exists())
    }

    @Test
    fun `with a user name in the URL, a helper that gives only the password is enough`() {
        // Like osxkeychain, which gives only the password when git sends the user name
        File(tools, "git-credential-leaf-test").writeExecutable(
            "#!/bin/sh\ncat > /dev/null\n[ \"\$1\" = get ] && echo password=helper-password\nexit 0\n"
        )
        val provider = createProvider(helper = "leaf-test")

        val answer = provider.requestCredentials(url = "https://alice@example.invalid/team/project.git")

        assertEquals(Answer("alice", "helper-password"), answer)
    }

    @Test
    fun `without a repository, as when cloning, the user's git config sets the helper`() {
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        globalConfig.writeText("[credential]\n\thelper = leaf-test\n")
        val provider = createProvider(helper = null, inRepository = false)

        assertEquals(Answer("helper-user", "helper-password"), provider.requestCredentials())
    }

    @Test
    fun `typed credentials are stored with every helper once accepted, and erased from every helper when rejected`() {
        createRecordingHelper(answer = null)
        val credentialsFile = File(tempDir, ".git-credentials")
        val provider = createProvider(helper = null) {
            setStringList("credential", null, "helper", listOf("store", "leaf-test"))
        }

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials())
        assertEquals(listOf(HttpCredentialsRequest(null, askPassword = true)), requests)
        // Not before the server accepts them
        assertFalse(credentialsFile.exists())
        provider.credentialsAccepted()
        assertEquals("https://prompted-user:prompted-password@example.invalid\n", credentialsFile.readText())
        // A later request rejects them after all
        provider.reset(URIish(REMOTE_URL))

        assertEquals("", credentialsFile.readText())
        assertEquals(listOf("get", "store", "erase"), File(tools, "operations").readLines())
        assertEquals(
            "${EXPECTED_INPUT}username=prompted-user\npassword=prompted-password\n",
            File(tools, "erase.input").readText(),
        )
    }

    @Test
    fun `Leaf asks for what git asks for, and shows the user name that git shows`() {
        File(tools, "leaf-username").writeExecutable(
            "#!/bin/sh\ncat > /dev/null\n[ \"\$1\" = get ] && echo username=carol\nexit 0\n"
        )
        File(tools, "leaf-password").writeExecutable(
            "#!/bin/sh\ncat > /dev/null\n[ \"\$1\" = get ] && echo password=helper-password\nexit 0\n"
        )
        val aliceUrl = "https://alice@example.invalid/team/project.git"
        val bob = "[credential]\n\tusername = bob\n"
        val dora = "[credential \"https://example.invalid\"]\n\tusername = dora\n"
        val askBoth = HttpCredentialsRequest(null, askPassword = true)
        fun askPassword(user: String) = HttpCredentialsRequest(user, askPassword = true)
        // The user's config, the URL, and what git asks for
        val cases = listOf(
            Triple("", REMOTE_URL, askBoth),
            Triple("", aliceUrl, askPassword("alice")),
            Triple(bob, REMOTE_URL, askPassword("bob")),
            Triple(bob, aliceUrl, askPassword("alice")),
            Triple(dora, REMOTE_URL, askPassword("dora")),
            Triple(dora, "https://other.invalid/team/project.git", askBoth),
            // A helper that gives only the user name
            Triple("[credential]\n\thelper = !leaf-username\n", REMOTE_URL, askPassword("carol")),
            // A helper that gives only the password
            Triple(
                "[credential]\n\thelper = !leaf-password\n",
                REMOTE_URL,
                HttpCredentialsRequest(null, askPassword = false),
            ),
        )

        assertAll(
            cases.map { (config, url, expected) ->
                Executable {
                    globalConfig.writeText(config)
                    requests.clear()

                    val answer = createProvider(helper = null, inRepository = false).requestCredentials(url = url)

                    assertEquals(expected, requestThatGitMakes(url), "what git asks for $url with $config")
                    assertEquals(listOf(expected), requests, "what Leaf asks for $url with $config")
                    // Whatever the dialog gives for a part that git knows, git's is the one used
                    assertEquals(
                        Answer(
                            expected.user ?: "prompted-user",
                            if (expected.askPassword) "prompted-password" else "helper-password",
                        ),
                        answer,
                    )
                }
            }
        )
    }

    @Test
    fun `a helper's password with the typed user name is stored with every helper once accepted, and erased`() {
        createRecordingHelperAnswering(listOf("password=helper-password"))
        val credentialsFile = File(tempDir, ".git-credentials")
        val provider = createProvider(helper = null) {
            setStringList("credential", null, "helper", listOf("leaf-test", "store"))
        }

        assertEquals(Answer("prompted-user", "helper-password"), provider.requestCredentials(serverAccepts = true))
        assertEquals(listOf(HttpCredentialsRequest(null, askPassword = false)), requests)
        assertEquals("https://prompted-user:helper-password@example.invalid\n", credentialsFile.readText())
        provider.reset(URIish(REMOTE_URL))

        assertEquals("", credentialsFile.readText())
        assertEquals(listOf("get", "store", "erase"), File(tools, "operations").readLines())
        assertEquals(
            "${EXPECTED_INPUT}username=prompted-user\npassword=helper-password\n",
            File(tools, "erase.input").readText(),
        )
    }

    @Test
    fun `typed credentials are stored with the user name that git knows, where git finds them`() {
        val credentialsFile = File(tempDir, ".git-credentials")
        val provider = createProvider(helper = "store") { setString("credential", null, "username", "bob") }

        // Whatever user name the dialog gives, the request's is the one used
        assertEquals(Answer("bob", "prompted-password"), provider.requestCredentials(serverAccepts = true))
        assertEquals(listOf(HttpCredentialsRequest("bob", askPassword = true)), requests)
        assertEquals("https://bob:prompted-password@example.invalid\n", credentialsFile.readText())

        // Git, with the same settings, gives them without asking
        val filled = runGit(
            listOf("-c", "credential.helper=store", "-c", "credential.username=bob", "credential", "fill"),
            input = "url=$REMOTE_URL\n",
        )
        assertEquals("${EXPECTED_INPUT}username=bob\npassword=prompted-password\n", filled)
    }

    @Test
    fun `credentials that a helper gave are stored with every helper once the operation succeeds, as git does`() {
        createRecordingHelperAnswering(
            listOf(
                "username=helper-user",
                "password=helper-password",
                "oauth_refresh_token=refresh",
                "password_expiry_utc=4102444800",
            )
        )
        val provider = createProvider(helper = null) {
            setStringList("credential", null, "helper", listOf("leaf-test", "store"))
        }

        assertEquals(Answer("helper-user", "helper-password"), provider.requestCredentials())
        // The operation succeeded
        runBlocking { provider.cacheCredentialsIfNeeded() }

        val credentialsFile = File(tempDir, ".git-credentials")
        assertEquals("https://helper-user:helper-password@example.invalid\n", credentialsFile.readText())
        // The helper that gave them gets them back, with its refresh token and expiry
        assertEquals(listOf("get", "store"), File(tools, "operations").readLines())
        assertEquals(
            "${EXPECTED_INPUT}username=helper-user\npassword=helper-password\noauth_refresh_token=refresh\n" +
                    "password_expiry_utc=4102444800\n",
            File(tools, "store.input").readText(),
        )
    }

    @Test
    fun `what the helpers gave besides is stored with the typed password, as git does`() {
        createRecordingHelperAnswering(listOf("username=helper-user", "oauth_refresh_token=refresh"))
        val provider = createProvider(helper = "leaf-test")

        assertEquals(Answer("helper-user", "prompted-password"), provider.requestCredentials(serverAccepts = true))

        assertEquals(
            "${EXPECTED_INPUT}username=helper-user\npassword=prompted-password\noauth_refresh_token=refresh\n",
            File(tools, "store.input").readText(),
        )
    }

    @Test
    fun `credentials are stored once, at the first request that the server accepts`() {
        createRecordingHelper(answer = null)
        val provider = createProvider(helper = "leaf-test")

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials(serverAccepts = true))
        // More requests succeed with them, then the whole operation
        provider.credentialsAccepted()
        runBlocking { provider.cacheCredentialsIfNeeded() }

        assertEquals(listOf("get", "store"), File(tools, "operations").readLines())
    }

    @Test
    fun `credentials are stored once a request succeeds with them, even if the operation then fails, as git does`() {
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        val provider = createProvider(helper = null) {
            setStringList("credential", null, "helper", listOf("leaf-test", "store"))
        }

        FakeGitServer(accepts = Answer("helper-user", "helper-password")).use { server ->
            val result = fetchThroughLeaf(provider, server.url)

            // The server took the credentials, then failed the fetch
            assertInstanceOf(Either.Err::class.java, result)
            assertEquals(listOf("GET 401", "GET 200", "POST 500"), server.requests)
            assertEquals(listOf("get", "store"), File(tools, "operations").readLines())
            assertEquals(
                "protocol=http\nhost=127.0.0.1:${server.port}\nusername=helper-user\npassword=helper-password\n",
                File(tools, "store.input").readText(),
            )
            assertEquals(
                "http://helper-user:helper-password@127.0.0.1%3a${server.port}\n",
                File(tempDir, ".git-credentials").readText(),
            )
        }
    }

    @Test
    fun `through JGit, rejected credentials are erased, and typed ones stored once a request succeeds with them`() {
        val provider = createProvider(helper = "store")

        FakeGitServer(accepts = Answer("prompted-user", "prompted-password")).use { server ->
            val credentialsFile = File(tempDir, ".git-credentials")
            // As git's store writes them
            credentialsFile.writeText("http://saved-user:old-password@127.0.0.1%3a${server.port}\n")

            fetchThroughLeaf(provider, server.url)

            assertEquals(listOf("GET 401", "GET 401", "GET 200", "POST 500"), server.requests)
            assertEquals(listOf(HttpCredentialsRequest(null, askPassword = true)), requests)
            assertEquals(
                "http://prompted-user:prompted-password@127.0.0.1%3a${server.port}\n",
                credentialsFile.readText(),
            )
        }
    }

    @Test
    fun `without a helper, typed credentials are cached as soon as a request succeeds with them`() {
        val provider = createProvider(helper = null)

        FakeGitServer(accepts = Answer("prompted-user", "prompted-password")).use { server ->
            val result = fetchThroughLeaf(provider, server.url)

            val cached = credentialsCache.getCachedHttpCredentials(server.url, isLfs = false)

            assertInstanceOf(Either.Err::class.java, result)
            assertEquals(Answer("prompted-user", "prompted-password"), cached?.let { Answer(it.user, it.password) })
        }
    }

    @Test
    fun `after a rejection, the next credentials are stored once the server accepts them`() {
        createRecordingHelper(answer = null)
        val provider = createProvider(helper = "leaf-test")

        provider.requestCredentials(serverAccepts = true)
        // A later request rejects them, and JGit asks again
        provider.reset(URIish(REMOTE_URL))
        provider.requestCredentials(Answer("new-user", "new-password"), serverAccepts = true)

        assertEquals(listOf("get", "store", "erase", "get", "store"), File(tools, "operations").readLines())
        assertEquals(
            "${EXPECTED_INPUT}username=new-user\npassword=new-password\n",
            File(tools, "store.input").readText(),
        )
    }

    @Test
    fun `without a helper, typed credentials that a later request rejects leave the cache`() {
        val provider = createProvider(helper = null)

        provider.requestCredentials(serverAccepts = true)
        assertEquals(Answer("prompted-user", "prompted-password"), cachedInMemory())
        provider.reset(URIish(REMOTE_URL))

        assertNull(cachedInMemory())
    }

    @Test
    fun `without a helper, cached credentials that the server rejects are dropped, and the new ones cached`() {
        cacheInMemory(Answer("cached-user", "old-password"))
        val provider = createProvider(helper = null)

        assertEquals(Answer("cached-user", "old-password"), provider.requestCredentials())
        provider.reset(URIish(REMOTE_URL))

        assertNull(cachedInMemory())

        val answer = provider.requestCredentials(promptAnswer = Answer("cached-user", "new-password"))
        // The operation succeeded
        runBlocking { provider.cacheCredentialsIfNeeded() }

        assertEquals(Answer("cached-user", "new-password"), answer)
        assertEquals(Answer("cached-user", "new-password"), cachedInMemory())
    }

    @Test
    fun `without a helper, credentials that the user typed and the server rejected are never cached`() {
        val provider = createProvider(helper = null)

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials())
        // Meanwhile another tab's operation succeeds with other credentials, which the reset must keep
        cacheInMemory(Answer("other-user", "other-password"))
        provider.reset(URIish(REMOTE_URL))

        assertEquals(Answer("other-user", "other-password"), provider.requestCredentials())
        // The operation succeeded with the other tab's credentials
        runBlocking { provider.cacheCredentialsIfNeeded() }

        assertEquals(Answer("other-user", "other-password"), cachedInMemory())
    }

    @Test
    fun `with a helper, the in-memory cache is neither read nor changed`() {
        cacheInMemory(Answer("cached-user", "cached-password"))
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        val provider = createProvider(helper = "leaf-test")

        assertEquals(Answer("helper-user", "helper-password"), provider.requestCredentials())
        provider.reset(URIish(REMOTE_URL))

        assertEquals(listOf("get", "erase"), File(tools, "operations").readLines())
        assertEquals(Answer("cached-user", "cached-password"), cachedInMemory())
    }

    @Test
    fun `helper commands are built the way git builds them`() {
        assertEquals(
            listOf("/bin/sh", "-c", "git credential-osxkeychain get"),
            posixCredentialHelperCommand("osxkeychain", "get"),
        )
        assertEquals(
            listOf("/bin/sh", "-c", "/opt/homebrew/bin/gh auth git-credential store"),
            posixCredentialHelperCommand("!/opt/homebrew/bin/gh auth git-credential", "store"),
        )
        assertEquals(
            listOf("/bin/sh", "-c", "/usr/local/bin/git-credential-manager erase"),
            posixCredentialHelperCommand("/usr/local/bin/git-credential-manager", "erase"),
        )
        assertEquals(
            listOf("/bin/sh", "-c", "git credential-store --file ~/.leaf-credentials get"),
            posixCredentialHelperCommand("store --file ~/.leaf-credentials", "get"),
        )
    }

    /**
     * Creates the helper [name] in [tools]. It answers `get` with fixed credentials, and writes its arguments and its
     * input to `<name>.args` and `<name>.input`.
     */
    private fun createHelper(name: String) {
        File(tools, name).writeExecutable(
            """
            |#!/bin/sh
            |dir="${'$'}(dirname "${'$'}0")"
            |echo "${'$'}*" >> "${'$'}dir/$name.args"
            |cat >> "${'$'}dir/$name.input"
            |for operation; do :; done
            |[ "${'$'}operation" = get ] && printf 'username=helper-user\npassword=helper-password\n'
            |exit 0
            |""".trimMargin()
        )
    }

    /**
     * Creates the helper `git-credential-leaf-test` in [tools]. It writes the input of each operation to
     * `<operation>.input`, and each operation to `operations` when it finishes. It answers `get` with [answer], if
     * there is one.
     */
    private fun createRecordingHelper(answer: Answer?) = createRecordingHelperAnswering(
        answer?.let { listOf("username=${it.user}", "password=${it.password}") }.orEmpty()
    )

    /** Like [createRecordingHelper], answering `get` with the [lines]. */
    private fun createRecordingHelperAnswering(lines: List<String>) {
        val answerGet = if (lines.isEmpty()) ":" else "printf '${lines.joinToString("") { "$it\\n" }}'"

        File(tools, "git-credential-leaf-test").writeExecutable(
            """
            |#!/bin/sh
            |dir="${'$'}(dirname "${'$'}0")"
            |operation="${'$'}1"
            |cat > "${'$'}dir/${'$'}operation.input"
            |echo "${'$'}operation" >> "${'$'}dir/operations"
            |[ "${'$'}operation" = get ] && $answerGet
            |exit 0
            |""".trimMargin()
        )
    }

    /**
     * Asks a new [HttpCredentialsProvider] for the credentials of [REMOTE_URL], with [helper] as `credential.helper`.
     * If Leaf asks the user instead, the answer is [promptAnswer]. With [serverAccepts], the server then accepts them.
     */
    private fun requestCredentials(
        helper: String,
        shellVariables: Map<String, String> = this.shellVariables,
        gitCredentialsManagerProvider: IGitCredentialsManagerProvider = NoCredentialsManager,
        shellManager: IShellManager = ShellManager(),
        promptAnswer: Answer = Answer("prompted-user", "prompted-password"),
        serverAccepts: Boolean = false,
    ): Answer? = createProvider(helper, shellVariables, gitCredentialsManagerProvider, shellManager)
        .requestCredentials(promptAnswer, serverAccepts = serverAccepts)

    /**
     * An [HttpCredentialsProvider] for a repository whose `credential.helper` is [helper], if there is one, with
     * [configure] for any other settings. Without [inRepository], it has no repository, as when cloning. Helpers run
     * with [shellVariables], while git always reads the test's config.
     */
    private fun createProvider(
        helper: String?,
        shellVariables: Map<String, String> = this.shellVariables,
        gitCredentialsManagerProvider: IGitCredentialsManagerProvider = NoCredentialsManager,
        shellManager: IShellManager = ShellManager(),
        useHttpPath: Boolean = false,
        inRepository: Boolean = true,
        configure: Config.() -> Unit = {},
    ): HttpCredentialsProvider {
        val git = if (inRepository) Git.init().setDirectory(File(tempDir, "repository")).call() else null
        git?.let { repositories += it }

        git?.repository?.config?.apply {
            helper?.let { setString("credential", null, "helper", it) }
            setBoolean("credential", null, "useHttpPath", useHttpPath)
            configure()
            save()
        }

        return HttpCredentialsProvider(
            credentialsStateManager = credentialsStateManager,
            credentialsCacheRepository = credentialsCache,
            credentialHelpers = CredentialHelpers(
                shellManager = shellManager,
                gitCredentialsManagerProvider = gitCredentialsManagerProvider,
                loginShellEnvironment = LoginShellEnvironment { shellVariables },
                gitCli = testGitCli(this.shellVariables),
            ),
            git = git,
        )
    }

    /**
     * Asks this provider for the credentials of [url], as JGit does, and returns them, or null if it gives none. If
     * Leaf asks the user, the answer is [promptAnswer]. With [serverAccepts], the server then accepts them, which the
     * transport reports ([HttpCredentialsProvider.credentialsAccepted]).
     */
    private fun HttpCredentialsProvider.requestCredentials(
        promptAnswer: Answer = Answer("prompted-user", "prompted-password"),
        url: String = REMOTE_URL,
        serverAccepts: Boolean = false,
    ): Answer? = runBlocking {
        val user = CredentialItem.Username()
        val password = CredentialItem.Password()

        val prompt = launch(Dispatchers.Default) {
            requests += credentialsStateManager.credentialsState.filterIsInstance<HttpCredentialsRequest>().first()
            credentialsStateManager.httpCredentialsAccepted(promptAnswer.user, promptAnswer.password)
        }

        val given = withContext(Dispatchers.IO) { get(URIish(url), user, password) }
        prompt.cancel()

        if (given && serverAccepts) {
            withContext(Dispatchers.IO) { credentialsAccepted() }
        }

        if (given) Answer(user.value, String(password.value)) else null
    }

    /**
     * Fetches [url] into the repository of [provider], with it as the HTTPS credentials provider, the way Leaf's git
     * actions do, through [HandleTransportGitAction]. If Leaf asks the user, the answer is [promptAnswer].
     */
    private fun fetchThroughLeaf(
        provider: HttpCredentialsProvider,
        url: String,
        promptAnswer: Answer = Answer("prompted-user", "prompted-password"),
    ): Either<FetchResult, GitError> = runBlocking {
        val git = checkNotNull(provider.git)
        val handleTransport = HandleTransportGitAction(
            sessionManager = GSessionManager(GSshSessionFactory { error("No SSH in this test") }),
            httpCredentialsProvider = object : HttpCredentialsFactory {
                override fun create(git: Git?) = provider
            },
            sshCredentialsProvider = Provider { error("No SSH in this test") },
            jgit = testJGit(shellVariables),
        )

        val prompt = launch(Dispatchers.Default) {
            requests += credentialsStateManager.credentialsState.filterIsInstance<HttpCredentialsRequest>().first()
            credentialsStateManager.httpCredentialsAccepted(promptAnswer.user, promptAnswer.password)
        }

        val result = withContext(Dispatchers.IO) {
            handleTransport(git.repository.directory.absolutePath) {
                git.fetch()
                    .setRemote(url)
                    .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    .setTransportConfigCallback { handleTransport(it) }
                    .call()
            }
        }
        prompt.cancel()

        result
    }

    /** Puts [credentials] for [REMOTE_URL] in Leaf's in-memory cache, as Leaf does once the server accepts them. */
    private fun cacheInMemory(credentials: Answer) = runBlocking {
        credentialsCache.cacheHttpCredentials(REMOTE_URL, credentials.user, credentials.password, isLfs = false)
    }

    /** The credentials for [REMOTE_URL] in Leaf's in-memory cache. */
    private fun cachedInMemory(): Answer? = credentialsCache.getCachedHttpCredentials(REMOTE_URL, isLfs = false)
        ?.let { Answer(it.user, it.password) }

    /** Waits until git's cache daemon has the credentials. */
    private fun awaitCachedCredentials() = runBlocking {
        withTimeout(10_000) {
            while (!runGit(listOf("credential-cache", "get"), EXPECTED_INPUT).contains("username=")) {
                delay(20)
            }
        }
    }

    /**
     * Saves credentials for [url] with git's `store`, with `credential.useHttpPath`, as git does when the server
     * accepts them.
     */
    private fun storeWithGit(url: String, answer: Answer) {
        runGit(
            listOf("-c", "credential.helper=store", "-c", "credential.useHttpPath=true", "credential", "approve"),
            input = "url=$url\nusername=${answer.user}\npassword=${answer.password}\n",
        )
    }

    /**
     * The credentials that git's `store` gives `git credential fill` for [url], with `credential.useHttpPath`, or null
     * if it has none.
     */
    private fun fillWithGit(url: String): Answer? {
        val output = runGit(
            listOf("-c", "credential.helper=store", "-c", "credential.useHttpPath=true", "credential", "fill"),
            input = "url=$url\n",
            // Without credentials, git fails as it can't ask for them
            requireSuccess = false,
        )
        val values = output.lines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }

        return values["password"]?.let { Answer(values.getValue("username"), it) }
    }

    /**
     * What `git credential fill` asks the user for [url], with the user's config, as a request of Leaf's: the user
     * name that it shows when it asks for the password alone, and whether it asks for the password. Git asks through
     * `GIT_ASKPASS`.
     */
    private fun requestThatGitMakes(url: String): HttpCredentialsRequest {
        val prompts = File(tempDir, "prompts").apply { delete() }
        val askpass = File(tempDir, "askpass").writeExecutable(
            "#!/bin/sh\necho \"\$1\" >> '${prompts.absolutePath}'\necho typed\n"
        )

        runGit(listOf("credential", "fill"), "url=$url\n", environment = mapOf("GIT_ASKPASS" to askpass.absolutePath))
        val asked = prompts.readLines()
        val passwordPrompt = asked.singleOrNull { it.startsWith("Password") }

        // When git asks for the user name, the password prompt shows the typed one
        if (asked.any { it.startsWith("Username") }) {
            return HttpCredentialsRequest(null, askPassword = passwordPrompt != null)
        }

        val shownUser = Regex("^Password for 'https://([^@]+)@").find(checkNotNull(passwordPrompt))!!.groupValues[1]
        return HttpCredentialsRequest(shownUser, askPassword = true)
    }

    /**
     * Runs git with [args] and [input] outside any repository, ignoring the developer's git config and never prompting,
     * and returns its output.
     */
    private fun runGit(
        args: List<String>,
        input: String,
        requireSuccess: Boolean = true,
        environment: Map<String, String> = emptyMap(),
    ): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(tempDir)
            .apply {
                environment().putAll(shellVariables)
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment().remove("GIT_ASKPASS")
                environment().remove("SSH_ASKPASS")
                environment().putAll(environment)
            }
            .start()

        process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0 || !requireSuccess) { "git ${args.joinToString(" ")} failed: $error" }

        return output
    }

    private data class Answer(val user: String, val password: String)

    /**
     * A git server over HTTP on this machine that takes the Basic credentials [accepts], and answers any other request
     * with a 401. With them, it advertises a branch (`info/refs`), and fails the fetch itself (`git-upload-pack`) with
     * a 500, so that the operation fails after a request succeeded with the credentials. [requests] has the method and
     * the status of each request.
     */
    private class FakeGitServer(private val accepts: Answer) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val port: Int get() = server.address.port
        val url: String get() = "http://127.0.0.1:$port/team/project.git"

        init {
            server.createContext("/") { exchange ->
                try {
                    answer(exchange)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        private fun answer(exchange: HttpExchange) {
            exchange.requestBody.readAllBytes()
            val credentials = Base64.getEncoder().encodeToString("${accepts.user}:${accepts.password}".toByteArray())

            val (status, body) = when {
                exchange.requestHeaders.getFirst("Authorization") != "Basic $credentials" -> {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"leaf\"")
                    401 to ""
                }

                exchange.requestURI.path.endsWith("/info/refs") -> {
                    exchange.responseHeaders.add("Content-Type", "application/x-git-upload-pack-advertisement")
                    val ref = "${"1".repeat(40)} refs/heads/main\u0000multi_ack_detailed side-band-64k\n"
                    200 to pktLine("# service=git-upload-pack\n") + "0000" + pktLine(ref) + "0000"
                }

                else -> 500 to ""
            }

            requests += "${exchange.requestMethod} $status"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())

            if (bytes.isNotEmpty()) {
                exchange.responseBody.write(bytes)
            }
        }

        override fun close() = server.stop(0)

        private fun pktLine(line: String) = "%04x".format(line.toByteArray().size + 4) + line
    }

    /** A helper process that has already exited, so writing its input fails as with a closed pipe. */
    private object ExitedProcess : Process() {
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
        }

        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor() = 127
        override fun exitValue() = 127
        override fun destroy() = Unit
    }

    private object NoCredentialsManager : IGitCredentialsManagerProvider {
        override fun loadPath(): String? = null
    }
}
