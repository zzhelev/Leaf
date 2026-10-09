// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.ProcessOutcome
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GpgSigningError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.GpgSignature
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Signer
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GpgProgramSigner"

private const val DEFAULT_PROGRAM = "gpg"

/**
 * Leaves time to type a passphrase or touch a security key. There is no cancel button while Leaf commits, so a gpg
 * that hangs must not block the tab forever.
 */
private val SIGN_TIMEOUT = 2.minutes

/** gpg may have to start gpg-agent or keyboxd first. */
private val LIST_KEYS_TIMEOUT = 30.seconds

private const val STATUS_PREFIX = "[GNUPG:] "
private const val GPG_ERR_CODE_MASK = 0xFFFF
private const val GPG_ERR_SYSTEM_ERROR = 0x8000
private const val GPG_ERR_NO_PIN_ENTRY = 85

/**
 * Signs commits and tags with OpenPGP keys by running GnuPG, as the git CLI does (gpg-interface.c): the program from
 * `gpg.program` (`gpg` by default) with `--status-fd=2 -bsau <key>`, the data on stdin, the armored signature on
 * stdout, and `[GNUPG:] SIG_CREATED` on the status output. gpg-agent and its pinentry ask for the key's passphrase.
 *
 * It replaces JGit's BouncyCastle signer, which reimplements gpg: it can't find keys kept by keyboxd (GnuPG 2.4's
 * `use-keyboxd`), and its ED25519 signatures fail to verify (Gitnuro#194, #293).
 *
 * gpg runs with the login shell's environment, so that Homebrew's gpg is found when Leaf is opened from the Finder.
 */
class GpgProgramSigner @Inject constructor(
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
        val gpgConfig = config ?: repository?.let { GpgConfig(it.config) }
        val key = signingKeyOrIdentity(signingKey ?: gpgConfig?.signingKey, committer)
            ?: throw GpgSigningException(GpgSigningError.NoSigningKey)

        val result = runBlocking {
            sign(gpgProgram(gpgConfig), key, data, repository?.workingDirectory())
        }

        return when (result) {
            is Either.Ok -> GpgSignature(result.value)
            is Either.Err -> throw GpgSigningException(result.error)
        }
    }

    override fun canLocateSigningKey(
        repository: Repository?,
        config: GpgConfig?,
        committer: PersonIdent?,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?,
    ): Boolean {
        val gpgConfig = config ?: repository?.let { GpgConfig(it.config) }
        val key = signingKeyOrIdentity(signingKey ?: gpgConfig?.signingKey, committer) ?: return false

        return runBlocking {
            canLocateSigningKey(gpgProgram(gpgConfig), key, repository?.workingDirectory())
        }
    }

    internal suspend fun sign(
        program: String,
        key: String,
        data: ByteArray,
        workingDirectory: File?,
    ): Either<ByteArray, GpgSigningError> {
        val environment = loginShellEnvironment.variables()
        val executable = locateProgram(program, environment)
            ?: return Either.Err(GpgSigningError.ProgramNotFound(program))

        val outcome = try {
            processRunner.run(
                listOf(executable, "--status-fd=2", "-bsau", key),
                workingDirectory,
                environment,
                SIGN_TIMEOUT,
                input = data,
            )
        } catch (e: IOException) {
            return Either.Err(GpgSigningError.StartFailed(program, e.message.orEmpty()))
        }

        return when (outcome) {
            ProcessOutcome.TimedOut -> Either.Err(GpgSigningError.TimedOut(program, SIGN_TIMEOUT.inWholeSeconds))
            is ProcessOutcome.Completed -> signatureFromOutcome(program, outcome)
        }
    }

    /** Whether gpg has a secret key for [key] that can sign, without asking for its passphrase. */
    internal suspend fun canLocateSigningKey(program: String, key: String, workingDirectory: File?): Boolean {
        val environment = loginShellEnvironment.variables()
        val executable = locateProgram(program, environment) ?: return false

        val outcome = try {
            processRunner.run(
                listOf(executable, "--batch", "--no-tty", "--with-colons", "--list-secret-keys", "--", key),
                workingDirectory,
                environment,
                LIST_KEYS_TIMEOUT,
            )
        } catch (e: IOException) {
            printError(TAG, "Could not start $program to look for the signing key: ${e.message}")
            return false
        }

        return outcome is ProcessOutcome.Completed && outcome.exitCode == 0 && hasUsableSigningKey(outcome.stdout)
    }

    private fun Repository.workingDirectory(): File = if (isBare) directory else workTree
}

