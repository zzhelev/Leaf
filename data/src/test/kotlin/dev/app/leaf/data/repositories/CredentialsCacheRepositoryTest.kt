// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.models.CredentialsType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val URL = "https://example.invalid/team/project.git"
private const val OTHER_URL = "https://example.invalid/team/other.git"

/** Leaf's in-memory cache of the HTTPS credentials that the server took, used when no credential helper is set. */
class CredentialsCacheRepositoryTest {
    private val cache = CredentialsCacheRepository()

    @Test
    fun `cached credentials are given back for their URL only`(): Unit = runBlocking {
        cache.cacheHttpCredentials(URL, "user", "pass=word", isLfs = false)

        assertEquals(credentials("user", "pass=word"), cache.getCachedHttpCredentials(URL, isLfs = false))
        assertNull(cache.getCachedHttpCredentials(OTHER_URL, isLfs = false))
    }

    @Test
    fun `caching credentials for a URL replaces the ones cached for it before`(): Unit = runBlocking {
        cache.cacheHttpCredentials(credentials("user", "old-password"))
        cache.cacheHttpCredentials(credentials("user", "new-password"))

        assertEquals(credentials("user", "new-password"), cache.getCachedHttpCredentials(URL, isLfs = false))
    }

    @Test
    fun `LFS credentials and git credentials for the same URL are cached apart`(): Unit = runBlocking {
        cache.cacheHttpCredentials(URL, "git-user", "git-password", isLfs = false)
        cache.cacheHttpCredentials(URL, "lfs-user", "lfs-password", isLfs = true)

        assertEquals(credentials("git-user", "git-password"), cache.getCachedHttpCredentials(URL, isLfs = false))
        assertEquals(
            credentials("lfs-user", "lfs-password", isLfs = true),
            cache.getCachedHttpCredentials(URL, isLfs = true),
        )
    }

    @Test
    fun `removed credentials are no longer cached, and other URLs keep theirs`(): Unit = runBlocking {
        cache.cacheHttpCredentials(credentials("user", "password"))
        cache.cacheHttpCredentials(OTHER_URL, "user", "password", isLfs = false)

        cache.removeCachedHttpCredentials(credentials("user", "password"))

        assertNull(cache.getCachedHttpCredentials(URL, isLfs = false))
        assertEquals(
            CredentialsType.HttpCredentials(OTHER_URL, "user", "password", isLfs = false),
            cache.getCachedHttpCredentials(OTHER_URL, isLfs = false),
        )
    }

    @Test
    fun `removing credentials that newer ones replaced keeps the newer ones`(): Unit = runBlocking {
        cache.cacheHttpCredentials(credentials("user", "old-password"))
        cache.cacheHttpCredentials(credentials("user", "new-password"))

        cache.removeCachedHttpCredentials(credentials("user", "old-password"))
        cache.removeCachedHttpCredentials(credentials("other-user", "new-password"))
        cache.removeCachedHttpCredentials(credentials("user", "new-password", isLfs = true))

        assertEquals(credentials("user", "new-password"), cache.getCachedHttpCredentials(URL, isLfs = false))
    }

    private fun credentials(user: String, password: String, isLfs: Boolean = false) =
        CredentialsType.HttpCredentials(URL, user, password, isLfs)
}
