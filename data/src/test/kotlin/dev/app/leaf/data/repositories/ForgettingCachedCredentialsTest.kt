// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.credentials.ForgetCredentialsWhenCachingStops
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val HTTPS_URL = "git-credential:https://example.invalid/team/project.git"
private const val KEY = "ssh-key:/home/me/.ssh/id_ed25519"

/** Turning Settings' "Cache credentials in memory" off forgets what Leaf's in-memory cache holds (fork-only). */
class ForgettingCachedCredentialsTest {
    private val cache = CredentialsCacheRepository()

    /** The setting as stored: null until the user changes it, which reads as on. */
    private val setting = MutableStateFlow<Boolean?>(null)

    // Unconfined: the cache is forgotten before the setting's write returns, so that tests need no waiting
    private val watching = ForgetCredentialsWhenCachingStops(
        AppSettingsService(mockk<AppSettingsRepository> { every { cacheCredentialsInMemory } returns setting }),
        cache,
        CoroutineScope(Dispatchers.Unconfined),
    ).start()

    @AfterEach
    fun stop() {
        watching.cancel()
    }

    @Test
    fun `turning it off forgets the passphrases and HTTPS credentials kept`(): Unit = runBlocking {
        keepBoth()

        setting.value = false

        assertNull(cache.getCachedSshCredentials(KEY))
        assertNull(cache.getCachedHttpCredentials(HTTPS_URL, isLfs = false))
        assertNull(cache.getCachedHttpCredentials(HTTPS_URL, isLfs = true))
    }

    @Test
    fun `while it stays on, nothing is forgotten`(): Unit = runBlocking {
        keepBoth()

        setting.value = true

        assertEquals("phrase", cache.getCachedSshCredentials(KEY)?.password)
        assertEquals("pw", cache.getCachedHttpCredentials(HTTPS_URL, isLfs = false)?.password)
    }

    @Test
    fun `turned on again and off again, it forgets again`(): Unit = runBlocking {
        setting.value = false
        setting.value = true
        keepBoth()

        setting.value = false

        assertNull(cache.getCachedSshCredentials(KEY))
        assertNull(cache.getCachedHttpCredentials(HTTPS_URL, isLfs = false))
    }

    private suspend fun keepBoth() {
        cache.cacheSshCredentials(KEY, "phrase")
        cache.cacheHttpCredentials(HTTPS_URL, "bob", "pw", isLfs = false)
        cache.cacheHttpCredentials(HTTPS_URL, "bob", "lfs-pw", isLfs = true)
    }
}