/**
 * Thrown by [GpgProgramSigner], as JGit's signer interface can't return errors. It's a [CanceledException] because
 * JGit wraps other exceptions of a signer, such as [IOException], into one with a generic message. `JGit.provide`
 * turns it back into its [error].
 */
class GpgSigningException(val error: GpgSigningError) : CanceledException(error.toString())

/** The [GpgSigningError] that failed this operation, wherever JGit wrapped the [GpgSigningException]. */
internal fun Throwable.gpgSigningError(): GpgSigningError? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<GpgSigningException>()
        .firstOrNull()
        ?.error
}

/** The value of `gpg.program`, or `gpg`. JGit reads `gpg.openpgp.program` first, then `gpg.program`. */
internal fun gpgProgram(config: GpgConfig?): String {
    return config?.program?.takeIf { it.isNotBlank() } ?: DEFAULT_PROGRAM
}

/**
 * The key to sign with: `user.signingKey`, or else the committer's identity, which gpg matches against the user IDs
 * of its keys. Git does the same (`get_signing_key`).
 */
internal fun signingKeyOrIdentity(signingKey: String?, committer: PersonIdent?): String? {
    return signingKey?.takeIf { it.isNotBlank() }
        ?: committer?.let { "${it.name} <${it.emailAddress}>" }
}

/**
 * The signature gpg printed, read as git does (`sign_buffer_gpg`): gpg must exit with 0 and report `SIG_CREATED` at
 * the start of a status line. Carriage returns are removed from the signature, as gpg on Windows prints them.
 */
internal fun signatureFromOutcome(
    program: String,
    outcome: ProcessOutcome.Completed,
): Either<ByteArray, GpgSigningError> {
    val statusLines = outcome.stderr.lines()
    val signature = outcome.stdout.replace("\r", "")
    val isSigned = outcome.exitCode == 0 &&
            statusLines.any { it.startsWith("${STATUS_PREFIX}SIG_CREATED ") } &&
            signature.isNotBlank()

    return when {
        isSigned -> Either.Ok(signature.toByteArray(Charsets.UTF_8))
        isPinentryFailure(statusLines) -> Either.Err(GpgSigningError.PinentryUnavailable(gpgMessages(outcome.stderr)))
        else -> Either.Err(GpgSigningError.SigningFailed(program, gpgMessages(outcome.stderr)))
    }
}

/**
 * Whether gpg failed because its pinentry could not ask for the passphrase: there is no pinentry, or one was started
 * and failed with a system error, as `pinentry-curses` does without a terminal ("Inappropriate ioctl for device").
 * The codes of `FAILURE` status lines don't depend on the language, unlike gpg's messages. Cancelling the pinentry,
 * or a wrong passphrase, isn't such a failure.
 */
internal fun isPinentryFailure(statusLines: List<String>): Boolean {
    val wasPinentryLaunched = statusLines.any { it.startsWith("${STATUS_PREFIX}PINENTRY_LAUNCHED ") }

    return statusLines
        .filter { it.startsWith("${STATUS_PREFIX}FAILURE ") }
        .mapNotNull { it.substringAfterLast(' ').toIntOrNull() }
        .map { it and GPG_ERR_CODE_MASK }
        .any { code -> code == GPG_ERR_NO_PIN_ENTRY || (wasPinentryLaunched && (code and GPG_ERR_SYSTEM_ERROR) != 0) }
}

/**
 * gpg's own messages from its [stderr], which also holds the status lines. Git shows all of it; the status lines are
 * left out here when there are messages, as they don't help the user.
 */
internal fun gpgMessages(stderr: String): String {
    val lines = stderr.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.distinct()
    val messages = lines.filterNot { it.startsWith(STATUS_PREFIX) }

    return messages.ifEmpty { lines }.joinToString("\n").ifEmpty { "(no gpg output)" }
}

/**
 * Whether `gpg --with-colons --list-secret-keys` lists a key that can sign: a `sec` record whose capabilities (field
 * 12) have an `S`. gpg writes it uppercase only when the key as a whole can sign, so not when it's expired or revoked.
 */
internal fun hasUsableSigningKey(colonListing: String): Boolean {
    return colonListing.lineSequence()
        .map { it.split(':') }
        .any { fields -> fields.firstOrNull() == "sec" && fields.getOrNull(11)?.contains('S') == true }
}
