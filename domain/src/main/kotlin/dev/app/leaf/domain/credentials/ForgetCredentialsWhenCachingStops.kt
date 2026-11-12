// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.credentials

import dev.app.leaf.domain.repositories.CredentialsRepository
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forgets what Leaf keeps in memory, HTTPS credentials and SSH key passphrases, once Settings' "Cache credentials in
 * memory" is turned off, so that none of it stays behind until Leaf quits. While the setting is off, nothing is kept
 * or read: each of the cache's users checks it.
 */
@Singleton
class ForgetCredentialsWhenCachingStops(
    private val appSettingsService: AppSettingsService,
    private val credentialsRepository: CredentialsRepository,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(appSettingsService: AppSettingsService, credentialsRepository: CredentialsRepository) :
            this(appSettingsService, credentialsRepository, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Watches the setting for as long as Leaf runs. */
    fun start(): Job = scope.launch {
        // Settings re-emit every value whenever any is written; while it's off, the cache is empty anyway
        appSettingsService.cacheCredentialsInMemory
            .filter { isOn -> !isOn }
            .collect { credentialsRepository.removeAllCachedCredentials() }
    }
}
