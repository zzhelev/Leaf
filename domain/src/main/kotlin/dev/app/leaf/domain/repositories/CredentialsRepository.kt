package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.models.CredentialsType

interface CredentialsRepository {
    fun getCachedHttpCredentials(url: String, isLfs: Boolean): CredentialsType.HttpCredentials?
    fun getCachedSshCredentials(url: String): CredentialsType.SshCredentials?

    /** Caches [credentials] for their URL, replacing the credentials cached for it before. */
    suspend fun cacheHttpCredentials(credentials: CredentialsType.HttpCredentials)

    suspend fun cacheHttpCredentials(url: String, userName: String, password: String, isLfs: Boolean)

    /**
     * Removes [credentials], which the server rejected, from the cache. Like git's `store` helper, it removes them only
     * while they are still the ones cached for their URL, so that it doesn't drop newer credentials.
     */
    suspend fun removeCachedHttpCredentials(credentials: CredentialsType.HttpCredentials)

    suspend fun cacheSshCredentials(url: String, password: String)
}
