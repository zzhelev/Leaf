package dev.app.leaf.data.git.credentials

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.exceptions.CommandExecutionFailed
import dev.app.leaf.domain.exceptions.NotSupportedHelper
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TIMEOUT_MIN = 1L
private const val TAG = "CredentialHelpers"
private const val GH_CLI_ARGS = "auth git-credential"
private val CONFIG_TIMEOUT = 10.seconds

/**
 * Runs the credential helpers that git runs for a URL (`credential.helper`), the way git runs them, for `get`, `store`
 * and `erase`. HTTPS remotes ([HttpCredentialsProvider]) and LFS servers (`ProvideLfsCredentialsGitAction`) use it.
 */
class CredentialHelpers @Inject constructor(
    private val shellManager: IShellManager,
    private val gitCredentialsManagerProvider: IGitCredentialsManagerProvider,
    private val loginShellEnvironment: LoginShellEnvironment,
    private val gitCli: GitCli,
) {
    /**
     * The credential settings that git applies to [uri] in [repository], or without one (cloning) in the user's and
     * the system's config. Their helpers are the ones Leaf can run here, and may be none.
     */
    suspend fun find(repository: Repository?, uri: URIish): CredentialSettings {
        val entries = gitConfigEntries(repository) ?: jgitCredentialEntries(jgitConfig(repository), uri)
        val settings = credentialSettings(entries, uri)

        return settings.copy(helpers = settings.helpers.mapNotNull { helper -> platformHelper(helper) })
    }

    /**
     * Every config entry, in the order git reads them, from `git config --list`. Git also reads the files that
     * `includeIf` names, which JGit doesn't. Null if git can't run.
     */
    private suspend fun gitConfigEntries(repository: Repository?): List<ConfigEntry>? {
        // Without a repository, a git dir that isn't one, so that git reads only the user's and the system's config
        val gitDir = repository?.directory ?: File(System.getProperty("java.io.tmpdir"), "leaf-no-repository")
        val workingDirectory = repository?.directory ?: File(System.getProperty("user.home"))
        val args = listOf("--git-dir=${gitDir.absolutePath}", "config", "--list", "-z")

        return when (val result = gitCli.run(workingDirectory, args, CONFIG_TIMEOUT)) {
            is Either.Ok -> parseConfigList(result.value)
            is Either.Err -> {
                printError(TAG, "Reading the credential settings with JGit, as git couldn't list them: ${result.error}")
                null
            }
        }
    }

    /** The config that JGit reads for [repository], or without one its user config, which includes the system's. */
    private fun jgitConfig(repository: Repository?): Config {
        if (repository != null) {
            return repository.config
        }

        return try {
            SystemReader.getInstance().userConfig
        } catch (e: IOException) {
            printError(TAG, "Could not read the user's git config: ${e.message}")
            Config()
        } catch (e: ConfigInvalidException) {
            printError(TAG, "Could not read the user's git config: ${e.message}")
            Config()
        }
    }

    /** The command that runs [helper] on this OS, or null if Leaf can't run it here. */
    private fun platformHelper(helper: String): String? {
        // On macOS and Linux they run as git credential-cache and git credential-store (posixCredentialHelperCommand)
        if (currentOs == OS.WINDOWS && (helper == "cache" || helper == "store")) {
            printError(TAG, "Invalid credentials helper: \"$helper\" is not yet supported")
            return null
        }

        // TODO Try to use "git-credential-manager-core" when "manager-core" is detected. Works for linux but requires testing for mac/windows
        // On macOS and Linux, git's own lookup finds it (posixCredentialHelperCommand)
        if (currentOs == OS.WINDOWS && (helper == "manager-core" || helper == "manager")) {
            return gitCredentialsManagerProvider.loadPath()
                ?: throw NotSupportedHelper("Could not find git credentials manager path")
        }

        return helper
    }

    /**
     * Asks the helpers for the credentials of [uri] (`get`), one after the other, like git's `credential_fill`, until
     * one completes the user name and the password. Each helper gets the user name that git knows, and what the
     * helpers before it gave, so a helper can answer with the password alone.
     */
    fun get(settings: CredentialSettings, uri: URIish): HelperAnswer {
        var user = settings.username
        var password: String? = null

        for (helper in settings.helpers) {
            // Like git, which refuses the URL, gives no credentials rather than asking
            val helperInput = credentialHelperInput(uri, settings.useHttpPath, user, password)

            if (helperInput == null) {
                printError(TAG, "Not running the credential helper: the URL has a newline or carriage return")
                return HelperAnswer.Failed
            }

            val answer = ask(helper, helperInput) ?: return HelperAnswer.Failed

            answer["username"]?.let { user = it }
            answer["password"]?.let { password = it }

            val givenUser = user
            val givenPassword = password

            if (givenUser != null && givenPassword != null) {
                return HelperAnswer.Credentials(givenUser, givenPassword)
            }

            if (answer["quit"]?.let(::gitBoolean) == true) {
                printError(TAG, "The credential helper told Leaf to stop looking for credentials")
                return HelperAnswer.Failed
            }
        }

        return HelperAnswer.NotStored(user, password)
    }

    /** What [helper] answers to `get` with [helperInput], or null if it didn't answer within a minute. */
    private fun ask(helper: String, helperInput: String): Map<String, String>? {
        val process = try {
            start(helper, "get")
        } catch (e: CommandExecutionFailed) {
            // Like git, carries on without it
            printError(TAG, "The credential helper could not be started: ${e.message}")
            return emptyMap()
        }

        val output = process.outputStream // write to the input stream of the helper
        val input = process.inputStream // reads from the output stream of the helper

        val bufferedWriter = BufferedWriter(OutputStreamWriter(output))
        val bufferedReader = BufferedReader(InputStreamReader(input))

        bufferedWriter.useForHelperInput {
            bufferedWriter.write(helperInput)
            bufferedWriter.flush()
        }

        process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)

        // If the process is alive after $TIMEOUT_MIN, it means that it hasn't given an answer and then finished
        if (process.isAlive) {
            process.destroy()
            return null
        }

        return bufferedReader.use { reader ->
            reader.lineSequence()
                .takeWhile { it.isNotEmpty() }
                // A value is everything after the first "=", as passwords and tokens can contain "="
                .filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
        }
    }

    /**
     * Runs every helper's [operation] (`store` or `erase`) for these credentials, like git's `credential_approve` and
     * `credential_reject`, without waiting for them to finish. None run if git would refuse to send the credentials
     * (see [credentialHelperInput]), and like git, it carries on past a helper that can't be started.
     */
    fun send(
        operation: String,
        settings: CredentialSettings,
        uri: URIish,
        user: String,
        password: String,
    ): List<Process> {
        val input = credentialHelperInput(uri, settings.useHttpPath, user, password)

        if (input == null) {
            printError(TAG, "Not running the credential helper's $operation: a value has a newline or carriage return")
            return emptyList()
        }

        return settings.helpers.mapNotNull { helper ->
            val process = try {
                start(helper, operation)
            } catch (e: CommandExecutionFailed) {
                printError(TAG, "The credential helper could not be started for $operation: ${e.message}")
                return@mapNotNull null
            }

            val output = process.outputStream // write to the input stream of the helper
            val bufferedWriter = BufferedWriter(OutputStreamWriter(output))

            bufferedWriter.useForHelperInput {
                bufferedWriter.write(input)
                bufferedWriter.flush()
            }

            process
        }
    }

    /**
     * Erases these credentials, which the server rejected, with every helper, like git's `credential_reject`, and
     * waits for them, so that the next `get` doesn't find them. [stores] are the helpers' `store` of them, which may
     * still be running: erasing them before they finish would leave them stored.
     */
    fun erase(
        settings: CredentialSettings,
        uri: URIish,
        user: String,
        password: String,
        stores: List<Process> = emptyList(),
    ) {
        stores.forEach { it.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES) }

        send("erase", settings, uri, user, password).forEach { process ->
            if (!process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)) {
                process.destroy()
                printError(TAG, "The credential helper did not finish erasing the rejected credentials")
            }
        }
    }

    /**
     * On macOS and Linux the helper runs the way git runs it, with the login shell's environment, so that it's found
     * when Leaf was opened from the Finder, the Dock or a desktop launcher.
     */
    private fun start(helper: String, operation: String): Process {
        return if (currentOs == OS.WINDOWS) {
            shellManager.runCommandProcess(windowsHelperCommand(helper) + operation)
        } else {
            shellManager.runCommandProcess(
                command = posixCredentialHelperCommand(helper, operation),
                environment = loginShellEnvironment.variablesBlocking(),
            )
        }
    }
}

/** What the credential helpers answered to `get`. */
sealed interface HelperAnswer {
    data class Credentials(val user: String, val password: String) : HelperAnswer

    /**
     * No helper has complete credentials for the URL, so Leaf asks the user for the rest, as git does. At most one is
     * known: the [user] name, from the URL, `credential.username` or a helper, or the [password] that a helper gave
     * without a user name.
     */
    data class NotStored(val user: String?, val password: String?) : HelperAnswer

    /**
     * Leaf gives no credentials: git refuses the URL, a helper said `quit`, or a helper didn't answer within a
     * minute.
     */
    data object Failed : HelperAnswer
}

/**
 * The command that runs the helper [path] on Windows. Sometimes the git credentials manager path also includes these
 * arguments (detected on Linux), they should be treated as arguments instead of part of the binary path
 */
private fun windowsHelperCommand(path: String): List<String> {
    return if (path.endsWith(GH_CLI_ARGS)) {
        val command = path.removeSuffix(GH_CLI_ARGS).trim()
        val arguments = GH_CLI_ARGS.split(" ")

        listOf(command) + arguments
    } else {
        listOf(path)
    }
}
