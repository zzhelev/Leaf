package dev.app.leaf.data.git.credentials

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.credentials.CredentialsAccepted
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.models.CredentialsType
import dev.app.leaf.domain.repositories.CredentialsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialItem.*
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

private const val TAG = "HttpCredentialsProvider"


@AssistedFactory
interface HttpCredentialsFactory {
    fun create(git: Git?): HttpCredentialsProvider
}

class HttpCredentialsProvider @AssistedInject constructor(
    private val credentialsStateManager: CredentialsStateManager,
    // private val appSettingsRepository: AppSettingsRepository,
    private val credentialsCacheRepository: CredentialsRepository,
    private val credentialHelpers: CredentialHelpers,
    @Assisted val git: Git?,
) : CredentialsProvider(), CredentialsCache {

    private var credentialsCached: CredentialsType.HttpCredentials? = null

    /** The credentials that [get] last gave from Leaf's in-memory cache, which [reset] removes from it. */
    private var memoryCachedCredentials: CredentialsType.HttpCredentials? = null

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
        memoryCachedCredentials = null

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

        val helperSettings = runBlocking { credentialHelpers.find(git?.repository, uri) }

        if (helperSettings == null) {
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
                memoryCachedCredentials = cachedCredentials

                return true
            }
        } else {
            when (val answer = credentialHelpers.get(helperSettings, uri)) {
                is HelperAnswer.Credentials -> {
                    userItem.value = answer.user
                    passwordItem.value = answer.password.toCharArray()

                    helperCredentials = HelperCredentials(
                        settings = helperSettings,
                        uri = uri,
                        user = answer.user,
                        password = answer.password,
                        stores = emptyList(),
                    )

                    return true
                }

                HelperAnswer.Failed -> return false
                HelperAnswer.NotStored -> {
                    val credentials = askForCredentials()
                    userItem.value = credentials.user
                    passwordItem.value = credentials.password.toCharArray()

                    // Git stores them once the server accepts them, Leaf right away: reset erases them if it doesn't
                    helperCredentials = HelperCredentials(
                        settings = helperSettings,
                        uri = uri,
                        user = credentials.user,
                        password = credentials.password,
                        stores = credentialHelpers.send(
                            operation = "store",
                            settings = helperSettings,
                            uri = uri,
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
     * helper or from the user, so that the helper doesn't give them back and Leaf asks the user instead. Without a
     * helper, Leaf removes them from its in-memory cache, for the same reason.
     */
    override fun reset(uri: URIish) {
        // Typed credentials are cached once the operation succeeds, but the server rejected these
        credentialsCached = null

        memoryCachedCredentials?.let { rejected ->
            memoryCachedCredentials = null
            runBlocking { credentialsCacheRepository.removeCachedHttpCredentials(rejected) }
        }

        val rejected = helperCredentials ?: return
        helperCredentials = null

        credentialHelpers.erase(rejected.settings, rejected.uri, rejected.user, rejected.password, rejected.stores)
    }

    private fun askForCredentials(): CredentialsAccepted.HttpCredentialsAccepted = runBlocking {
        credentialsStateManager.requestHttpCredentials()
    }

    override suspend fun cacheCredentialsIfNeeded() {
        credentialsCached?.let {
            credentialsCacheRepository.cacheHttpCredentials(it)
        }
    }
}

/**
 * Credentials that [HttpCredentialsProvider.get] gave for [uri] while credential helpers are configured ([settings]).
 * [stores] are the helpers' `store` of them, when Leaf asked the user for them; they may still be running.
 */
private class HelperCredentials(
    val settings: CredentialSettings,
    val uri: URIish,
    val user: String,
    val password: String,
    val stores: List<Process>,
)
