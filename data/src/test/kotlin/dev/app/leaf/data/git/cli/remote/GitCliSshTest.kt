// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private const val SSHD = "/usr/sbin/sshd"
private const val SSH_KEYGEN = "/usr/bin/ssh-keygen"
private const val PASSPHRASE = "correct horse"

/**
 * Pushes and clones over SSH with the git CLI and the system's ssh, to a local sshd whose keys act like accounts of a git host:
 * `alice` and `dave` (whose key has a passphrase) may push, `bob` is refused with a message on stderr, as GitHub does
 * for another account's key. ssh asks through the askpass helper, and Leaf answers with its dialogs.
 *
 * The repository's `core.sshCommand` keeps ssh away from the developer's config, keys, agent and known_hosts. Skipped
 * without OpenSSH or the askpass helper.
 */
@DisabledOnOs(OS.WINDOWS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitCliSshTest {
    @TempDir
    lateinit var tempDir: File

    private val labDir: File = Files.createTempDirectory("leaf-cli-ssh").toFile()
    private var sshd: Process? = null
    private var port = 0
    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var helper: File
    private lateinit var git: TestGitCli
    private lateinit var globalConfig: File
    private lateinit var work: File
    private lateinit var knownHosts: File
    private lateinit var remote: TestRemoteCommand

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeAll
    fun startServer() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        assumeTrue(File(SSHD).canExecute() && File(SSH_KEYGEN).canExecute()) { "OpenSSH is missing" }
        helper = built!!

        val gitPath = runAndRead("/bin/sh", "-c", "command -v git").trim()

        keygen("host_key")
        keygen("alice")
        keygen("bob")
        keygen("dave", PASSPHRASE)

        val serve = File(labDir, "serve.sh")
        serve.writeText(
            """
            #!/bin/sh
            account=${'$'}1
            case "${'$'}SSH_ORIGINAL_COMMAND" in
              "git-upload-pack "*) command=upload-pack ;;
              "git-receive-pack "*) command=receive-pack ;;
              *) echo "unsupported command" >&2; exit 1 ;;
            esac
            # "git-receive-pack '/name.git'" serves <labDir>/name.git
            repository=${'$'}{SSH_ORIGINAL_COMMAND#* }
            repository=${'$'}{repository#\'/}
            repository=${'$'}{repository%\'}
            if [ "${'$'}account" = bob ]; then
              sleep 0.2; echo "ERROR: Permission to team/${'$'}repository denied to bob." >&2; exit 1
            fi
            exec "$gitPath" "${'$'}command" "${labDir.absolutePath}/${'$'}repository"
            """.trimIndent() + "\n"
        )
        serve.setExecutable(true)

        File(labDir, "authorized_keys").writeText(
            listOf("alice", "bob", "dave").joinToString("") { account ->
                val key = File(labDir, "$account.pub").readText().trim()
                "command=\"${serve.absolutePath} $account\",no-pty,no-port-forwarding $key\n"
            }
        )

        port = ServerSocket(0).use { it.localPort }
        val config = File(labDir, "sshd_config")
        config.writeText(
            """
            Port $port
            ListenAddress 127.0.0.1
            HostKey ${labDir.absolutePath}/host_key
            PidFile ${labDir.absolutePath}/sshd.pid
            AuthorizedKeysFile ${labDir.absolutePath}/authorized_keys
            StrictModes no
            UsePAM no
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            PubkeyAuthentication yes
            """.trimIndent() + "\n"
        )

        val log = File(labDir, "sshd.log")
        sshd = ProcessBuilder(SSHD, "-D", "-e", "-f", config.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        assumeTrue(waitUntil { canConnect(port) }) { "sshd didn't start: ${log.readText()}" }
    }

    @AfterAll
    fun stopServer() {
        sshd?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        labDir.deleteRecursively()
    }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper, globalConfig)

        knownHosts = File(tempDir, "known_hosts")
        knownHosts.writeText(knownHostsLine(File(labDir, "host_key.pub")))

        // Each test pushes to a repository of its own on the server
        val repository = "${tempDir.name}.git"
        git.run(labDir, "init", "--bare", repository)

        work = git.initRepository(File(tempDir, "work"))
        git.run(work, "remote", "add", "origin", remoteUrl())
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `an unknown host key is trusted through Leaf's dialog and added to known_hosts`(): Unit = runBlocking {
        knownHosts.writeText("")
        useKey("alice")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs({ sshHostKeyTrusted() }) { push() }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(listOf(CredentialsRequest.SshHostKeyRequest("[127.0.0.1]:$port", hostKeyFingerprint())), dialogs)
        assertTrue(knownHosts.readText().contains(File(labDir, "host_key.pub").readText().split(" ")[1]))
    }

    @Test
    fun `an unknown host key that the user doesn't trust stops the push`(): Unit = runBlocking {
        knownHosts.writeText("")
        useKey("alice")

        val (result, _) = remote.credentialsStateManager.answeringDialogs({ credentialsDenied() }) { push() }

        val error = (result as Either.Err).error
        assertInstanceOf(RemoteOperationError.PromptRefused::class.java, error) { "$result" }
        assertTrue((error as RemoteOperationError).output.contains("Host key verification failed.")) { error.output }
        assertEquals("", knownHosts.readText())
    }

    @Test
    fun `a changed host key is refused without asking`(): Unit = runBlocking {
        val otherKey = File(tempDir, "other_host_key")
        run(SSH_KEYGEN, "-q", "-t", "ed25519", "-N", "", "-f", otherKey.path)
        knownHosts.writeText(knownHostsLine(File("${otherKey.path}.pub")))
        useKey("alice")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs { push() }

        assertInstanceOf(RemoteOperationError.HostKeyChanged::class.java, (result as Either.Err).error) { "$result" }
        assertEquals(emptyList<CredentialsRequest>(), dialogs)
    }

    @Test
    fun `another account's key shows the server's message`(): Unit = runBlocking {
        useKey("bob")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs { push() }
        val error = (result as Either.Err).error

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertTrue((error as RemoteOperationError).output.contains("ERROR: Permission to team/${tempDir.name}.git denied to bob."))
        assertEquals(emptyList<CredentialsRequest>(), dialogs)
    }

    @Test
    fun `a key's passphrase is asked for once, then kept for the session`(): Unit = runBlocking {
        useKey("dave")
        val passphrase: CredentialsStateManager.(CredentialsRequest) -> Unit = { sshCredentialsAccepted(PASSPHRASE) }

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs(passphrase) { push() }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(listOf(CredentialsRequest.SshCredentialsRequest(isRetry = false, password = "")), dialogs)

        commit("second")
        val (again, dialogsAgain) = remote.credentialsStateManager.answeringDialogs { push() }

        assertEquals(Either.Ok(Unit), again)
        assertEquals(emptyList<CredentialsRequest>(), dialogsAgain)
    }

    @Test
    fun `a wrong passphrase is asked for again`(): Unit = runBlocking {
        useKey("dave")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs(
            { sshCredentialsAccepted("wrong") },
            { sshCredentialsAccepted(PASSPHRASE) },
        ) { push() }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(
            listOf(
                CredentialsRequest.SshCredentialsRequest(isRetry = false, password = ""),
                CredentialsRequest.SshCredentialsRequest(isRetry = true, password = ""),
            ),
            dialogs,
        )
    }

    @Test
    fun `a clone over SSH asks about an unknown host key through Leaf's dialog`(): Unit = runBlocking {
        useKey("alice")
        assertEquals(Either.Ok(Unit), push())
        knownHosts.writeText("")
        // There is no repository yet, whose config could hold it
        git.run(tempDir, "config", "--file", globalConfig.path, "core.sshCommand", sshCommand("alice"))
        val destination = File(tempDir, "clone")

        val (states, dialogs) = remote.credentialsStateManager.answeringDialogs({ sshHostKeyTrusted() }) {
            GitCliCloneRepositoryGitAction(jgit, remote.command)(destination, remoteUrl(), cloneSubmodules = false)
                .toList()
        }

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals(listOf(CredentialsRequest.SshHostKeyRequest("[127.0.0.1]:$port", hostKeyFingerprint())), dialogs)
        assertEquals("Test repository\n", File(destination, "README.md").readText())
    }

    /** Makes the repository's ssh use only [account]'s key, the test's known_hosts, and no config or agent. */
    private fun useKey(account: String) {
        git.run(work, "config", "core.sshCommand", sshCommand(account))
    }

    private fun sshCommand(account: String) = listOf(
            "ssh", "-F", "/dev/null",
            "-o", "UserKnownHostsFile=${knownHosts.absolutePath}",
            "-o", "GlobalKnownHostsFile=/dev/null",
            "-o", "IdentityAgent=none",
            "-o", "IdentitiesOnly=yes",
            "-i", File(labDir, account).absolutePath,
        ).joinToString(" ")

    /** The test's own repository on the server. */
    private fun remoteUrl() = "ssh://${System.getProperty("user.name")}@127.0.0.1:$port/${tempDir.name}.git"

    private suspend fun push(): Either<Unit, GitError> =
        GitCliPushBranchGitAction(jgit, GetTrackingBranchGitAction(jgit), remote.command)(
            gitDir,
            force = false,
            pushTags = false,
            pushWithLease = true,
            specificBranch = null,
        )

    private fun commit(message: String) {
        File(work, "file.txt").appendText("$message\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", message)
    }

    private fun knownHostsLine(publicKey: File) = "[127.0.0.1]:$port ${publicKey.readText().trim()}\n"

    /** "256 SHA256:... comment (ED25519)" from ssh-keygen, as ssh shows it. */
    private fun hostKeyFingerprint() =
        runAndRead(SSH_KEYGEN, "-l", "-E", "sha256", "-f", File(labDir, "host_key.pub").path).split(" ")[1]

    private fun keygen(name: String, passphrase: String = "") =
        run(SSH_KEYGEN, "-q", "-t", "ed25519", "-N", passphrase, "-f", File(labDir, name).path)

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
    }

    private fun runAndRead(vararg command: String): String {
        val process = ProcessBuilder(*command).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed" }

        return output
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }

            Thread.sleep(50)
        }

        return false
    }

    private fun canConnect(port: Int) = try {
        Socket("127.0.0.1", port).close()
        true
    } catch (e: Exception) {
        false
    }
}
