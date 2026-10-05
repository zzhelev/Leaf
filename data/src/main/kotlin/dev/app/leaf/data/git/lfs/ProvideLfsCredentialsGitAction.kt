package dev.app.leaf.data.git.lfs

import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IProvideLfsCredentialsGitAction
import dev.app.leaf.domain.repositories.CredentialsRepository
import io.ktor.http.*
import javax.inject.Inject

class ProvideLfsCredentialsGitAction @Inject constructor(
    private val credentialsCacheRepository: CredentialsRepository,
    private val credentialsStateManager: CredentialsStateManager,
) : IProvideLfsCredentialsGitAction {
    override suspend operator fun <T> invoke(
        url: String,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError> {
        var res: Either<T, LfsError>


        res = callback(null, null)

        if (!res.isUnauthorizedError()) {
            return res
        }

        val credentialsCached = credentialsCacheRepository.getCachedHttpCredentials(url, isLfs = true)

        if (credentialsCached != null) {
            res = callback(credentialsCached.user, credentialsCached.password)

            if (!res.isUnauthorizedError()) {
                return res
            }
        }

        do {
            val lfsCredentials = credentialsStateManager.requestLfsCredentials()
            res = callback(lfsCredentials.user, lfsCredentials.password)
        } while (res.isUnauthorizedError())

        return res
    }

    private fun <T> Either<T, LfsError>.isUnauthorizedError(): Boolean {
        return this is Either.Err &&
                this.error is LfsError.HttpError &&
                (this.error as LfsError.HttpError).code == HttpStatusCode.Unauthorized
    }
}