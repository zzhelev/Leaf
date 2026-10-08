// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.common.OS
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GpgSigningError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.condition.OS as JUnitOS

private const val SIGNATURE = "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQRK\n-----END PGP SIGNATURE-----\n"

/** Prints [SIGNATURE] with Windows line ends, as gpg on Windows does. */
private val PRINT_SIGNATURE = "printf '%b' '${SIGNATURE.replace("\n", "\\r\\n")}'"

/** What gpg prints for a successful `--status-fd=2 -bsau`: status lines, and the signature. */
private val SIGNING_GPG = """
    echo '[GNUPG:] KEY_CONSIDERED 4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A 2' >&2
    echo '[GNUPG:] BEGIN_SIGNING H10' >&2
    echo '[GNUPG:] SIG_CREATED D 22 10 00 1791381795 4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A' >&2
    $PRINT_SIGNATURE
""".trimIndent()

/** What gpg 2.5 prints for a key it doesn't have. */
private val NO_SECRET_KEY_GPG = """
    echo 'gpg: skipped "nobody@example.com": No secret key' >&2
    echo '[GNUPG:] INV_SGNR 9 nobody@example.com' >&2
    echo '[GNUPG:] FAILURE sign 17' >&2
    echo 'gpg: signing failed: No secret key' >&2
    exit 2
""".trimIndent()

/** What gpg 2.5 prints when its pinentry is pinentry-curses and there is no terminal, as when opened from the Finder. */
private val CURSES_PINENTRY_GPG = """
    echo '[GNUPG:] KEY_CONSIDERED 2E980F8FFE6F77B28442B6F5F6D66BEC06604C88 2' >&2
    echo '[GNUPG:] BEGIN_SIGNING H10' >&2
    echo '[GNUPG:] PINENTRY_LAUNCHED 37345 curses 1.3.3 - - - - 501/20 0' >&2
    echo 'gpg: signing failed: Inappropriate ioctl for device' >&2
    echo '[GNUPG:] FAILURE sign 83918950' >&2
    echo 'gpg: signing failed: Inappropriate ioctl for device' >&2
    exit 2
""".trimIndent()

private const val KEY = "4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A"

class GpgProgramSignerTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val data = "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n\nSigned\n".toByteArray()

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `runs gpg program as git does and returns the signature without carriage returns`() {
        val gpg = fakeGpg(File(tempDir, "bin"), SIGNING_GPG)

        val signature = signer().sign(null, gpgConfig("gpg.program" to gpg.path), data, null, KEY, null)

        assertEquals(listOf("--status-fd=2", "-bsau", KEY), recorded(gpg, "args").lines().dropLast(1))
        assertEquals(data.decodeToString(), recorded(gpg, "input"))
        assertEquals(SIGNATURE, signature.toExternalString())
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `finds gpg on the login shell's PATH and runs it with the shell's variables`() {
        // Like Homebrew's gpg, which isn't on the PATH of an app opened from the Finder
        val gpg = fakeGpg(File(tempDir, "homebrew/bin"), SIGNING_GPG, name = "gpg")
        val shellVariables = mapOf(
            "PATH" to "${gpg.parent}:/usr/bin:/bin",
            "LEAF_TEST_VARIABLE" to "from the shell",
        )

        signer(shellVariables).sign(null, gpgConfig(), data, null, KEY, null)

        assertEquals("from the shell\n", recorded(gpg, "variable"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `signs with the committer's identity when no signing key is set, as git does`() {
        val gpg = fakeGpg(File(tempDir, "bin"), SIGNING_GPG)
        val committer = PersonIdent("Leaf Test", "leaf@example.com")

        signer().sign(null, gpgConfig("gpg.program" to gpg.path), data, committer, null, null)

        assertEquals("Leaf Test <leaf@example.com>", recorded(gpg, "args").lines()[2])
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `signs with user signingKey when JGit passes no key, as its TagCommand does`() {
        val gpg = fakeGpg(File(tempDir, "bin"), SIGNING_GPG)
        val config = gpgConfig("gpg.program" to gpg.path, "user.signingKey" to KEY)

        signer().sign(null, config, data, PersonIdent("Leaf Test", "leaf@example.com"), null, null)

        assertEquals(KEY, recorded(gpg, "args").lines()[2])
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `fails with gpg's own messages when gpg can't sign`() {
        val gpg = fakeGpg(File(tempDir, "bin"), NO_SECRET_KEY_GPG)

        val error = signingError(gpgConfig("gpg.program" to gpg.path))

        val expected = "gpg: skipped \"nobody@example.com\": No secret key\ngpg: signing failed: No secret key"
        assertEquals(GpgSigningError.SigningFailed(gpg.path, expected), error)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `fails when gpg exits with 0 without reporting SIG_CREATED, as git does`() {
        val gpg = fakeGpg(File(tempDir, "bin"), PRINT_SIGNATURE)

        val error = signingError(gpgConfig("gpg.program" to gpg.path))

        assertEquals(GpgSigningError.SigningFailed(gpg.path, "(no gpg output)"), error)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `tells when gpg's pinentry needs a terminal`() {
        val gpg = fakeGpg(File(tempDir, "bin"), CURSES_PINENTRY_GPG)

        val error = signingError(gpgConfig("gpg.program" to gpg.path))

        assertEquals(GpgSigningError.PinentryUnavailable("gpg: signing failed: Inappropriate ioctl for device"), error)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `fails when gpg is not found`() {
        val error = signingError(gpgConfig("gpg.program" to "leaf-missing-gpg"), mapOf("PATH" to tempDir.path))

        assertEquals(GpgSigningError.ProgramNotFound("leaf-missing-gpg"), error)
    }

    @Test
    fun `fails without a signing key or a committer`() {
        assertEquals(GpgSigningError.NoSigningKey, signingError(gpgConfig(), committer = null))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `a commit that gpg can't sign fails with the gpg error, not JGit's generic one`(): Unit = runBlocking {
        val gpg = fakeGpg(File(tempDir, "bin"), NO_SECRET_KEY_GPG)
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "gpg.program", gpg.path)
        val signer = signer()

        val result = testJGit().provide(File(repository, ".git").path) { git ->
            git.commit().setMessage("Signed").setAllowEmpty(true).setSign(true).setSigner(signer).call()
        }

        val error = (result as Either.Err).error
        assertTrue(error is GpgSigningError.SigningFailed, "$error")
        assertEquals("Initial commit\n", git.run(repository, "log", "-1", "--format=%s"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `JGit puts the signature into the commit`(): Unit = runBlocking {
        val gpg = fakeGpg(File(tempDir, "bin"), SIGNING_GPG)
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "gpg.program", gpg.path)
        val signer = signer()

        val result = testJGit().provide(File(repository, ".git").path) { git ->
            git.commit().setMessage("Signed").setAllowEmpty(true).setSign(true).setSigner(signer).call()
        }

        val commit = (result as Either.Ok).value
        assertEquals(SIGNATURE.trim(), commit.rawGpgSignature.decodeToString().trim())
        // gpg signed the commit as git writes it, without the signature
        assertTrue(recorded(gpg, "input").contains("\n\nSigned"), recorded(gpg, "input"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `locates a secret key that can sign`() {
        val gpg = fakeGpg(
            File(tempDir, "bin"),
            "echo 'sec:u:255:22:B4CE4B3E4E90D11A:1791381795:::u:::scSC:::+:::ed25519:::0:'\n" +
                    "echo 'fpr:::::::::$KEY:'",
        )

        val canLocate = signer().canLocateSigningKey(null, gpgConfig("gpg.program" to gpg.path), null, KEY, null)

        assertTrue(canLocate)
        val expectedArgs = listOf("--batch", "--no-tty", "--with-colons", "--list-secret-keys", "--", KEY)
        assertEquals(expectedArgs, recorded(gpg, "args").lines().dropLast(1))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `does not locate a key gpg doesn't have`() {
        val gpg = fakeGpg(File(tempDir, "bin"), "echo 'gpg: error reading key: No secret key' >&2; exit 2")

        assertFalse(signer().canLocateSigningKey(null, gpgConfig("gpg.program" to gpg.path), null, KEY, null))
    }

    @Test
    fun `a secret key needs the uppercase signing capability`() {
        assertTrue(hasUsableSigningKey("sec:u:255:22:B4CE4B3E4E90D11A:1791381795:::u:::scSC:::+:::ed25519:::0:"))
        // Expired: gpg keeps the key's own lowercase capabilities but no uppercase ones
        assertFalse(hasUsableSigningKey("sec:e:255:22:B4CE4B3E4E90D11A:1691381795:1701381795::u:::sc:::+:::ed25519:::0:"))
        assertFalse(hasUsableSigningKey("sec:u:255:22:B4CE4B3E4E90D11A:1791381795:::u:::eE:::+:::cv25519:::0:"))
        assertFalse(hasUsableSigningKey("ssb:u:255:22:B4CE4B3E4E90D11A:1791381795:::u:::S:::+:::ed25519:::0:"))
        assertFalse(hasUsableSigningKey(""))
    }

    @Test
    fun `recognizes pinentry failures from their status codes`() {
        val launched = "[GNUPG:] PINENTRY_LAUNCHED 37345 curses 1.3.3 - - - - 501/20 0"

        // pinentry-curses without a terminal: ENOTTY, and ENOENT for GPG_TTY="not a tty"
        assertTrue(isPinentryFailure(listOf(launched, "[GNUPG:] FAILURE sign 83918950")))
        assertTrue(isPinentryFailure(listOf(launched, "[GNUPG:] FAILURE sign 83918929")))
        // pinentry-program points to a missing program
        assertTrue(isPinentryFailure(listOf("[GNUPG:] FAILURE sign 67108949")))
        // The user cancelled the pinentry, gpg has no such key
        assertFalse(isPinentryFailure(listOf(launched, "[GNUPG:] FAILURE sign 83886179")))
        assertFalse(isPinentryFailure(listOf("[GNUPG:] FAILURE sign 17")))
        // A system error without a pinentry isn't the pinentry's
        assertFalse(isPinentryFailure(listOf("[GNUPG:] FAILURE sign 83918950")))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `looks a name up on the PATH, and on macOS also where gpg is usually installed`() {
        val bin = File(tempDir, "bin")
        fakeGpg(bin, SIGNING_GPG, name = "gpg")
        File(tempDir, "not-executable/gpg").apply { parentFile.mkdirs() }.writeText("")

        assertEquals(
            File(bin, "gpg").path,
            locateGpgProgram("gpg", OS.LINUX, "${tempDir.path}/not-executable:${bin.path}"),
        )
        assertNull(locateGpgProgram("gpg", OS.LINUX, "${tempDir.path}/not-executable"))
        assertEquals("/some/where/gpg2", locateGpgProgram("/some/where/gpg2", OS.LINUX, bin.path))
        assertEquals("gpg", locateGpgProgram("gpg", OS.WINDOWS, null))
    }

    @Test
    fun `gpg program defaults to gpg`() {
        assertEquals("gpg", gpgProgram(gpgConfig()))
        assertEquals("gpg", gpgProgram(null))
        assertEquals("gpg2", gpgProgram(gpgConfig("gpg.program" to "gpg2")))
        // As in git, gpg.openpgp.program is the same setting
        assertEquals("gpg2", gpgProgram(gpgConfig("gpg.openpgp.program" to "gpg2")))
    }

    private fun signer(shellVariables: Map<String, String> = emptyMap()) =
        GpgProgramSigner(ProcessRunner(), LoginShellEnvironment { shellVariables })

    private fun signingError(
        config: GpgConfig,
        shellVariables: Map<String, String> = emptyMap(),
        committer: PersonIdent? = PersonIdent("Leaf Test", "leaf@example.com"),
    ): GpgSigningError {
        val exception = assertThrows(GpgSigningException::class.java) {
            signer(shellVariables).sign(null, config, data, committer, null, null)
        }

        return exception.error
    }

    /** A [GpgConfig] with the given `section.key` (or `section.subsection.key`) values. */
    private fun gpgConfig(vararg values: Pair<String, String>): GpgConfig {
        val config = Config()

        for ((name, value) in values) {
            val parts = name.split('.')
            config.setString(parts.first(), parts.drop(1).dropLast(1).singleOrNull(), parts.last(), value)
        }

        return GpgConfig(config)
    }

    /**
     * A gpg that records its arguments (one per line), its input and `LEAF_TEST_VARIABLE` next to itself, then runs
     * [body].
     */
    private fun fakeGpg(directory: File, body: String, name: String = "fake-gpg"): File {
        return File(directory, name).writeExecutable(
            """
            |#!/bin/sh
            |dir=${'$'}(dirname "${'$'}0")
            |printf '%s\n' "${'$'}@" > "${'$'}dir/args"
            |printf '%s\n' "${'$'}LEAF_TEST_VARIABLE" > "${'$'}dir/variable"
            |cat > "${'$'}dir/input"
            |$body
            |""".trimMargin()
        )
    }

    private fun recorded(gpg: File, name: String) = File(gpg.parentFile, name).readText()
}
