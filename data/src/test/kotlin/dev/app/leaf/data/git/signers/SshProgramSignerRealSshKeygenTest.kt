// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.tags.CreateTagGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.SshSigningError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private const val EMAIL = "leaf@example.com"
private const val PASSPHRASE = "correct horse"

/**
 * Signs with the real ssh-keygen, when it's installed, and verifies with the git CLI. Keys are made for each test, and
 * neither the developer's agent nor their `~/.ssh` is used: `SSH_AUTH_SOCK` points to a socket that doesn't exist, or
 * to an ssh-agent started for the test.
 */
@DisabledOnOs(OS.WINDOWS)
class SshProgramSignerRealSshKeygenTest {
    @TempDir
    lateinit var tempDir: File

    private val sshKeygen: String? = locateProgram("ssh-keygen", currentOs, System.getenv("PATH"))
    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val credentialsStateManager = CredentialsStateManager()
    private var agent: Process? = null

    // A Unix socket's path can't be longer than 104 bytes on macOS, which a JUnit temp folder may be
    private val socketDirectory: File = Files.createTempDirectory("leaf-ssh-sign").toFile()

    private val environment by lazy {
        mutableMapOf("HOME" to File(tempDir, "home").path, "SSH_AUTH_SOCK" to File(socketDirectory, "none").path)
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(sshKeygen != null, "ssh-keygen is not installed")
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
        agent?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        FileUtils.delete(socketDirectory, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
    }

    @Test
    fun `signs a commit that git verifies`(): Unit = runBlocking {
        val key = createKey("id_ed25519")
        val repository = createRepository(signingKey = key.path)

        val result = commit(repository, signer())

        assertTrue(result is Either.Ok, "$result")
        assertGoodSignature(git.run(repository, environment, "verify-commit", "HEAD"))
    }

    @Test
    fun `signs a commit exactly as git commit -S does`(): Unit = runBlocking {
        val key = createKey("id_ed25519")
        val repository = createRepository(signingKey = key.path)
        val date = Instant.ofEpochSecond(1_790_000_000)
        val gitDate = mapOf("GIT_AUTHOR_DATE" to "1790000000 +0000", "GIT_COMMITTER_DATE" to "1790000000 +0000")

        git.run(repository, environment + gitDate, "commit", "-S", "--allow-empty", "-m", "Signed")
        val signedByGit = git.run(repository, "cat-file", "commit", "HEAD")
        git.run(repository, "reset", "--soft", "HEAD~1")

        // ED25519 signatures are deterministic, so the same commit signed the same way is the same object. TestGitCli
        // sets git's identity on the command line, and git ends the message with a line break
        val identity = PersonIdent("Leaf Test", "test@example.invalid", date, ZoneOffset.UTC)
        val result = commit(repository, signer(), identity, message = "Signed\n")

        assertTrue(result is Either.Ok, "$result")
        assertEquals(signedByGit, git.run(repository, "cat-file", "commit", "HEAD"))
    }

    @Test
    fun `signs a tag that git verifies`(): Unit = runBlocking {
        val key = createKey("id_ed25519")
        val repository = createRepository(signingKey = key.path)
        git.run(repository, "config", "tag.gpgSign", "true")
        val head = git.run(repository, "rev-parse", "HEAD").trim()
        val identity = Identity("Leaf Test", EMAIL)

        Signers.set(GpgConfig.GpgFormat.SSH, signer())
        val result = try {
            CreateTagGitAction(testJGit())(
                File(repository, ".git").path,
                "v1",
                Commit(head, "Initial commit", identity, identity, 0, emptyList()),
            )
        } finally {
            Signers.set(GpgConfig.GpgFormat.SSH, null)
        }

        assertTrue(result is Either.Ok, "$result")
        assertGoodSignature(git.run(repository, environment, "verify-tag", "v1"))
    }

    @Test
    fun `signs with a public key file, whose private key ssh-keygen finds next to it`(): Unit = runBlocking {
        val key = createKey("id_ed25519")
        val repository = createRepository(signingKey = "${key.path}.pub")

        val result = commit(repository, signer())

        assertTrue(result is Either.Ok, "$result")
        assertGoodSignature(git.run(repository, environment, "verify-commit", "HEAD"))
    }

    @Test
    fun `signs with a key that only ssh-agent has, named with key prefix`(): Unit = runBlocking {
        val key = createKey("id_ed25519")
        startAgent()
        run("ssh-add", key.path)
        val publicKey = File("${key.path}.pub").readText().trim()
        FileUtils.delete(key)
        val repository = createRepository(signingKey = "key::$publicKey")

        val result = commit(repository, signer())

        assertTrue(result is Either.Ok, "$result")
        assertGoodSignature(git.run(repository, environment, "verify-commit", "HEAD"))
    }

    @Test
    fun `asks for an encrypted key's passphrase once, then keeps it for the session`(): Unit = runBlocking {
        val helper = askpassHelper()
        val key = createKey("id_ed25519", PASSPHRASE)
        val repository = createRepository(signingKey = key.path)
        val signer = signer(helper)

        val (results, dialogs) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted(PASSPHRASE) }) {
            listOf(commit(repository, signer, message = "First"), commit(repository, signer, message = "Second"))
        }

