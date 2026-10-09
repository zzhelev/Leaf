// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.SshSigningError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.condition.OS as JUnitOS

private const val SIGNATURE = "-----BEGIN SSH SIGNATURE-----\nU1NIU0lHAAAAAQ==\n-----END SSH SIGNATURE-----\n"
private const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOp4 leaf@example.com"

/**
 * Writes [SIGNATURE] next to the data file, its last argument, with Windows line ends, as ssh-keygen on Windows does.
 * Records its arguments, the data, the key file that `-f` names, and a variable from the login shell.
 */
private val SIGNING_SSH_KEYGEN = """
    dir=${'$'}(dirname "${'$'}0")
    previous=
    for argument in "${'$'}@"; do
      echo "${'$'}argument"
      if [ "${'$'}previous" = "-f" ] && [ -f "${'$'}argument" ]; then cp "${'$'}argument" "${'$'}dir/key"; fi
      previous=${'$'}argument
    done > "${'$'}dir/args"
    cp "${'$'}previous" "${'$'}dir/input"
    echo "${'$'}LEAF_TEST_VARIABLE" > "${'$'}dir/variable"
    printf '%b' '${SIGNATURE.replace("\n", "\\r\\n")}' > "${'$'}previous.sig"
""".trimIndent()

/** What ssh-keygen 8.1 prints for `-Y sign`, which it doesn't know. */
private val OLD_SSH_KEYGEN = """
    echo 'usage: ssh-keygen [-q] [-a rounds] [-b bits] [-C comment] [-f output_keyfile]' >&2
    exit 1
""".trimIndent()

/** What ssh-keygen 10.3 prints for a key file that doesn't exist. */
private val MISSING_KEY_SSH_KEYGEN = """
    echo 'Couldn'"'"'t load public key /keys/missing: No such file or directory' >&2
    exit 255
""".trimIndent()

/**
 * Asks for the passphrase as ssh-keygen before OpenSSH 10 does, without naming the key, through `SSH_ASKPASS`. Takes
 * only "phrase", and then signs.
 */
private val ASKING_SSH_KEYGEN = """
    answer=${'$'}("${'$'}SSH_ASKPASS" 'Enter passphrase: ') || exit 255
    if [ "${'$'}answer" != phrase ]; then
      echo 'Load key "/keys/id_ed25519": incorrect passphrase supplied to decrypt private key' >&2
      exit 255
    fi
    for last in "${'$'}@"; do :; done
    printf '%s' '$SIGNATURE' > "${'$'}last.sig"
""".trimIndent()

