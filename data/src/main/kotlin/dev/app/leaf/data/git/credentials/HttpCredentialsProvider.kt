package dev.app.leaf.data.git.credentials

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.credentials.CredentialsAccepted
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.models.CredentialsType
import dev.app.leaf.domain.repositories.CredentialsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

        if (helperSettings.helpers.isEmpty()) {
            val cachedCredentials = credentialsCacheRepository.getCachedHttpCredentials(
                url = uri.toString(),
                isLfs = false,
            )
            // TODO Reenable this after refactoring
            if (cachedCredentials == null /*|| !appSettingsRepository.cacheCredentialsInMemory*/) {
                val credentials = askForCredentials(helperSettings.username, password = null)

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
                        credential = answer.credential,
                        fromHelpers = true,
                        stores = emptyList(),
                    )

                    return true
                }

                HelperAnswer.Failed -> return false
                is HelperAnswer.NotStored -> {
                    val typed = askForCredentials(answer.credential.username, answer.credential.password)
                    // With anything else that the helpers gave, such as a refresh token, as git keeps it
                    val credential = answer.credential.copy(username = typed.user, password = typed.password)
                    userItem.value = typed.user
                    passwordItem.value = typed.password.toCharArray()

                    // Git stores them once the server accepts them, Leaf right away: reset erases them if it doesn't.
                    // That includes a password from a helper, as git's credential_reject erases it too
                    helperCredentials = HelperCredentials(
                        settings = helperSettings,
                        uri = uri,
                        credential = credential,
                        fromHelpers = false,
                        stores = credentialHelpers.send("store", helperSettings, uri, credential),
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

        credentialHelpers.erase(rejected.settings, rejected.uri, rejected.credential, rejected.stores)
    }

    /**
     * Asks the user for credentials, or only for the part that git doesn't know: the password when it knows the
     * [user] name, or the user name when a helper gave the [password].
     */
    private fun askForCredentials(user: String?, password: String?): CredentialsAccepted.HttpCredentialsAccepted =
        runBlocking { credentialsStateManager.requestHttpCredentials(user, password) }

    /**
     * Called once the operation succeeded. Credentials that the user typed without a helper go into Leaf's in-memory
     * cache. Credentials that the helpers gave are stored with every helper, like git's `credential_approve`, so that
     * they reach the helpers that didn't have them. Typed credentials went to the helpers when the user gave them.
     */
    override suspend fun cacheCredentialsIfNeeded() {
        credentialsCached?.let {
            credentialsCacheRepository.cacheHttpCredentials(it)
        }

        helperCredentials?.takeIf { it.fromHelpers }?.let { approved ->
            withContext(Dispatchers.IO) {
                credentialHelpers.approve(approved.settings, approved.uri, approved.credential)
            }
        }
    }
}

/**
 * Credentials that [HttpCredentialsProvider.get] gave for [uri] while credential helpers are configured ([settings]).
 * When the helpers gave them ([fromHelpers]), they are stored with every helper once the operation succeeds. When Leaf
 * asked the user for them, [stores] are the helpers' `store` of them, which may still be running.
 */
private class HelperCredentials(
    val settings: CredentialSettings,
    val uri: URIish,
    val credential: HelperCredential,
    val fromHelpers: Boolean,
    val stores: List<Process>,
)
