// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
    fun `credentials that Leaf asks for are stored with the helper`() {
        // Answers no get, so that Leaf asks, and then stores what it was given. The input file appears only once it's
        // complete, as Leaf doesn't wait for store to finish.
        File(tools, "git-credential-leaf-test").writeExecutable(
            """
            |#!/bin/sh
            |dir="${'$'}(dirname "${'$'}0")"
            |cat > "${'$'}dir/${'$'}1.tmp"
            |mv "${'$'}dir/${'$'}1.tmp" "${'$'}dir/${'$'}1.input"
            |""".trimMargin()
        )

        val answer = requestCredentials(helper = "leaf-test")

        assertEquals(Answer("prompted-user", "prompted-password"), answer)
        assertEquals(
            "${EXPECTED_INPUT}username=prompted-user\npassword=prompted-password\n",
            awaitFile(File(tools, "store.input")).readText(),
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
        assertEquals(Answer("prompted-user", "prompted-password"), requestCredentials(helper = "store"))
        assertEquals(
            "https://prompted-user:prompted-password@example.invalid\n",
            awaitFile(File(tempDir, ".git-credentials")).readText(),
        )

        val answer = requestCredentials(helper = "store", promptAnswer = Answer("asked-again", "asked-again"))

        assertEquals(Answer("prompted-user", "prompted-password"), answer)
    }

    @Test
    fun `cache keeps the credentials that Leaf asks for, and gives them back next time`() {
        try {
            assertEquals(Answer("prompted-user", "prompted-password"), requestCredentials(helper = "cache"))
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

        val answer = provider.requestCredentials(promptAnswer = Answer("new-user", "new-password"))

        assertEquals(Answer("new-user", "new-password"), answer)
        assertEquals("https://new-user:new-password@example.invalid\n", awaitText(credentialsFile))
    }

    @Test
    fun `credentials that Leaf asked for are erased when the server rejects them, once the helper has stored them`() {
        // Leaf stores them before the server answers and doesn't wait, so the erase has to wait for the slow store
        createRecordingHelper(answer = null, storeSeconds = "0.5")
        val provider = createProvider(helper = "leaf-test")

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials())
        provider.reset(URIish(REMOTE_URL))

        assertEquals(listOf("get", "store", "erase"), File(tools, "operations").readLines())
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

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials(url = PORT_URL))
        awaitFile(File(tempDir, ".git-credentials"))

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
        File(shellVariables.getValue("GIT_CONFIG_GLOBAL")).writeText("[credential]\n\thelper = leaf-test\n")
        val provider = createProvider(helper = null, inRepository = false)

        assertEquals(Answer("helper-user", "helper-password"), provider.requestCredentials())
    }

    @Test
    fun `typed credentials are stored with every helper, and erased from every helper when the server rejects them`() {
        createRecordingHelper(answer = null)
        val credentialsFile = File(tempDir, ".git-credentials")
        val provider = createProvider(helper = null) {
            setStringList("credential", null, "helper", listOf("store", "leaf-test"))
        }

        assertEquals(Answer("prompted-user", "prompted-password"), provider.requestCredentials())
        assertEquals("https://prompted-user:prompted-password@example.invalid\n", awaitText(awaitFile(credentialsFile)))
        provider.reset(URIish(REMOTE_URL))

        assertEquals("", credentialsFile.readText())
        assertEquals(listOf("get", "store", "erase"), File(tools, "operations").readLines())
        assertEquals(
            "${EXPECTED_INPUT}username=prompted-user\npassword=prompted-password\n",
            File(tools, "erase.input").readText(),
        )
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
     * there is one, and `store` takes [storeSeconds].
     */
    private fun createRecordingHelper(answer: Answer?, storeSeconds: String = "0") {
        val answerGet = answer?.let { "printf 'username=${it.user}\\npassword=${it.password}\\n'" } ?: ":"

        File(tools, "git-credential-leaf-test").writeExecutable(
            """
            |#!/bin/sh
            |dir="${'$'}(dirname "${'$'}0")"
            |operation="${'$'}1"
            |cat > "${'$'}dir/${'$'}operation.input"
            |[ "${'$'}operation" = store ] && sleep $storeSeconds
            |echo "${'$'}operation" >> "${'$'}dir/operations"
            |[ "${'$'}operation" = get ] && $answerGet
            |exit 0
            |""".trimMargin()
        )
    }

    /**
     * Asks a new [HttpCredentialsProvider] for the credentials of [REMOTE_URL], with [helper] as `credential.helper`.
     * If Leaf asks the user instead, the answer is [promptAnswer].
     */
    private fun requestCredentials(
        helper: String,
        shellVariables: Map<String, String> = this.shellVariables,
        gitCredentialsManagerProvider: IGitCredentialsManagerProvider = NoCredentialsManager,
        shellManager: IShellManager = ShellManager(),
        promptAnswer: Answer = Answer("prompted-user", "prompted-password"),
    ): Answer? = createProvider(helper, shellVariables, gitCredentialsManagerProvider, shellManager)
        .requestCredentials(promptAnswer)

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
     * Leaf asks the user, the answer is [promptAnswer].
     */
    private fun HttpCredentialsProvider.requestCredentials(
        promptAnswer: Answer = Answer("prompted-user", "prompted-password"),
        url: String = REMOTE_URL,
    ): Answer? = runBlocking {
        val user = CredentialItem.Username()
        val password = CredentialItem.Password()

        val prompt = launch(Dispatchers.Default) {
            credentialsStateManager.credentialsState.first { it == CredentialsRequest.HttpCredentialsRequest }
            credentialsStateManager.httpCredentialsAccepted(promptAnswer.user, promptAnswer.password)
        }

        val accepted = withContext(Dispatchers.IO) { get(URIish(url), user, password) }
        prompt.cancel()

        if (accepted) Answer(user.value, String(password.value)) else null
    }

    /** Puts [credentials] for [REMOTE_URL] in Leaf's in-memory cache, as a successful operation does. */
    private fun cacheInMemory(credentials: Answer) = runBlocking {
        credentialsCache.cacheHttpCredentials(REMOTE_URL, credentials.user, credentials.password, isLfs = false)
    }

    /** The credentials for [REMOTE_URL] in Leaf's in-memory cache. */
    private fun cachedInMemory(): Answer? = credentialsCache.getCachedHttpCredentials(REMOTE_URL, isLfs = false)
        ?.let { Answer(it.user, it.password) }

    /** Waits for [file], which a helper writes after Leaf has moved on, as Leaf doesn't wait for `store` to finish. */
    private fun awaitFile(file: File): File = runBlocking {
        withTimeout(10_000) {
            while (!file.exists()) {
                delay(20)
            }
        }

        file
    }

    /** Waits until [file] has some text, which a helper writes after Leaf has moved on, and returns it. */
    private fun awaitText(file: File): String = runBlocking {
        withTimeout(10_000) {
            while (file.readText().isEmpty()) {
                delay(20)
            }
        }

        file.readText()
    }

    /** Waits until git's cache daemon has the credentials, which Leaf stores after it has moved on. */
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
     * Runs git with [args] and [input] outside any repository, ignoring the developer's git config and never prompting,
     * and returns its output.
     */
    private fun runGit(args: List<String>, input: String, requireSuccess: Boolean = true): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(tempDir)
            .apply {
                environment().putAll(shellVariables)
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment().remove("GIT_ASKPASS")
                environment().remove("SSH_ASKPASS")
            }
            .start()

        process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0 || !requireSuccess) { "git ${args.joinToString(" ")} failed: $error" }

        return output
    }

    private data class Answer(val user: String, val password: String)

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
