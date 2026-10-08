package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.credentials.CredentialSettings
import dev.app.leaf.data.git.credentials.HelperAnswer
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IProvideLfsCredentialsGitAction
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.repositories.CredentialsRepository
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import javax.inject.Inject

class ProvideLfsCredentialsGitAction @Inject constructor(
    private val credentialsCacheRepository: CredentialsRepository,
    private val credentialsStateManager: CredentialsStateManager,
    private val credentialHelpers: CredentialHelpers,
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
     * the helpers, and the ones the user typed are stored with them once the server takes them. When git knows the
     * user name, the user is asked only for the password, and when a helper gave only the password, only for the
     * user name.
     */
    private suspend fun <T> withHelper(
        helper: CredentialSettings,
        uri: URIish,
        unauthorized: Either<T, LfsError>,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError> {
        // After a rejection, like git-lfs, the user name that git knows rather than the rejected one
        val knownUser = when (val answer = withContext(Dispatchers.IO) { credentialHelpers.get(helper, uri) }) {
            is HelperAnswer.Credentials -> {
                val res = callback(answer.user, answer.password)

                if (!res.isUnauthorizedError()) {
                    return res
                }

                withContext(Dispatchers.IO) { credentialHelpers.erase(helper, uri, answer.user, answer.password) }
                helper.username
            }

            HelperAnswer.Failed -> return unauthorized
            is HelperAnswer.NotStored -> {
                answer.password?.let { password ->
                    withHelperPassword(helper, uri, password, callback)?.let { return it }
                }

                answer.user
            }
        }

        return askForCredentials(knownUser, callback) { user, password ->
            withContext(Dispatchers.IO) { credentialHelpers.send("store", helper, uri, user, password) }
        }
    }

    /**
     * Like git, asks only for the user name to go with the [password] that a helper gave without one. Like the helpers'
     * credentials, they are tried once: stored with the helpers if the server takes them, and erased if it rejects
     * them, which gives null.
     */
    private suspend fun <T> withHelperPassword(
        helper: CredentialSettings,
        uri: URIish,
        password: String,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError>? {
        val credentials = credentialsStateManager.requestLfsCredentials(user = null, password = password)
        val res = callback(credentials.user, credentials.password)

        if (!res.isUnauthorizedError()) {
            if (res is Either.Ok) {
                withContext(Dispatchers.IO) {
                    credentialHelpers.send("store", helper, uri, credentials.user, credentials.password)
                }
            }

            return res
        }

        withContext(Dispatchers.IO) { credentialHelpers.erase(helper, uri, credentials.user, credentials.password) }
        return null
    }

    /**
     * Without a helper: the credentials that Leaf cached for [url], then the user's, asked only for the password when
     * git knows the [user] name. Cached credentials that the server rejects are removed, and the ones the user typed
     * are cached once the server takes them.
     */
    private suspend fun <T> withCachedCredentials(
        url: String,
        user: String?,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError> {
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