class SshProgramSignerTest {
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
    fun `runs the program as git does and returns the signature without carriage returns`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)

        val signature = sign(SshSigningSettings("/keys/id_ed25519", sshKeygen.path)) as Either.Ok

        val arguments = recorded(sshKeygen, "args").lines().dropLast(1)
        assertEquals(listOf("-Y", "sign", "-n", "git", "-f", "/keys/id_ed25519"), arguments.dropLast(1))
        assertEquals(data.decodeToString(), recorded(sshKeygen, "input"))
        assertEquals(SIGNATURE, signature.value.decodeToString())
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `removes the data and the signature files afterwards`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)

        sign(SshSigningSettings("/keys/id_ed25519", sshKeygen.path))

        val dataFile = File(recorded(sshKeygen, "args").lines().dropLast(1).last())
        assertFalse(dataFile.parentFile.exists(), "$dataFile")
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `signs with a public key from user signingKey through the agent, as git does with key prefix`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)

        sign(SshSigningSettings("key::$PUBLIC_KEY", sshKeygen.path))

        val arguments = recorded(sshKeygen, "args").lines().dropLast(1)
        assertEquals(listOf("-Y", "sign", "-n", "git", "-f"), arguments.take(5))
        assertEquals("-U", arguments[6])
        assertEquals("$PUBLIC_KEY\n", recorded(sshKeygen, "key"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `takes a public key without key prefix, which git still accepts`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)

        sign(SshSigningSettings(PUBLIC_KEY, sshKeygen.path))

        assertEquals("-U", recorded(sshKeygen, "args").lines()[6])
        assertEquals("$PUBLIC_KEY\n", recorded(sshKeygen, "key"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `expands a key path in the home folder, and finds a relative one in the working tree`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)
        val home = File(tempDir, "home")
        val workTree = File(tempDir, "repo").apply { mkdirs() }

        sign(SshSigningSettings("~/.ssh/id_ed25519", sshKeygen.path), mapOf("HOME" to home.path))
        assertEquals(File(home, ".ssh/id_ed25519").path, recorded(sshKeygen, "args").lines()[5])

        sign(SshSigningSettings("keys/id_ed25519", sshKeygen.path), workingDirectory = workTree)
        assertEquals(File(workTree, "keys/id_ed25519").path, recorded(sshKeygen, "args").lines()[5])
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `finds the program on the login shell's PATH and runs it with the shell's variables`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "homebrew/bin"), SIGNING_SSH_KEYGEN, name = "leaf-ssh-keygen")
        val shellVariables = mapOf(
            "PATH" to "${sshKeygen.parent}:/usr/bin:/bin",
            "LEAF_TEST_VARIABLE" to "from the shell",
        )

        sign(SshSigningSettings("/keys/id_ed25519", "leaf-ssh-keygen"), shellVariables)

        assertEquals("from the shell\n", recorded(sshKeygen, "variable"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `signs with the first key that gpg ssh defaultKeyCommand prints, when user signingKey isn't set`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)
        // Like "ssh-add -L", which lists the agent's keys
        val listKeys = File(tempDir, "bin/leaf-list-keys").writeExecutable(
            "#!/bin/sh\nfor argument in \"\$@\"; do echo \"\$argument\"; done > \"\$(dirname \"\$0\")/list-args\"\n" +
                "printf '%s\\n' '$PUBLIC_KEY' 'ssh-rsa AAAAB3NzaC1yc2E other'\n"
        )
        val settings = SshSigningSettings(null, sshKeygen.path, "${listKeys.path} -L 'two words'")

        sign(settings)

        assertEquals("-L\ntwo words\n", File(tempDir, "bin/list-args").readText())
        assertEquals("-U", recorded(sshKeygen, "args").lines()[6])
        assertEquals("$PUBLIC_KEY\n", recorded(sshKeygen, "key"))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `fails without a key when gpg ssh defaultKeyCommand prints no public key or fails`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)
        val noKeys = File(tempDir, "bin/leaf-no-keys").writeExecutable("#!/bin/sh\necho 'The agent has no identities.'\n")
        val failing = File(tempDir, "bin/leaf-failing").writeExecutable("#!/bin/sh\necho '$PUBLIC_KEY'\nexit 1\n")

        assertEquals(Either.Err(SshSigningError.NoSigningKey), sign(SshSigningSettings(null, sshKeygen.path, noKeys.path)))
        assertEquals(Either.Err(SshSigningError.NoSigningKey), sign(SshSigningSettings(null, sshKeygen.path, failing.path)))
        assertFalse(File(tempDir, "bin/args").exists())
    }

    @Test
    fun `fails without a key, as git does`() {
        assertEquals(Either.Err(SshSigningError.NoSigningKey), sign(SshSigningSettings(null)))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `fails when the program is not found`() {
        val result = sign(SshSigningSettings("/keys/id_ed25519", "leaf-missing-ssh-keygen"), mapOf("PATH" to tempDir.path))

        assertEquals(Either.Err(SshSigningError.ProgramNotFound("leaf-missing-ssh-keygen")), result)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `tells when ssh-keygen is too old for -Y sign`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), OLD_SSH_KEYGEN)

        val error = (sign(SshSigningSettings("/keys/id_ed25519", sshKeygen.path)) as Either.Err).error

        assertTrue(error is SshSigningError.SigningUnsupported, "$error")
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `fails with ssh-keygen's own message`() {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), MISSING_KEY_SSH_KEYGEN)

        val result = sign(SshSigningSettings("/keys/missing", sshKeygen.path))

        val expected = "Couldn't load public key /keys/missing: No such file or directory"
        assertEquals(Either.Err(SshSigningError.SigningFailed(sshKeygen.path, expected)), result)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `asks for the passphrase of the key it signs with, again after a wrong one, and keeps it for the key`(): Unit =
        runBlocking {
            val helper = builtAskpassHelper()
            assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
            val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), ASKING_SSH_KEYGEN)
            val credentialsStateManager = CredentialsStateManager()
            val signer = signer(askpassHelper = helper, credentialsStateManager = credentialsStateManager)
            val settings = SshSigningSettings("/keys/id_ed25519.pub", sshKeygen.path)

            val (results, dialogs) = credentialsStateManager.answeringDialogs(
                { sshCredentialsAccepted("wrong") },
                { sshCredentialsAccepted("phrase") },
            ) {
                listOf(signer.sign(settings, data, null), signer.sign(settings, data, null))
            }

            assertTrue(results.all { it is Either.Ok }, "$results")
            assertEquals(
                listOf(
                    CredentialsRequest.SshCredentialsRequest(isRetry = false, password = ""),
                    CredentialsRequest.SshCredentialsRequest(isRetry = true, password = ""),
                ),
                dialogs,
            )
        }

    @Test
    fun `reads gpg ssh program, not gpg program, which git only uses for OpenPGP`() {
        val config = Config().apply {
            setString("gpg", null, "program", "leaf-gpg")
            setString("gpg", "ssh", "defaultKeyCommand", "ssh-add -L")
        }

        assertEquals(SshSigningSettings("key", "ssh-keygen", "ssh-add -L"), SshSigningSettings.of(config, "key"))

        config.setString("gpg", "ssh", "program", "/opt/op-ssh-sign")
        assertEquals("/opt/op-ssh-sign", SshSigningSettings.of(config, null).program)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `JGit commits with the signature, with the program and key from the repository's config`(): Unit = runBlocking {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), SIGNING_SSH_KEYGEN)
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "gpg.format", "ssh")
        git.run(repository, "config", "gpg.ssh.program", sshKeygen.path)
        git.run(repository, "config", "user.signingKey", "/keys/id_ed25519")
        val signer = signer()

        val result = testJGit().provide(File(repository, ".git").path) { git ->
            git.commit().setMessage("Signed").setAllowEmpty(true).setSign(true).setSigner(signer).call()
        }

        assertTrue(result is Either.Ok, "$result")
        assertEquals("/keys/id_ed25519", recorded(sshKeygen, "args").lines()[5])
        val commit = git.run(repository, "cat-file", "commit", "HEAD")
        assertTrue(commit.contains("gpgsig -----BEGIN SSH SIGNATURE-----\n U1NIU0lHAAAAAQ==\n -----END SSH SIGNATURE-----\n"), commit)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `a commit that ssh-keygen can't sign fails with the signing error, not JGit's generic one`(): Unit = runBlocking {
        val sshKeygen = fakeSshKeygen(File(tempDir, "bin"), MISSING_KEY_SSH_KEYGEN)
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "gpg.ssh.program", sshKeygen.path)
        git.run(repository, "config", "user.signingKey", "/keys/missing")
        val signer = signer()

        val result = testJGit().provide(File(repository, ".git").path) { git ->
            git.commit().setMessage("Signed").setAllowEmpty(true).setSign(true).setSigner(signer).call()
        }

        val error = (result as Either.Err).error
        assertTrue(error is SshSigningError.SigningFailed, "$error")
        assertEquals("Initial commit\n", git.run(repository, "log", "-1", "--format=%s"))
    }

    @Test
    fun `splits a command line as git does, without a shell`() {
        assertEquals(listOf("ssh-add", "-L"), splitCommandLine("ssh-add   -L"))
        assertEquals(listOf("a b", "c d", "e'f", "g\"h", ""), splitCommandLine("'a b' \"c d\" \"e'f\" g\\\"h ''"))
        assertEquals(listOf("C:\\Tools\\list"), splitCommandLine("'C:\\Tools\\list'"))
        assertEquals(listOf("ab"), splitCommandLine("a\\b"))
        assertNull(splitCommandLine("'unclosed"))
        assertNull(splitCommandLine("ends with \\"))
    }

    @Test
    fun `the private key of a public key or certificate file is the file without its suffix, as ssh-keygen finds it`() {
        assertEquals("/k/id", SshSigningKey.KeyFile("/k/id.pub").privateKeyPath)
        assertEquals("/k/id", SshSigningKey.KeyFile("/k/id-cert.pub").privateKeyPath)
        assertEquals("/k/id", SshSigningKey.KeyFile("/k/id").privateKeyPath)
    }

    private fun sign(
        settings: SshSigningSettings,
        shellVariables: Map<String, String> = emptyMap(),
        workingDirectory: File? = null,
    ): Either<ByteArray, SshSigningError> = runBlocking {
        signer(shellVariables).sign(settings, data, workingDirectory)
    }

    /** Without the askpass helper, unless a test gives it: then the program can't ask anything. */
    private fun signer(
        shellVariables: Map<String, String> = emptyMap(),
        askpassHelper: File? = null,
        credentialsStateManager: CredentialsStateManager = CredentialsStateManager(),
    ): SshProgramSigner {
        val processRunner = ProcessRunner()
        val askpass = AskpassProcessRunner(
            processRunner,
            AskpassHelper { askpassHelper },
            credentialsStateManager,
            CredentialsCacheRepository(),
        )

        return SshProgramSigner(askpass, processRunner, LoginShellEnvironment { shellVariables })
    }

    private fun fakeSshKeygen(directory: File, body: String, name: String = "fake-ssh-keygen"): File =
        File(directory, name).writeExecutable("#!/bin/sh\n$body\n")

    private fun recorded(sshKeygen: File, name: String): String = File(sshKeygen.parentFile, name).readText()
}
