// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.models.CredentialsType
import dev.app.leaf.domain.repositories.CredentialsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/** Credential helpers that [HttpCredentialsProvider] starts on macOS and Linux, for HTTPS remotes. */
@DisabledOnOs(OS.WINDOWS)
class HttpCredentialsProviderTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val credentialsStateManager = CredentialsStateManager()

    /** Holds the helpers and the tools they run, on no PATH unless a test puts it there, like Homebrew's folder. */
    private val tools by lazy { File(tempDir, "tools").apply { mkdirs() } }

    /** What the login shell adds: its PATH includes [tools], and git ignores the developer's own config. */
    private val shellVariables by lazy {
        mapOf(
            "PATH" to "${tools.absolutePath}:${System.getenv("PATH")}",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").apply { createNewFile() }.absolutePath,
        )
    }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
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
     * Asks [HttpCredentialsProvider] for the credentials of [REMOTE_URL], with [helper] as `credential.helper`. If
     * Leaf asks the user instead, the answer is `prompted-user` and `prompted-password`.
     */
    private fun requestCredentials(
        helper: String,
        shellVariables: Map<String, String> = this.shellVariables,
        gitCredentialsManagerProvider: IGitCredentialsManagerProvider = NoCredentialsManager,
        shellManager: IShellManager = ShellManager(),
    ): Answer = runBlocking {
        Git.init().setDirectory(File(tempDir, "repository")).call().use { git ->
            git.repository.config.apply {
                setString("credential", null, "helper", helper)
                save()
            }

            val provider = HttpCredentialsProvider(
                credentialsStateManager = credentialsStateManager,
                shellManager = shellManager,
                credentialsCacheRepository = NoCachedCredentials,
                gitCredentialsManagerProvider = gitCredentialsManagerProvider,
                loginShellEnvironment = LoginShellEnvironment { shellVariables },
                git = git,
            )
            val user = CredentialItem.Username()
            val password = CredentialItem.Password()

            val prompt = launch(Dispatchers.Default) {
                credentialsStateManager.credentialsState.first { it == CredentialsRequest.HttpCredentialsRequest }
                credentialsStateManager.httpCredentialsAccepted("prompted-user", "prompted-password")
            }

            val accepted = withContext(Dispatchers.IO) { provider.get(URIish(REMOTE_URL), user, password) }
            prompt.cancel()

            check(accepted) { "The provider gave no credentials" }
            Answer(user.value, String(password.value))
        }
    }

    /** Waits for [file], which a helper writes after Leaf has moved on, as Leaf doesn't wait for `store` to finish. */
    private fun awaitFile(file: File): File = runBlocking {
        withTimeout(10_000) {
            while (!file.exists()) {
                delay(20)
            }
        }

        file
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

    private object NoCachedCredentials : CredentialsRepository {
        override fun getCachedHttpCredentials(url: String, isLfs: Boolean): CredentialsType.HttpCredentials? = null
        override fun getCachedSshCredentials(url: String): CredentialsType.SshCredentials? = null
        override suspend fun cacheHttpCredentials(credentials: CredentialsType.HttpCredentials) = Unit
        override suspend fun cacheHttpCredentials(url: String, userName: String, password: String, isLfs: Boolean) =
            Unit

        override suspend fun cacheSshCredentials(url: String, password: String) = Unit
    }
}
