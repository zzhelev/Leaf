package dev.app.leaf.data.git.credentials

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.exceptions.CommandExecutionFailed
import dev.app.leaf.domain.exceptions.NotSupportedHelper
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.URIish
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TIMEOUT_MIN = 1L
private const val TAG = "CredentialHelpers"
private const val GH_CLI_ARGS = "auth git-credential"

/**
 * Runs the credential helper that git runs for a URL (`credential.helper`), the way git runs it, for `get`, `store`
 * and `erase`. HTTPS remotes ([HttpCredentialsProvider]) and LFS servers (`ProvideLfsCredentialsGitAction`) use it.
 */
class CredentialHelpers @Inject constructor(
    private val shellManager: IShellManager,
    private val gitCredentialsManagerProvider: IGitCredentialsManagerProvider,
    private val loginShellEnvironment: LoginShellEnvironment,
) {
    /** The helper that [config] sets for [uri], or null if it sets none. */
    fun find(config: Config, uri: URIish): ExternalCredentialsHelper? {
        // The credential.<url> subsections that apply to this URL, as git matches them, then credential.* (null)
        val subsections = credentialConfigSubsections(config, uri) + null

        val helperSubsection = subsections.firstOrNull { config.getString("credential", it, "helper") != null }
        var credentialHelperPath = config.getString("credential", helperSubsection, "helper") ?: return null

        // On macOS and Linux they run as git credential-cache and git credential-store (posixCredentialHelperCommand)
        if (currentOs == OS.WINDOWS && (credentialHelperPath == "cache" || credentialHelperPath == "store")) {
            printError(TAG, "Invalid credentials helper: \"$credentialHelperPath\" is not yet supported")
            return null
        }

        // TODO Try to use "git-credential-manager-core" when "manager-core" is detected. Works for linux but requires testing for mac/windows
        // On macOS and Linux, git's own lookup finds it (posixCredentialHelperCommand)
        if (currentOs == OS.WINDOWS && (credentialHelperPath == "manager-core" || credentialHelperPath == "manager")) {
            val credentialsPath = gitCredentialsManagerProvider.loadPath()
                ?: throw NotSupportedHelper("Could not find git credentials manager path")

            credentialHelperPath = credentialsPath
        }

        // getString finds where it's set, as getBoolean gives the default where it isn't
        val useHttpPathSubsection = subsections.firstOrNull {
            config.getString("credential", it, "useHttpPath") != null
        }
        val useHttpPath = config.getBoolean("credential", useHttpPathSubsection, "useHttpPath", false)

        return ExternalCredentialsHelper(credentialHelperPath, useHttpPath)
    }

    /** Asks [helper] for the credentials of [uri] (`get`). */
    fun get(helper: ExternalCredentialsHelper, uri: URIish): HelperAnswer {
        // Like git, which refuses the URL, gives no credentials rather than asking
        val helperInput = credentialHelperInput(uri, helper.useHttpPath)

        if (helperInput == null) {
            printError(TAG, "Not running the credential helper: the URL has a newline or carriage return")
            return HelperAnswer.Failed
        }

        val process = start(helper, "get")

        val output = process.outputStream // write to the input stream of the helper
        val input = process.inputStream // reads from the output stream of the helper

        val bufferedWriter = BufferedWriter(OutputStreamWriter(output))
        val bufferedReader = BufferedReader(InputStreamReader(input))

        bufferedWriter.useForHelperInput {
            bufferedWriter.write(helperInput)
            bufferedWriter.flush()
        }

        var user: String? = null
        var password: String? = null

        process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)

        // If the process is alive after $TIMEOUT_MIN, it means that it hasn't given an answer and then finished
        if (process.isAlive) {
            process.destroy()
            return HelperAnswer.Failed
        }

        bufferedReader.use {
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null && (user == null || password == null)) {
                val safeLine = line ?: continue

                // A value is everything after the first "=", as passwords and tokens can contain "="
                if (safeLine.startsWith("username=")) {
                    user = safeLine.substringAfter("=")
                } else if (safeLine.startsWith("password=")) {
                    password = safeLine.substringAfter("=")
                }
            }
        }

        val givenUser = user
        val givenPassword = password

        return if (givenUser != null && givenPassword != null) {
            HelperAnswer.Credentials(givenUser, givenPassword)
        } else {
            HelperAnswer.NotStored
        }
    }

    /**
     * Runs [helper]'s [operation] (`store` or `erase`) for these credentials, without waiting for it to finish. Null
     * if git would refuse to send them (see [credentialHelperInput]).
     */
    fun send(
        operation: String,
        helper: ExternalCredentialsHelper,
        uri: URIish,
        user: String,
        password: String,
    ): Process? {
        val input = credentialHelperInput(uri, helper.useHttpPath, user, password)

        if (input == null) {
            printError(TAG, "Not running the credential helper's $operation: a value has a newline or carriage return")
            return null
        }

        val process = start(helper, operation)

        val output = process.outputStream // write to the input stream of the helper
        val bufferedWriter = BufferedWriter(OutputStreamWriter(output))

        bufferedWriter.useForHelperInput {
            bufferedWriter.write(input)
            bufferedWriter.flush()
        }

        return process
    }

    /**
     * Erases these credentials, which the server rejected, with [helper], like git's `credential_reject`, and waits
     * for it, so that the next `get` doesn't find them. [store] is the helper's `store` of them, which may still be
     * running: erasing them before it finishes would leave them stored. Like git, it carries on if the helper fails.
     */
    fun erase(
        helper: ExternalCredentialsHelper,
        uri: URIish,
        user: String,
        password: String,
        store: Process? = null,
    ) {
        try {
            store?.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)

            val process = send("erase", helper, uri, user, password) ?: return

            if (!process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)) {
                process.destroy()
                printError(TAG, "The credential helper did not finish erasing the rejected credentials")
            }
        } catch (e: CommandExecutionFailed) {
            printError(TAG, "The credential helper could not erase the rejected credentials: ${e.message}")
        }
    }

    /**
     * On macOS and Linux the helper runs the way git runs it, with the login shell's environment, so that it's found
     * when Leaf was opened from the Finder, the Dock or a desktop launcher.
     */
    private fun start(helper: ExternalCredentialsHelper, operation: String): Process {
        return if (currentOs == OS.WINDOWS) {
            shellManager.runCommandProcess(helper.sanitizedCommand() + operation)
        } else {
            shellManager.runCommandProcess(
                command = posixCredentialHelperCommand(helper.path, operation),
                environment = loginShellEnvironment.variablesBlocking(),
            )
        }
    }
}

/** What a credential helper answered to `get`. */
sealed interface HelperAnswer {
    data class Credentials(val user: String, val password: String) : HelperAnswer

    /** The helper has no credentials for the URL, so Leaf asks the user. */
    data object NotStored : HelperAnswer

    /** Leaf gives no credentials: git refuses the URL, or the helper didn't answer within a minute. */
    data object Failed : HelperAnswer
}

data class ExternalCredentialsHelper(
    val path: String,
    val useHttpPath: Boolean,
) {
    /**
     * Sometimes the git credentials manager path also includes these arguments (detected on Linux), they should be
     * treated as arguments instead of part of the binary path
     */
    fun sanitizedCommand(): List<String> {
        return if (path.endsWith(GH_CLI_ARGS)) {
            val path = path.removeSuffix(GH_CLI_ARGS).trim()
            val arguments = GH_CLI_ARGS.split(" ")

            listOf(path) + arguments
        } else {
            listOf(path)
        }
    }
}
