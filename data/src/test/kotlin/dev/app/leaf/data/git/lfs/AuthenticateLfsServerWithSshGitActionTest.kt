// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.lfs.LfsSshAuthenticateResult
import dev.app.leaf.domain.models.OperationType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.errors.LfsException
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val ANSWER = """{"href": "https://lfs.example.com/org/repo", "header": {"Authorization": "RemoteAuth abc"}}"""

/**
 * A fake ssh, which records its arguments, warns on stderr as ssh does when it adds a host key, and answers
 * `git-lfs-authenticate` with [ANSWER].
 */
private val ANSWERING_SSH = """
    for argument in "${'$'}@"; do echo "${'$'}argument"; done > "${'$'}(dirname "${'$'}0")/args"
    echo "Warning: Permanently added '[example.com]:2222' (ED25519) to the list of known hosts." >&2
    echo '$ANSWER'
""".trimIndent()

/** What the sshd harness of `GitCliSshTest` sends for another account's key, as GitHub does. */
private val REFUSING_SSH = """
    echo 'ERROR: Permission to org/repo.git denied to bob.' >&2
    exit 1
""".trimIndent()

/**
 * How Leaf's built-in LFS client asks an SSH remote for its LFS server: the command git-lfs runs, with the ssh that
 * git-lfs picks. Fake ssh programs stand in for the server here; `GitCliSshTest` asks a real sshd.
 */
