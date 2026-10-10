package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.credentials.CredentialSettings
import dev.app.leaf.data.git.credentials.HelperAnswer
import dev.app.leaf.data.git.credentials.HelperCredential
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IProvideLfsCredentialsGitAction
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.repositories.CredentialsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import javax.inject.Inject

class ProvideLfsCredentialsGitAction @Inject constructor(
    private val credentialsCacheRepository: CredentialsRepository,
    private val credentialsStateManager: CredentialsStateManager,
    private val credentialHelpers: CredentialHelpers,
    private val appSettingsService: AppSettingsService,
) : IProvideLfsCredentialsGitAction {
    override suspend operator fun <T> invoke(
        repository: Repository,
        lfsServer: LfsServer,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError> {
        val res = callback(null, null)

        if (!res.isUnauthorizedError()) {
            return res
        }

        val credentialsUri = lfsCredentialsUri(lfsServer)
        val settings = credentialHelpers.find(repository, credentialsUri)

        return if (settings.helpers.isNotEmpty()) {
            withHelper(settings, credentialsUri, unauthorized = res, callback)
        } else {
            withCachedCredentials(lfsServer.url, settings.username, callback)
        }
    }

    /**
     * Like git-lfs: the helpers' credentials, then the user's. Credentials that the server rejects are erased with
     * the helpers. Once the server takes them, they are stored with every helper, as git-lfs does with
     * `git credential approve`: the helpers' too, so that they reach the helpers that didn't have them. When git
     * knows the user name, the user is asked only for the password, and when a helper gave only the password, only for
     * the user name.
     */
    private suspend fun <T> withHelper(
        helper: CredentialSettings,
        uri: URIish,
        unauthorized: Either<T, LfsError>,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError> {
        // What Leaf knows when it asks the user. After a rejection, like git-lfs, only the user name that git knows
        val known = when (val answer = withContext(Dispatchers.IO) { credentialHelpers.get(helper, uri) }) {
            is HelperAnswer.Credentials -> {
                tryOnce(helper, uri, answer.credential, callback)?.let { return it }
                HelperCredential(username = helper.username)
            }

            HelperAnswer.Failed -> return unauthorized
            is HelperAnswer.NotStored -> {
                val helperPassword = answer.credential.password

                if (helperPassword == null) {
                    answer.credential
                } else {
                    // Like git, only the user name to go with the helper's password
                    val typed = credentialsStateManager.requestLfsCredentials(user = null, password = helperPassword)
                    val credential = answer.credential.copy(username = typed.user, password = typed.password)

                    tryOnce(helper, uri, credential, callback)?.let { return it }
                    HelperCredential(username = helper.username)
                }
            }
        }

        return askForCredentials(known.username, callback) { user, password ->
            val credential = known.copy(username = user, password = password)
            withContext(Dispatchers.IO) { credentialHelpers.approve(helper, uri, credential) }
        }
    }

    /**
     * Tries [credential], which came from the helpers at least in part, once: it is stored with every helper if the
     * server takes it, and erased with them if it rejects it, which gives null.
     */
    private suspend fun <T> tryOnce(
        helper: CredentialSettings,
        uri: URIish,
        credential: HelperCredential,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError>? {
        val res = callback(credential.username, credential.password)

        if (!res.isUnauthorizedError()) {
            if (res is Either.Ok) {
                withContext(Dispatchers.IO) { credentialHelpers.approve(helper, uri, credential) }
            }

            return res
        }

        withContext(Dispatchers.IO) { credentialHelpers.erase(helper, uri, credential) }
        return null
    }

    /**
     * Without a helper: the credentials that Leaf cached for [url], then the user's, asked only for the password when
     * git knows the [user] name. Cached credentials that the server rejects are removed, and the ones the user typed
     * are cached once the server takes them. With "Cache HTTP credentials in memory" off, the user is asked every
     * time and nothing is cached.
     */
    private suspend fun <T> withCachedCredentials(
        url: String,
        user: String?,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError> {
        if (!appSettingsService.cacheCredentialsInMemory.first()) {
            return askForCredentials(user, callback) { _, _ -> }
        }

        val credentialsCached = credentialsCacheRepository.getCachedHttpCredentials(url, isLfs = true)

        if (credentialsCached != null) {
            val res = callback(credentialsCached.user, credentialsCached.password)

            if (!res.isUnauthorizedError()) {
                return res
            }

            credentialsCacheRepository.removeCachedHttpCredentials(credentialsCached)
        }

        return askForCredentials(user, callback) { acceptedUser, password ->
            credentialsCacheRepository.cacheHttpCredentials(url, acceptedUser, password, isLfs = true)
        }
    }

    /**
     * Asks the user for credentials until the server takes them, or the user cancels, and passes the ones it took to
     * [onAccepted]. When git knows the [user] name, the user is asked only for the password.
     */
    private suspend fun <T> askForCredentials(
        user: String?,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
        onAccepted: suspend (user: String, password: String) -> Unit,
    ): Either<T, LfsError> {
        while (true) {
            val lfsCredentials = credentialsStateManager.requestLfsCredentials(user, password = null)
            val res = callback(lfsCredentials.user, lfsCredentials.password)

            if (!res.isUnauthorizedError()) {
                if (res is Either.Ok) {
                    onAccepted(lfsCredentials.user, lfsCredentials.password)
                }

                return res
            }
        }
    }

    private fun <T> Either<T, LfsError>.isUnauthorizedError(): Boolean {
        return this is Either.Err &&
                this.error is LfsError.HttpError &&
                (this.error as LfsError.HttpError).code == HttpStatusCode.Unauthorized
    }
}

/**
 * The URL that git-lfs asks the credential helper about for [server] (`getCredURLForAPI` in git-lfs's
 * lfsapi/auth.go): the remote's when the server has its scheme, host and port, so that LFS gets the credentials that
 * git uses for the remote, and the server's otherwise.
 */
internal fun lfsCredentialsUri(server: LfsServer): URIish {
    val serverUri = URIish(server.url)
    val remoteUri = server.remoteUrl?.let { runCatching { URIish(it) }.getOrNull() } ?: return serverUri

    val sameServer = remoteUri.scheme.equals(serverUri.scheme, ignoreCase = true) &&
            remoteUri.host.equals(serverUri.host, ignoreCase = true) &&
            remoteUri.port == serverUri.port

    return if (sameServer) remoteUri else serverUri
}