        assertTrue(results.all { it is Either.Ok }, "$results")
        assertEquals(listOf(CredentialsRequest.SshCredentialsRequest(isRetry = false, password = "")), dialogs)
        assertGoodSignature(git.run(repository, environment, "verify-commit", "HEAD"))
    }

    @Test
    fun `asks again after a wrong passphrase`(): Unit = runBlocking {
        val helper = askpassHelper()
        val key = createKey("id_ed25519", PASSPHRASE)
        val repository = createRepository(signingKey = key.path)

        val (result, dialogs) = credentialsStateManager.answeringDialogs(
            { sshCredentialsAccepted("wrong") },
            { sshCredentialsAccepted(PASSPHRASE) },
        ) { commit(repository, signer(helper)) }

        assertTrue(result is Either.Ok, "$result")
        assertEquals(
            listOf(
                CredentialsRequest.SshCredentialsRequest(isRetry = false, password = ""),
                CredentialsRequest.SshCredentialsRequest(isRetry = true, password = ""),
            ),
            dialogs,
        )
    }

    @Test
    fun `closing the passphrase dialog cancels signing, and nothing is committed`(): Unit = runBlocking {
        val helper = askpassHelper()
        val key = createKey("id_ed25519", PASSPHRASE)
        val repository = createRepository(signingKey = key.path)

        val (result, dialogs) = credentialsStateManager.answeringDialogs { commit(repository, signer(helper)) }

        assertEquals(Either.Err(SshSigningError.Cancelled), result)
        assertEquals(1, dialogs.size)
        assertEquals("Initial commit\n", git.run(repository, "log", "-1", "--format=%s"))
    }

    private suspend fun commit(
        repository: File,
        signer: SshProgramSigner,
        identity: PersonIdent = PersonIdent("Leaf Test", EMAIL),
        message: String = "Signed",
    ) = testJGit().provide(File(repository, ".git").path) { git ->
        git.commit()
            .setMessage(message)
            .setAllowEmpty(true)
            .setAuthor(identity)
            .setCommitter(identity)
            .setSign(true)
            .setSigner(signer)
            .call()
    }

    /** A repository that signs with [signingKey], and whose allowed signers file lets git verify the test's keys. */
    private fun createRepository(signingKey: String): File {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "user.name", "Leaf Test")
        git.run(repository, "config", "user.email", EMAIL)
        git.run(repository, "config", "gpg.format", "ssh")
        git.run(repository, "config", "user.signingKey", signingKey)
        git.run(repository, "config", "gpg.ssh.allowedSignersFile", File(tempDir, "allowed_signers").path)

        return repository
    }

    private fun createKey(name: String, passphrase: String = ""): File {
        val key = File(tempDir, "keys/$name")
        key.parentFile.mkdirs()
        run(sshKeygen!!, "-q", "-t", "ed25519", "-N", passphrase, "-C", EMAIL, "-f", key.path)

        val publicKey = File("${key.path}.pub").readText().trim()
        File(tempDir, "allowed_signers").appendText("$EMAIL namespaces=\"git\" $publicKey\n")

        return key
    }

    private fun startAgent() {
        val socket = File(socketDirectory, "agent")
        agent = ProcessBuilder("ssh-agent", "-D", "-a", socket.path)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        val deadline = System.currentTimeMillis() + 5_000
        while (!socket.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assumeTrue(socket.exists(), "ssh-agent didn't start")
        environment["SSH_AUTH_SOCK"] = socket.path
    }

    private fun askpassHelper(): File {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }

        return helper!!
    }

    private fun signer(helper: File? = null): SshProgramSigner {
        val processRunner = ProcessRunner()
        val askpass = AskpassProcessRunner(
            processRunner,
            AskpassHelper { helper },
            credentialsStateManager,
            CredentialsCacheRepository(),
        )

        return SshProgramSigner(askpass, processRunner, LoginShellEnvironment { environment })
    }

    private fun assertGoodSignature(output: String) {
        assertTrue(output.contains("Good \"git\" signature for $EMAIL with ED25519 key"), output)
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
    }
}
