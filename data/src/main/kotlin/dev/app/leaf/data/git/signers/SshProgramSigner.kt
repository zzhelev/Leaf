// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.ProcessOutcome
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.SshSigningError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.GpgSignature
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Signer
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SshProgramSigner"

private const val DEFAULT_PROGRAM = "ssh-keygen"
private const val LITERAL_KEY_PREFIX = "key::"

/** git still takes a public key without `key::` when it starts like one, but calls that form deprecated. */
private const val DEPRECATED_LITERAL_KEY_PREFIX = "ssh-"

/**
 * Leaves time to type a passphrase or touch a security key. There is no cancel button while Leaf commits, so a program
 * that hangs must not block the tab forever.
 */
private val SIGN_TIMEOUT = 2.minutes

private val DEFAULT_KEY_COMMAND_TIMEOUT = 30.seconds

/** ssh-keygen asks for a passphrase once. ssh asks three times (`NumberOfPasswordPrompts`), and so does Leaf. */
private const val PASSPHRASE_ATTEMPTS = 3

/** ssh-keygen's message for a wrong passphrase. OpenSSH doesn't translate its messages. */
private const val WRONG_PASSPHRASE = "incorrect passphrase supplied"

/** What ssh-keygen prints when it doesn't know `-Y sign`, which git looks for too. */
private const val USAGE = "usage:"

/**
 * Signs commits and tags with SSH keys (`gpg.format` `ssh`) by running ssh-keygen, as the git CLI does
 * (`sign_buffer_ssh` in gpg-interface.c): the program from `gpg.ssh.program` (`ssh-keygen` by default) with
 * `-Y sign -n git -f <key> <file>`, which writes the signature to `<file>.sig`.
 *
 * - The key is `user.signingKey`, or else the first line that `gpg.ssh.defaultKeyCommand` prints, if it's a public
 *   key ([SshSigningKey]).
 * - ssh-keygen signs with the agent when it holds the key. Otherwise it asks for the key's passphrase through the
 *   askpass helper, which shows Leaf's dialog, and the passphrase is kept for the session, as for ssh.
 * - Programs that stand in for ssh-keygen, such as 1Password's `op-ssh-sign`, get the arguments that git gives them.
 *
 * It replaces libssh's signing, which only read private key files, and asked for their passphrase at every signature.
 */
