package dev.app.leaf.data.git.credentials

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.credentials.CredentialsAccepted
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.exceptions.CommandExecutionFailed
import dev.app.leaf.domain.exceptions.NotSupportedHelper
import dev.app.leaf.domain.models.CredentialsType
import dev.app.leaf.domain.repositories.CredentialsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialItem.*
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import java.io.*
import java.util.concurrent.TimeUnit

private const val TIMEOUT_MIN = 1L
private const val TAG = "HttpCredentialsProvider"
private const val GH_CLI_ARGS = "auth git-credential"


@AssistedFactory
interface HttpCredentialsFactory {
    fun create(git: Git?): HttpCredentialsProvider
}

class HttpCredentialsProvider @AssistedInject constructor(
    private val credentialsStateManager: CredentialsStateManager,
    private val shellManager: IShellManager,
    // private val appSettingsRepository: AppSettingsRepository,
    private val credentialsCacheRepository: CredentialsRepository,
    private val gitCredentialsManagerProvider: IGitCredentialsManagerProvider,
    private val loginShellEnvironment: LoginShellEnvironment,
    @Assisted val git: Git?,
) : CredentialsProvider(), CredentialsCache {

    private var credentialsCached: CredentialsType.HttpCredentials? = null

    /** The credentials that [get] last gave while a credential helper is configured, which [reset] erases. */
    private var helperCredentials: HelperCredentials? = null

    override fun isInteractive() = true

    override fun supports(vararg items: CredentialItem?): Boolean {
        val fields = items.map { credentialItem -> credentialItem?.promptText }
        val isEmpty = fields.isEmpty()

        val isUserPasswordAuth = fields.size == 2 &&
                fields.contains("Username") &&
                fields.contains("Password")

        val isAskingForSslDisable = items.any { it is YesNoType }

        return isEmpty || isUserPasswordAuth || isAskingForSslDisable
    }

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        helperCredentials = null

        val itemsMap = items.map { "${it::class.simpleName} - ${it.promptText}" }

        printLog(TAG, "Items are $itemsMap")

        val sslTrustNowItem = items
            .filterIsInstance<YesNoType>()
            .firstOrNull { it.promptText.contains(JGitText.get().sslTrustNow) }

        val userItem = items
            .filterIsInstance<Username>()
            .firstOrNull()

        val passwordItem = items
            .filterIsInstance<Password>()
            .firstOrNull()

        if (userItem == null || passwordItem == null) {
            return false
        }

        if (sslTrustNowItem != null) {
            // TODO Reenable this after refactoring
            //  sslTrustNowItem.value = appSettingsRepository.verifySsl
        }

        val externalCredentialsHelper = getExternalCredentialsHelper(uri, git)

        if (externalCredentialsHelper == null) {
            val cachedCredentials = credentialsCacheRepository.getCachedHttpCredentials(
                url = uri.toString(),
                isLfs = false,
            )
            // TODO Reenable this after refactoring
            if (cachedCredentials == null /*|| !appSettingsRepository.cacheCredentialsInMemory*/) {
                val credentials = askForCredentials()

                userItem.value = credentials.user
                passwordItem.value = credentials.password.toCharArray()

                // TODO Reenable this after refactoring
                if (true) {
                //if (appSettingsRepository.cacheCredentialsInMemory) {
                    credentialsCached = CredentialsType.HttpCredentials(
                        url = uri.toString(),
                        user = credentials.user,
                        password = credentials.password,
                        isLfs = false,
                    )
                }

                return true
            } else {
                userItem.value = cachedCredentials.user
                passwordItem.value = cachedCredentials.password.toCharArray()

                return true
            }
        } else {
            when (handleExternalCredentialHelper(externalCredentialsHelper, uri, items)) {
                ExternalCredentialsRequestResult.SUCCESS -> {
                    helperCredentials = HelperCredentials(
                        helper = externalCredentialsHelper,
                        uri = uri,
                        user = userItem.value,
                        password = String(passwordItem.value),
                        store = null,
                    )

                    return true
                }

                ExternalCredentialsRequestResult.FAIL -> return false
                ExternalCredentialsRequestResult.CREDENTIALS_NOT_STORED -> {
                    val credentials = askForCredentials()
                    userItem.value = credentials.user
                    passwordItem.value = credentials.password.toCharArray()

                    // Git stores them once the server accepts them, Leaf right away: reset erases them if it doesn't
                    helperCredentials = HelperCredentials(
                        helper = externalCredentialsHelper,
                        uri = uri,
                        user = credentials.user,
                        password = credentials.password,
                        store = sendCredentialsToExternalHelper(
                            operation = "store",
                            uri = uri,
                            externalCredentialsHelper = externalCredentialsHelper,
                            user = credentials.user,
                            password = credentials.password,
                        ),
                    )

                    return true
                }
            }
        }
    }

    /**
     * JGit calls this when the server rejects the credentials that [get] gave, before it calls [get] again. Like git
     * (`credential_reject`), Leaf then runs the credential helper with `erase`, whether the credentials came from the
     * helper or from the user, so that the helper doesn't give them back and Leaf asks the user instead.
     */
    override fun reset(uri: URIish) {
        val rejected = helperCredentials ?: return
        helperCredentials = null

        try {
            // Erasing them before the helper has stored them would leave them stored
            rejected.store?.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)

            val process = sendCredentialsToExternalHelper(
                operation = "erase",
                uri = rejected.uri,
                externalCredentialsHelper = rejected.helper,
                user = rejected.user,
                password = rejected.password,
            )

            // The next get must not find them
            if (!process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)) {
                process.destroy()
                printError(TAG, "The credential helper did not finish erasing the rejected credentials")
            }
        } catch (e: CommandExecutionFailed) {
            // Like git, carries on without the helper
            printError(TAG, "The credential helper could not erase the rejected credentials: ${e.message}")
        }
    }

    /** Runs the helper's [operation] (`store` or `erase`) for these credentials, without waiting for it to finish. */
    private fun sendCredentialsToExternalHelper(
        operation: String,
        uri: URIish,
        externalCredentialsHelper: ExternalCredentialsHelper,
        user: String,
        password: String,
    ): Process {
        val process = startCredentialsHelper(externalCredentialsHelper, operation)

        val output = process.outputStream // write to the input stream of the helper
        val bufferedWriter = BufferedWriter(OutputStreamWriter(output))

        bufferedWriter.useForHelperInput {
            bufferedWriter.write("protocol=${uri.scheme}\n")
            bufferedWriter.write("host=${uri.host}\n")

            if (externalCredentialsHelper.useHttpPath) {
                bufferedWriter.write("path=${uri.path}\n")
            }

            bufferedWriter.write("username=$user\n")
            bufferedWriter.write("password=$password\n")
            bufferedWriter.write("")

            bufferedWriter.flush()
        }

        return process
    }

    private fun askForCredentials(): CredentialsAccepted.HttpCredentialsAccepted = runBlocking {
        credentialsStateManager.requestHttpCredentials()
    }

    private fun handleExternalCredentialHelper(
        externalCredentialsHelper: ExternalCredentialsHelper, uri: URIish, items: Array<out CredentialItem>,
    ): ExternalCredentialsRequestResult {
        val process = startCredentialsHelper(externalCredentialsHelper, "get")

        val output = process.outputStream // write to the input stream of the helper
        val input = process.inputStream // reads from the output stream of the helper

        val bufferedWriter = BufferedWriter(OutputStreamWriter(output))
        val bufferedReader = BufferedReader(InputStreamReader(input))

        bufferedWriter.useForHelperInput {
            bufferedWriter.write("protocol=${uri.scheme}\n")
            bufferedWriter.write("host=${uri.host}\n")

            if (externalCredentialsHelper.useHttpPath) {
                bufferedWriter.write("path=${uri.path}\n")
            }

            bufferedWriter.write("")

            bufferedWriter.flush()
        }

        var usernameSet = false
        var passwordSet = false

        process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)

        // If the process is alive after $TIMEOUT_MIN, it means that it hasn't given an answer and then finished
        if (process.isAlive) {
            process.destroy()
            return ExternalCredentialsRequestResult.FAIL
        }

        bufferedReader.use {
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null && !(usernameSet && passwordSet)) {
                val safeLine = line ?: continue

                // A value is everything after the first "=", as passwords and tokens can contain "="
                if (safeLine.startsWith("username=")) {
                    val userName = safeLine.substringAfter("=")

                    val userNameItem = items.firstOrNull { it is Username }

                    if (userNameItem is Username) {
                        userNameItem.value = userName
                        usernameSet = true
                    }

                } else if (safeLine.startsWith("password=")) {
                    val password = safeLine.substringAfter("=")

                    val passwordItem = items.firstOrNull { it is Password }

                    if (passwordItem is Password) {
                        passwordItem.value = password.toCharArray()
                        passwordSet = true
                    }
                }
            }
        }

        return if (usernameSet && passwordSet) {
            ExternalCredentialsRequestResult.SUCCESS
        } else
            ExternalCredentialsRequestResult.CREDENTIALS_NOT_STORED
    }

    /**
     * On macOS and Linux the helper runs the way git runs it, with the login shell's environment, so that it's found
     * when Leaf was opened from the Finder, the Dock or a desktop launcher.
     */
    private fun startCredentialsHelper(helper: ExternalCredentialsHelper, operation: String): Process {
        return if (currentOs == OS.WINDOWS) {
            shellManager.runCommandProcess(helper.sanitizedCommand() + operation)
        } else {
            shellManager.runCommandProcess(
                command = posixCredentialHelperCommand(helper.path, operation),
                environment = loginShellEnvironment.variablesBlocking(),
            )
        }
    }

    private fun getExternalCredentialsHelper(uri: URIish, git: Git?): ExternalCredentialsHelper? {
        val config = if (git == null) {
            val homePath = System.getProperty("user.home")
            val configFile = File("$homePath/.gitconfig")

            Config().apply {
                if (configFile.exists()) {
                    fromText(configFile.readText())
                }
            }
        } else {
            git.repository.config
        }

        val hostWithProtocol = "${uri.scheme}://${uri.host}"

        val genericCredentialHelper = config.getString("credential", null, "helper")
        val uriSpecificCredentialHelper = config.getString("credential", hostWithProtocol, "helper")
        var credentialHelperPath = uriSpecificCredentialHelper ?: genericCredentialHelper ?: return null

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

        // Use getString instead of getBoolean as boolean has a default value by we want null if the config field is not set
        val uriSpecificUseHttpHelper = config.getString("credential", hostWithProtocol, "useHttpPath")
        val genericUseHttpHelper = config.getBoolean("credential", "useHttpPath", false)

        val useHttpPath = uriSpecificUseHttpHelper?.toBoolean() ?: genericUseHttpHelper

        return ExternalCredentialsHelper(credentialHelperPath, useHttpPath)
    }

    override suspend fun cacheCredentialsIfNeeded() {
        credentialsCached?.let {
            credentialsCacheRepository.cacheHttpCredentials(it)
        }
    }
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

/**
 * Credentials that [HttpCredentialsProvider.get] gave while [helper] is configured, for [uri]. [store] is the helper's
 * `store` of them, when Leaf asked the user for them; it may still be running.
 */
private class HelperCredentials(
    val helper: ExternalCredentialsHelper,
    val uri: URIish,
    val user: String,
    val password: String,
    val store: Process?,
)

enum class ExternalCredentialsRequestResult {
    SUCCESS,
    FAIL,
    CREDENTIALS_NOT_STORED;
}