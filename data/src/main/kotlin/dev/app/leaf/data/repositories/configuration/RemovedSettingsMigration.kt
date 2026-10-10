// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences

/**
 * The keys of settings that Leaf no longer has:
 * - the proxy settings, which were never applied (the code that set the JVM's proxy was commented out). The git
 *   commands that Leaf runs use git's own `http.proxy`;
 * - "Do not verify SSL security" (`verify_ssl`), which nothing read; its switch changed the credential cache setting
 *   instead. git's `http.sslVerify` applies.
 */
val REMOVED_SETTINGS = setOf(
    "use_proxy",
    "proxy_use_auth",
    "proxy_type",
    "proxy_host_name",
    "proxy_port_number",
    "proxy_host_user",
    "proxy_host_password",
    "verify_ssl",
)

/**
 * Removes the [removed] settings from the settings file the first time Leaf reads it, so that nothing of them stays
 * behind, such as the proxy password, which was stored as plain text.
 */
class RemovedSettingsMigration(
    private val removed: Set<String> = REMOVED_SETTINGS,
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().keys.any { it.name in removed }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val preferences = currentData.toMutablePreferences()
        preferences.asMap().keys
            .filter { it.name in removed }
            .forEach { preferences.remove(it) }

        return preferences.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
