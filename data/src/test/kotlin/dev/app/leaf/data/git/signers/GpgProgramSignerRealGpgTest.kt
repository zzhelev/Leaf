// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.git.tags.CreateTagGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

private const val USER_ID = "Leaf Test <leaf@example.com>"

/**
 * Signs with the real gpg, when it's installed, and verifies with the git CLI. The key is an ED25519 key without a
 * passphrase, in a throwaway GNUPGHOME that keeps keys in keyboxd (`use-keyboxd`), as GnuPG 2.4 does for new users;
 * JGit's BouncyCastle signer failed on both (Gitnuro#194, #293). The developer's own keyring is never used.
 */
@DisabledOnOs(OS.WINDOWS)
class GpgProgramSignerRealGpgTest {
    @TempDir
    lateinit var tempDir: File

    private val gpg: String? = locateProgram("gpg", currentOs, System.getenv("PATH"))
    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    // Short, as gpg-agent's sockets are created in it, and a socket's path can't be longer than 104 bytes on macOS
    private val gnupgHome by lazy { File(tempDir, "g") }
    private val gpgEnvironment by lazy { mapOf("GNUPGHOME" to gnupgHome.path, "LC_ALL" to "C") }
    private val signer by lazy { GpgProgramSigner(ProcessRunner(), LoginShellEnvironment { gpgEnvironment }) }

    @BeforeEach
    fun setUp() {
        assumeTrue(gpg != null, "gpg is not installed")
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        // gpg warns about a home that others can read
        val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        Files.createDirectory(gnupgHome.toPath(), ownerOnly)
        File(gnupgHome, "common.conf").writeText("use-keyboxd\n")

        gpg("--batch", "--pinentry-mode", "loopback", "--passphrase", "", "--quick-gen-key", USER_ID, "ed25519", "sign", "never")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)

        if (gpg != null && gnupgHome.isDirectory) {
            // Stops the gpg-agent and keyboxd started for the throwaway home
            run(listOf(File(File(gpg).parentFile, "gpgconf").path, "--kill", "all"))
        }
    }

    @Test
    fun `signs a commit that git verifies, with the key of the committer's identity`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val committer = PersonIdent("Leaf Test", "leaf@example.com")

        val result = testJGit().provide(File(repository, ".git").path) { git ->
            git.commit()
                .setMessage("Signed")
                .setAllowEmpty(true)
                .setAuthor(committer)
                .setCommitter(committer)
                .setSign(true)
                .setSigner(signer)
                .call()
        }

        assertInstanceOf(Either.Ok::class.java, result)
        val output = git.run(repository, gpgEnvironment, "-c", "gpg.program=$gpg", "verify-commit", "HEAD")
        assertTrue(output.contains("Good signature from \"$USER_ID\""), output)
    }

    @Test
    fun `signs a tag that git verifies, with user signingKey`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "tag.gpgSign", "true")
        git.run(repository, "config", "user.signingKey", fingerprint())
        val head = git.run(repository, "rev-parse", "HEAD").trim()
        val identity = Identity("Leaf Test", "test@example.invalid")

        Signers.set(GpgConfig.GpgFormat.OPENPGP, signer)
        val result = try {
            CreateTagGitAction(testJGit())(
                File(repository, ".git").path,
                "v1",
                Commit(head, "Initial commit", identity, identity, 0, emptyList()),
            )
        } finally {
            Signers.set(GpgConfig.GpgFormat.OPENPGP, null)
        }

        assertInstanceOf(Either.Ok::class.java, result)
        val output = git.run(repository, gpgEnvironment, "-c", "gpg.program=$gpg", "verify-tag", "v1")
        assertTrue(output.contains("Good signature from \"$USER_ID\""), output)
    }

    @Test
    fun `locates the key of an identity gpg has, and no other`() {
        val config = GpgConfig(Config())

        assertTrue(signer.canLocateSigningKey(null, config, PersonIdent("Leaf Test", "leaf@example.com"), null, null))
        assertFalse(signer.canLocateSigningKey(null, config, PersonIdent("Nobody", "nobody@example.com"), null, null))
    }

    private fun fingerprint(): String {
        return gpg("--with-colons", "--list-secret-keys", USER_ID)
            .lines()
            .first { it.startsWith("fpr:") }
            .split(':')[9]
    }

    private fun gpg(vararg args: String): String = run(listOf(gpg!!) + args)

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(gpgEnvironment) }
            .start()

        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "'${command.joinToString(" ")}' did not finish" }
        check(process.exitValue() == 0) { "'${command.joinToString(" ")}' failed: $output" }

        return output
    }
}
