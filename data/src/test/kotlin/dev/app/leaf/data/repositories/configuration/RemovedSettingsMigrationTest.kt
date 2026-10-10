// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RemovedSettingsMigrationTest {
    @TempDir
    lateinit var dir: File

    private val theme = stringPreferencesKey("theme")
    private val cacheCredentials = booleanPreferencesKey("cache_credentials_in_memory")
    private val proxyHostPassword = stringPreferencesKey("proxy_host_password")
    private val proxyPort = intPreferencesKey("proxy_port_number")
    private val verifySsl = booleanPreferencesKey("verify_ssl")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val jsonFile: Path get() = File(dir, "user_prefs.json").toOkioPath()

    @AfterEach
    fun cancelScope() {
        scope.cancel()
    }

    private fun writeSettingsFile(preferences: Preferences) = runBlocking {
        FileSystem.SYSTEM.write(jsonFile) { JsonPreferencesSerializer.writeTo(preferences, this) }
    }

    @Test
    fun `the removed settings leave the settings file, and the others stay`(): Unit = runBlocking {
        writeSettingsFile(
            preferencesOf(
                theme to "DARK",
                cacheCredentials to false,
                proxyHostPassword to "proxy-secret",
                proxyPort to 3128,
                verifySsl to false,
            )
        )

        // Built the same way as `DatastoreModule`, after its protobuf migration
        val store = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, JsonPreferencesSerializer, producePath = { jsonFile }),
            migrations = listOf(RemovedSettingsMigration()),
            scope = scope,
        )
        val data = store.data.first()

        assertEquals(preferencesOf(theme to "DARK", cacheCredentials to false), data)
        val json = FileSystem.SYSTEM.read(jsonFile) { readUtf8() }
        assertFalse(json.contains("proxy"), json)
        assertFalse(json.contains("verify_ssl"), json)
        assertTrue(json.contains("\"theme\""), json)
    }

    @Test
    fun `settings without removed keys aren't migrated`(): Unit = runBlocking {
        val migration = RemovedSettingsMigration()

        assertFalse(migration.shouldMigrate(preferencesOf(theme to "DARK", cacheCredentials to true)))
        assertTrue(migration.shouldMigrate(preferencesOf(theme to "DARK", verifySsl to true)))
    }
}