class SshProgramSigner @Inject constructor(
    private val askpassProcessRunner: AskpassProcessRunner,
    private val processRunner: ProcessRunner,
    private val loginShellEnvironment: LoginShellEnvironment,
) : Signer {
    override fun sign(
        repository: Repository?,
        config: GpgConfig?,
        data: ByteArray,
        committer: PersonIdent?,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?,
    ): GpgSignature {
        // JGit's TagCommand passes no key unless it's set on the command, its CommitCommand passes user.signingKey
        val settings = SshSigningSettings.of(repository?.config, signingKey ?: config?.signingKey)
        val result = runBlocking { sign(settings, data, repository?.workingDirectory()) }

        return when (result) {
            is Either.Ok -> GpgSignature(result.value)
            is Either.Err -> throw SshSigningException(result.error)
        }
    }

    /** Whether a key is set up. Whether ssh-keygen can sign with it shows only when it signs. */
    override fun canLocateSigningKey(
        repository: Repository?,
        config: GpgConfig?,
        committer: PersonIdent?,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?,
    ): Boolean {
        val settings = SshSigningSettings.of(repository?.config, signingKey ?: config?.signingKey)

        return settings.signingKey != null || settings.defaultKeyCommand != null
    }

    internal suspend fun sign(
        settings: SshSigningSettings,
        data: ByteArray,
        workingDirectory: File?,
    ): Either<ByteArray, SshSigningError> {
        val environment = loginShellEnvironment.variables()
        val keyValue = settings.signingKey
            ?: settings.defaultKeyCommand?.let { defaultKey(it, environment, workingDirectory) }
            ?: return Either.Err(SshSigningError.NoSigningKey)

        val key = sshSigningKey(keyValue, homeDirectory(environment), workingDirectory)
        val program = settings.program
        val executable = locateProgram(program, environment)
            ?: return Either.Err(SshSigningError.ProgramNotFound(program))

        // Only the user can open it (Files.createTempDirectory on POSIX), and it's removed afterwards
        val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("leaf-ssh-signing").toFile() }

        return try {
            sign(program, executable, key, data, directory, environment, workingDirectory)
        } finally {
            withContext(Dispatchers.IO) {
                FileUtils.delete(directory, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
            }
        }
    }

    private suspend fun sign(
        program: String,
        executable: String,
        key: SshSigningKey,
        data: ByteArray,
        directory: File,
        environment: Map<String, String>,
        workingDirectory: File?,
    ): Either<ByteArray, SshSigningError> {
        val dataFile = File(directory, "buffer")
        val signatureFile = File(directory, "buffer.sig")

        val keyArguments = withContext(Dispatchers.IO) {
            dataFile.writeBytes(data)

            when (key) {
                is SshSigningKey.KeyFile -> listOf("-f", key.path)
                is SshSigningKey.Literal -> {
                    val keyFile = File(directory, "key.pub")
                    keyFile.writeText(key.publicKey + "\n")
                    // The agent has to hold the key, as there is no private key file
                    listOf("-f", keyFile.path, "-U")
                }
            }
        }

        val command = listOf(executable, "-Y", "sign", "-n", "git") + keyArguments + dataFile.path
        val answers = askpassProcessRunner.answers(passphraseKeyPath = (key as? SshSigningKey.KeyFile)?.privateKeyPath)

        for (attempt in 1..PASSPHRASE_ATTEMPTS) {
            val outcome = try {
                askpassProcessRunner.run(command, workingDirectory, environment, SIGN_TIMEOUT, answers)
            } catch (e: IOException) {
                return Either.Err(SshSigningError.StartFailed(program, e.message.orEmpty()))
            }

            val completed = when (outcome) {
                ProcessOutcome.TimedOut -> return Either.Err(
                    SshSigningError.TimedOut(program, SIGN_TIMEOUT.inWholeSeconds)
                )

                is ProcessOutcome.Completed -> outcome
            }

            if (completed.exitCode == 0 && signatureFile.isFile) {
                answers.commit()
                // Without carriage returns, as git removes them (ssh-keygen on Windows)
                val signature = withContext(Dispatchers.IO) { signatureFile.readText() }.replace("\r", "")

                return Either.Ok(signature.toByteArray(Charsets.UTF_8))
            }

            if (answers.refused) {
                return Either.Err(SshSigningError.Cancelled)
            }

            // The next attempt counts as a retry: a kept passphrase is dropped, and the user is asked
            if (completed.exitCode == 0 || !completed.stderr.contains(WRONG_PASSPHRASE)) {
                return Either.Err(signingFailure(program, completed))
            }
        }

        return Either.Err(SshSigningError.SigningFailed(program, "$WRONG_PASSPHRASE to decrypt the key"))
    }

    /**
     * The first line that `gpg.ssh.defaultKeyCommand` printed, if it's a public key. The command runs without a shell,
     * split as git splits it, and git only warns when it fails, as Leaf does.
     */
    private suspend fun defaultKey(
        commandLine: String,
        environment: Map<String, String>,
        workingDirectory: File?,
    ): String? {
        val arguments = splitCommandLine(commandLine)
        val executable = arguments?.firstOrNull()?.let { locateProgram(it, environment) }

        if (arguments == null || executable == null) {
            printError(TAG, "gpg.ssh.defaultKeyCommand can't be run: $commandLine")
            return null
        }

        val outcome = try {
            processRunner.run(
                listOf(executable) + arguments.drop(1),
                workingDirectory,
                environment,
                DEFAULT_KEY_COMMAND_TIMEOUT,
            )
        } catch (e: IOException) {
            printError(TAG, "gpg.ssh.defaultKeyCommand could not be started: ${e.message}")
            return null
        }

        val firstLine = (outcome as? ProcessOutcome.Completed)
            ?.takeIf { it.exitCode == 0 }
            ?.stdout
            ?.lineSequence()
            ?.firstOrNull()
            ?.trimEnd('\r')

        val isPublicKey = firstLine != null &&
            sshSigningKey(firstLine, home = null, workingDirectory = null) is SshSigningKey.Literal

        if (!isPublicKey) {
            printError(TAG, "gpg.ssh.defaultKeyCommand gave no key: $outcome")
            return null
        }

        return firstLine
    }

    private fun Repository.workingDirectory(): File = if (isBare) directory else workTree
}

/** The settings that git reads for SSH signing. */
internal data class SshSigningSettings(
    val signingKey: String?,
    val program: String = DEFAULT_PROGRAM,
    val defaultKeyCommand: String? = null,
) {
    companion object {
        /**
         * `gpg.ssh.program` is read here, as JGit's [GpgConfig.getProgram] falls back to `gpg.program`, which git only
         * uses for OpenPGP.
         */
        fun of(config: Config?, signingKey: String?) = SshSigningSettings(
            signingKey = signingKey?.takeIf { it.isNotBlank() },
            program = config?.getString("gpg", "ssh", "program")?.takeIf { it.isNotBlank() } ?: DEFAULT_PROGRAM,
            defaultKeyCommand = config?.getString("gpg", "ssh", "defaultKeyCommand")?.takeIf { it.isNotBlank() },
        )
    }
}