@DisabledOnOs(OS.WINDOWS)
class AuthenticateLfsServerWithSshGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var git: TestGitCli
    private lateinit var globalConfig: File
    private lateinit var work: File

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        globalConfig = File(tempDir, "global.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        work = git.initRepository(File(tempDir, "work"))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `runs the command that git-lfs runs`() {
        // As git-lfs 3.8 ran ssh for these remotes, captured with a GIT_SSH_COMMAND that logs its arguments
        assertEquals(
            listOf("git@example.com", "git-lfs-authenticate org/repo.git download"),
            lfsAuthenticateArguments(URIish("git@example.com:org/repo.git"), "download"),
        )
        assertEquals(
            listOf("-p", "2222", "git@example.com", "git-lfs-authenticate /org/repo.git upload"),
            lfsAuthenticateArguments(URIish("ssh://git@example.com:2222/org/repo.git"), "upload"),
        )
        assertEquals(
            listOf("example.com", "git-lfs-authenticate /~/re po download"),
            lfsAuthenticateArguments(URIish("ssh://example.com/~/re po"), "download"),
        )
    }

    @Test
    fun `refuses a host or a user that ssh would take as an option`() {
        assertThrows(LfsException::class.java) {
            lfsAuthenticateArguments(URIish("ssh://-oProxyCommand=touch%20pwned@example.com/repo.git"), "download")
        }
    }

    @Test
    fun `picks ssh in git's order`() {
        val variables = mapOf("GIT_SSH_COMMAND" to "ssh -v", "GIT_SSH" to "/opt/plink")

        assertEquals(SshProgram.ShellCommand("ssh -v"), sshProgram(variables::get, coreSshCommand = "ssh -i key"))
        assertEquals(
            SshProgram.ShellCommand("ssh -i key"),
            sshProgram((variables - "GIT_SSH_COMMAND")::get, coreSshCommand = "ssh -i key"),
        )
        assertEquals(SshProgram.Program("/opt/plink"), sshProgram((variables - "GIT_SSH_COMMAND")::get, null))
        assertEquals(SshProgram.Program("ssh"), sshProgram(emptyMap<String, String>()::get, null))
    }

    @Test
    fun `runs core sshCommand with the shell, the arguments after it, and reads its answer`(): Unit = runBlocking {
        val ssh = File(tempDir, "bin/fake-ssh").writeExecutable("#!/bin/sh\n$ANSWERING_SSH\n")
        git.run(work, "config", "core.sshCommand", "${ssh.path} -o 'IdentityFile=/keys/my key'")

        val result = authenticate("ssh://git@example.com:2222/org/repo.git", OperationType.UPLOAD)

        assertEquals(LfsSshAuthenticateResult("https://lfs.example.com/org/repo", mapOf("Authorization" to "RemoteAuth abc")), result)
        val expected = listOf(
            "-o", "IdentityFile=/keys/my key",
            "-p", "2222", "git@example.com", "git-lfs-authenticate /org/repo.git upload",
        )
        assertEquals(expected, recordedArguments(ssh))
    }

    @Test
    fun `reads core sshCommand with git, so that includeIf applies`(): Unit = runBlocking {
        val ssh = File(tempDir, "bin/fake-ssh").writeExecutable("#!/bin/sh\n$ANSWERING_SSH\n")
        val included = File(tempDir, "work.gitconfig")
        included.writeText("[core]\n\tsshCommand = ${ssh.path}\n")
        // git compares the real path, /private/var/... on macOS
        globalConfig.writeText("[includeIf \"gitdir:${work.canonicalPath}/\"]\n\tpath = ${included.path}\n")

        authenticate("git@example.com:org/repo.git", OperationType.DOWNLOAD)

        assertEquals(listOf("git@example.com", "git-lfs-authenticate org/repo.git download"), recordedArguments(ssh))
    }

    @Test
    fun `GIT_SSH_COMMAND wins over core sshCommand, and GIT_SSH runs without a shell`(): Unit = runBlocking {
        val ssh = File(tempDir, "bin/fake-ssh").writeExecutable("#!/bin/sh\n$ANSWERING_SSH\n")
        git.run(work, "config", "core.sshCommand", "/nonexistent/ssh")

        authenticate("git@example.com:org/repo.git", OperationType.DOWNLOAD, mapOf("GIT_SSH_COMMAND" to "${ssh.path} -v"))
        assertEquals("-v", recordedArguments(ssh).first())

        git.run(work, "config", "--unset", "core.sshCommand")
        authenticate("git@example.com:org/repo.git", OperationType.DOWNLOAD, mapOf("GIT_SSH" to ssh.path))
        assertEquals("git@example.com", recordedArguments(ssh).first())
    }

    @Test
    fun `fails with the server's message`() {
        val ssh = File(tempDir, "bin/fake-ssh").writeExecutable("#!/bin/sh\n$REFUSING_SSH\n")
        git.run(work, "config", "core.sshCommand", ssh.path)

        val exception = assertThrows(LfsException::class.java) {
            runBlocking { authenticate("git@example.com:org/repo.git", OperationType.DOWNLOAD) }
        }

        assertTrue(exception.message!!.contains("ERROR: Permission to org/repo.git denied to bob."), exception.message)
    }

    @Test
    fun `fails when the answer isn't what git-lfs-authenticate gives`() {
        val ssh = File(tempDir, "bin/fake-ssh").writeExecutable("#!/bin/sh\necho 'Welcome to the server'\n")
        git.run(work, "config", "core.sshCommand", ssh.path)

        assertThrows(LfsException::class.java) {
            runBlocking { authenticate("git@example.com:org/repo.git", OperationType.DOWNLOAD) }
        }
    }

    private suspend fun authenticate(
        url: String,
        operation: OperationType,
        shellVariables: Map<String, String> = emptyMap(),
    ): LfsSshAuthenticateResult {
        val gitVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.absolutePath, "GIT_CONFIG_NOSYSTEM" to "1")
        val processRunner = ProcessRunner()
        // Without the askpass helper, as nothing here asks
        val askpass = AskpassProcessRunner(
            processRunner,
            AskpassHelper { null },
            CredentialsStateManager(),
            CredentialsCacheRepository(),
        )
        val action = AuthenticateLfsServerWithSshGitAction(
            askpass,
            testGitCli(gitVariables),
            LoginShellEnvironment { shellVariables },
        )

        return Git.open(work).use { git -> action(git.repository, url, operation) }
    }

    private fun recordedArguments(ssh: File): List<String> = File(ssh.parentFile, "args").readLines()
}
