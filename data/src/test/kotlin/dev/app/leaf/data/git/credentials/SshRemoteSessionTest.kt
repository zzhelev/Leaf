// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import javax.inject.Provider

private const val SSHD = "/usr/sbin/sshd"
private const val SSH_AGENT = "/usr/bin/ssh-agent"
private const val SSH_ADD = "/usr/bin/ssh-add"
private const val SSH_KEYGEN = "/usr/bin/ssh-keygen"

/**
 * Leaf's SSH transport (JGit over libssh, through [SshRemoteSession]) against a local sshd. Its forced command acts like
 * a git host: it serves `allowed.git`, refuses other repositories with a message on stderr, as GitHub does for another
 * account's key, and exits with 127 for `missing-command.git`. It waits a moment before it refuses, as a real host
 * does while it checks permissions, so that the message comes after JGit has started reading stderr.
 *
 * Needs sshd, ssh-agent and Leaf's native library (`./gradlew :app:rustTasks` builds it), and is skipped without them.
 * libssh still reads the developer's `~/.ssh/config`, and offers the key from a temporary ssh-agent.
 */
@DisabledOnOs(OS.WINDOWS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SshRemoteSessionTest {
    @TempDir
    lateinit var tempDir: File

    // Not the @TempDir: the agent's socket path must stay under 104 bytes on macOS
    private val labDir: File = Files.createTempDirectory("leaf-ssh").toFile()
    private val processes = mutableListOf<Process>()
    private var port = 0

    private val passwordPrompts = mutableListOf<String>()
    private val originalReader: SystemReader = SystemReader.getInstance()

    private val credentialsStateManager = CredentialsStateManager()
    private val hostKeyPrompts = mutableListOf<CredentialsRequest.SshHostKeyRequest>()

    /** Whether the user trusts an unknown host key when Leaf asks. */
    @Volatile
    private var trustHostKeys = false

    /** The known_hosts file that Leaf uses in place of the developer's. Each test starts with the server's key in it. */
    private lateinit var knownHosts: File

    /** The server's host key as ssh shows it, from ssh-keygen. */
    private lateinit var hostKeyFingerprint: String

    @BeforeAll
    fun startServer() {
        val library = File("../app/src/main/resources", System.mapLibraryName("leaf_rs"))
        assumeTrue(library.isFile) { "Leaf's native library is missing, run ./gradlew :app:rustTasks" }
        assumeTrue(listOf(SSHD, SSH_AGENT, SSH_ADD, SSH_KEYGEN).all { File(it).canExecute() }) { "OpenSSH is missing" }
        System.setProperty("uniffi.component.leaf_rs.libraryOverride", library.absolutePath)
        SystemReader.setInstance(IsolatedSystemReader(File(labDir, "config"), originalReader))

        val git = runAndRead("/bin/sh", "-c", "command -v git").trim()
        TestGitCli(File(labDir, "empty.gitconfig")).run(labDir, "init", "--bare", "allowed.git")

        run(SSH_KEYGEN, "-q", "-t", "ed25519", "-N", "", "-f", File(labDir, "host_key").path)
        run(SSH_KEYGEN, "-q", "-t", "ed25519", "-N", "", "-f", File(labDir, "client_key").path)
        // "256 SHA256:... comment (ED25519)"
        hostKeyFingerprint = runAndRead(SSH_KEYGEN, "-l", "-E", "sha256", "-f", File(labDir, "host_key.pub").path)
            .split(" ")[1]

        val serve = File(labDir, "serve.sh")
        serve.writeText(
            """
            #!/bin/sh
            case "${'$'}SSH_ORIGINAL_COMMAND" in
              "git-upload-pack "*) command=upload-pack ;;
              "git-receive-pack "*) command=receive-pack ;;
              *) echo "unsupported command" >&2; exit 1 ;;
            esac
            repository=${'$'}{SSH_ORIGINAL_COMMAND#* }
            case "${'$'}repository" in
              "'/allowed.git'") exec "$git" "${'$'}command" "${labDir.absolutePath}/allowed.git" ;;
              "'/missing-command.git'") echo "sh: git-${'$'}command: command not found" >&2; exit 127 ;;
              *) sleep 0.2; echo "ERROR: Permission to ${'$'}repository denied to leaf-test." >&2; exit 1 ;;
            esac
            """.trimIndent() + "\n"
        )
        serve.setExecutable(true)

        val publicKey = File(labDir, "client_key.pub").readText().trim()
        File(labDir, "authorized_keys").writeText("command=\"${serve.absolutePath}\",no-pty,no-port-forwarding $publicKey\n")

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

        val sshdLog = File(labDir, "sshd.log")
        processes += ProcessBuilder(SSHD, "-D", "-e", "-f", config.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(sshdLog)
            .start()
        assumeTrue(waitUntil { canConnect(port) }) { "sshd didn't start: ${sshdLog.readText()}" }

        val agentSocket = File(labDir, "agent.sock")
        processes += ProcessBuilder(SSH_AGENT, "-D", "-a", agentSocket.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(File(labDir, "agent.log"))
            .start()
        check(waitUntil { agentSocket.exists() }) { "ssh-agent didn't start" }

        val add = ProcessBuilder(SSH_ADD, "-q", File(labDir, "client_key").path).redirectErrorStream(true)
        add.environment()["SSH_AUTH_SOCK"] = agentSocket.absolutePath
        val addProcess = add.start()
        val addOutput = addProcess.inputStream.readBytes().decodeToString()
        check(addProcess.waitFor() == 0) { "ssh-add failed: $addOutput" }

        // libssh finds the agent through the process's own environment, which Java can't change
        setNativeEnvironment("SSH_AUTH_SOCK", agentSocket.absolutePath)
    }

    @AfterAll
    fun stopServer() {
        SystemReader.setInstance(originalReader)
        setNativeEnvironment("SSH_AUTH_SOCK", System.getenv("SSH_AUTH_SOCK"))

        processes.forEach {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
        }

        labDir.deleteRecursively()
    }

    @BeforeEach
    fun resetPromptsAndKnownHosts() {
        passwordPrompts.clear()
        hostKeyPrompts.clear()
        trustHostKeys = false
        knownHosts = File(Files.createTempDirectory(tempDir.toPath(), "ssh").toFile(), "known_hosts")
        knownHosts.writeText(knownHostsLine(File(labDir, "host_key.pub")))
    }

    @Test
    fun `a push the server allows succeeds`() {
        assertEquals(listOf("OK"), push("allowed.git"))
        assertEquals(emptyList<String>(), passwordPrompts)
        assertEquals(emptyList<CredentialsRequest.SshHostKeyRequest>(), hostKeyPrompts)
    }

    @Test
    fun `a refused push shows the server's message`() {
        assertRefusalShown(errorMessages { push("team/denied.git") })
    }

    @Test
    fun `a refused fetch shows the server's message`() {
        assertRefusalShown(errorMessages { fetch("team/denied.git") })
    }

    @Test
    fun `a refused clone shows the server's message`() {
        val error = errorMessages {
            answeringHostKeyPrompts {
                Git.cloneRepository()
                    .setURI(url("team/denied.git"))
                    .setDirectory(File(tempDir, "clone"))
                    .setTransportConfigCallback(callback)
                    .call()
                    .close()
            }
        }

        assertRefusalShown(error)
    }

    @Test
    fun `a command the server can't run reports exit status 127`() {
        val error = errorMessages { fetch("missing-command.git") }

        // JGit's message for exit status 127, with the server's output as the cause
        assertTrue(error.contains("cannot execute: git-upload-pack '/missing-command.git'")) { error }
        assertTrue(error.contains("sh: git-upload-pack: command not found")) { error }
    }

    @Test
    fun `an unknown host key that the user trusts is added to known_hosts`() {
        knownHosts.writeText("")
        trustHostKeys = true

        assertEquals(listOf("OK"), push("allowed.git"))
        assertEquals(listOf(CredentialsRequest.SshHostKeyRequest(host, hostKeyFingerprint)), hostKeyPrompts)
        assertTrue(knownHosts.readText().startsWith("$host ssh-ed25519 ")) { knownHosts.readText() }

        // Known from then on
        fetch("allowed.git")
        assertEquals(1, hostKeyPrompts.size)
    }

    @Test
    fun `an unknown host key that the user doesn't trust stops the connection`() {
        knownHosts.delete()

        val error = errorMessages { fetch("allowed.git") }

        assertTrue(error.contains("Host key verification failed: the host key of $host wasn't trusted.")) { error }
        assertEquals(listOf(CredentialsRequest.SshHostKeyRequest(host, hostKeyFingerprint)), hostKeyPrompts)
        assertFalse(knownHosts.exists())
    }

    @Test
    fun `a changed host key is refused`() {
        knownHosts.writeText(knownHostsLine(newKey("ed25519")))

        val error = errorMessages { fetch("allowed.git") }

        assertTrue(error.contains("The host key of $host has changed, so Leaf didn't connect.")) { error }
        assertTrue(error.contains("Its key is now $hostKeyFingerprint.")) { error }
        assertEquals(emptyList<CredentialsRequest.SshHostKeyRequest>(), hostKeyPrompts)
    }

    @Test
    fun `a host key of another type than the known one is refused`() {
        knownHosts.writeText(knownHostsLine(newKey("ecdsa")))

        val error = errorMessages { fetch("allowed.git") }

        assertTrue(error.contains("$host sent a different type of host key")) { error }
        assertEquals(emptyList<CredentialsRequest.SshHostKeyRequest>(), hostKeyPrompts)
    }

    private val callback = TransportConfigCallback { transport ->
        transport as SshTransport
        transport.sshSessionFactory = GSshSessionFactory(
            Provider { SshRemoteSession(credentialsStateManager, knownHosts.absolutePath) }
        )
        transport.credentialsProvider = object : CredentialsProvider() {
            override fun isInteractive() = true
            override fun supports(vararg items: CredentialItem) = true
            override fun get(uri: URIish?, vararg items: CredentialItem): Boolean {
                passwordPrompts += "$uri"
                return false
            }
        }
    }

    /** The server as ssh names it in known_hosts and in its messages. */
    private val host get() = "[127.0.0.1]:$port"

    private fun knownHostsLine(publicKey: File) = "$host ${publicKey.readText().trim()}\n"

    /** A new key of [type], which isn't the server's, and returns its public key's file. */
    private fun newKey(type: String): File {
        val key = File(Files.createTempDirectory(tempDir.toPath(), "key").toFile(), "key")
        run(SSH_KEYGEN, "-q", "-t", type, "-N", "", "-f", key.path)

        return File("${key.path}.pub")
    }

    /** Pushes `main` to a new branch of [path] on the server, and returns the status of each ref. */
    private fun push(path: String) = answeringHostKeyPrompts {
        createRepository().use { git ->
            git.push()
                .setRemote(url(path))
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/test-${System.nanoTime()}"))
                .setTransportConfigCallback(callback)
                .call()
                .flatMap { it.remoteUpdates }
                .map { it.status.name }
        }
    }

    private fun fetch(path: String) = answeringHostKeyPrompts {
        createRepository().use { git ->
            git.fetch()
                .setRemote(url(path))
                .setRefSpecs(RefSpec("refs/heads/*:refs/remotes/origin/*"))
                .setTransportConfigCallback(callback)
                .call()
        }
    }

    /**
     * Runs [block], answering each host key question the way [trustHostKeys] says, as the user would in the dialog.
     */
    private fun <T> answeringHostKeyPrompts(block: () -> T): T = runBlocking {
        val responder = launch(Dispatchers.Default) {
            credentialsStateManager.credentialsState
                .filterIsInstance<CredentialsRequest.SshHostKeyRequest>()
                .collect { request ->
                    hostKeyPrompts += request

                    if (trustHostKeys) {
                        credentialsStateManager.sshHostKeyTrusted()
                    } else {
                        credentialsStateManager.credentialsDenied()
                    }
                }
        }

        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            responder.cancel()
        }
    }

    private fun url(path: String) = "ssh://${System.getProperty("user.name")}@127.0.0.1:$port/$path"

    private fun createRepository(): Git {
        val directory = Files.createTempDirectory(tempDir.toPath(), "work").toFile()
        val gitCli = TestGitCli(File(tempDir, "empty.gitconfig"))
        gitCli.run(directory, "init", "-q")
        gitCli.run(directory, "commit", "-q", "--allow-empty", "-m", "first")

        return Git.open(directory)
    }

    private fun assertRefusalShown(error: String) {
        assertTrue(error.contains("ERROR: Permission to '/team/denied.git' denied to leaf-test.")) { error }
        assertFalse(error.contains("Remote channel is closed")) { error }
        assertEquals(emptyList<String>(), passwordPrompts)
        assertEquals(emptyList<CredentialsRequest.SshHostKeyRequest>(), hostKeyPrompts)
    }

    /** The messages of the exception [block] throws and of its causes. */
    private fun errorMessages(block: () -> Unit): String {
        val exception = try {
            block()
            null
        } catch (e: Exception) {
            e
        }

        checkNotNull(exception) { "The operation succeeded" }

        return generateSequence<Throwable>(exception) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
    }

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

    /**
     * Sets or, with a null [value], removes a variable in the process's native environment, through JNA's libc
     * bindings. JNA is on the test classpath for the uniffi bindings, but not on the compile classpath.
     */
    private fun setNativeEnvironment(name: String, value: String?) {
        val libc = Class.forName("com.sun.jna.NativeLibrary")
            .getMethod("getInstance", String::class.java)
            .invoke(null, "c")
        val functionName = if (value == null) "unsetenv" else "setenv"
        val function = libc.javaClass.getMethod("getFunction", String::class.java).invoke(libc, functionName)
        val arguments: Array<Any> = if (value == null) arrayOf(name) else arrayOf(name, value, 1)
        val result = function.javaClass.getMethod("invokeInt", Array<Any>::class.java).invoke(function, arguments)

        check(result == 0) { "$functionName($name) failed" }
    }
}