/** The key that `user.signingKey` names, as git reads it (`is_literal_ssh_key`, `interpolate_path`). */
internal sealed interface SshSigningKey {
    /** A public key, `key::ssh-ed25519 AAAA…` or the deprecated `ssh-ed25519 AAAA…`, which the agent signs with. */
    data class Literal(val publicKey: String) : SshSigningKey

    /** A key file, private or public. */
    data class KeyFile(val path: String) : SshSigningKey {
        /** The private key, which ssh-keygen loads when the agent doesn't have the key: [path] without `.pub`. */
        val privateKeyPath: String
            get() = when {
                path.endsWith("-cert.pub") -> path.removeSuffix("-cert.pub")
                else -> path.removeSuffix(".pub")
            }
    }
}

/**
 * The key that [value] names. A path that starts with `~/` is in [home], as git expands it; `~user/` isn't expanded.
 * A relative path is relative to [workingDirectory], the working tree, where git runs ssh-keygen.
 */
internal fun sshSigningKey(value: String, home: String?, workingDirectory: File?): SshSigningKey {
    if (value.startsWith(LITERAL_KEY_PREFIX)) {
        return SshSigningKey.Literal(value.removePrefix(LITERAL_KEY_PREFIX))
    }

    if (value.startsWith(DEPRECATED_LITERAL_KEY_PREFIX)) {
        return SshSigningKey.Literal(value)
    }

    val path = when {
        home != null && value == "~" -> home
        home != null && value.startsWith("~/") -> File(home, value.removePrefix("~/")).path
        else -> value
    }

    val isRelative = !File(path).isAbsolute && workingDirectory != null

    return SshSigningKey.KeyFile(if (isRelative) File(workingDirectory, path).path else path)
}

private fun homeDirectory(environment: Map<String, String>): String? =
    environment["HOME"] ?: System.getenv("HOME") ?: System.getProperty("user.home")

/**
 * Splits [commandLine] into arguments as git's `split_cmdline` does, without a shell: whitespace separates them,
 * single and double quotes group them, and a backslash outside single quotes takes the next character as it is. Null
 * when a quote isn't closed or a backslash ends the line.
 */
internal fun splitCommandLine(commandLine: String): List<String>? {
    val arguments = mutableListOf<String>()
    val argument = StringBuilder()
    var inArgument = false
    var quote: Char? = null
    var index = 0

    while (index < commandLine.length) {
        val character = commandLine[index]

        when {
            quote == null && character.isWhitespace() -> if (inArgument) {
                arguments.add(argument.toString())
                argument.clear()
                inArgument = false
            }

            quote == null && (character == '\'' || character == '"') -> {
                quote = character
                inArgument = true
            }

            character == quote -> quote = null

            character == '\\' && quote != '\'' -> {
                index++
                argument.append(commandLine.getOrNull(index) ?: return null)
                inArgument = true
            }

            else -> {
                argument.append(character)
                inArgument = true
            }
        }

        index++
    }

    if (quote != null) {
        return null
    }

    if (inArgument) {
        arguments.add(argument.toString())
    }

    return arguments
}

/**
 * ssh-keygen's failure, with its own messages, which git shows too. It printed its usage when it has no `-Y sign`.
 */
private fun signingFailure(program: String, outcome: ProcessOutcome.Completed): SshSigningError {
    val output = outcome.stderr.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.joinToString("\n")
        .ifEmpty { "(no output, exit code ${outcome.exitCode})" }

    return if (output.contains(USAGE)) {
        SshSigningError.SigningUnsupported(program, output)
    } else {
        SshSigningError.SigningFailed(program, output)
    }
}

/**
 * Thrown by [SshProgramSigner], as JGit's signer interface can't return errors. It's a [CanceledException] for the
 * reason [GpgSigningException] is: `JGit.provide` turns it back into its [error].
 */
class SshSigningException(val error: SshSigningError) : CanceledException(error.toString())

/** The [SshSigningError] that failed this operation, wherever JGit wrapped the [SshSigningException]. */
internal fun Throwable.sshSigningError(): SshSigningError? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SshSigningException>()
        .firstOrNull()
        ?.error
}
