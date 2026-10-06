// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Buffer
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

class ProtobufPreferencesMigrationTest {
    @TempDir
    lateinit var dir: File

    private val theme = stringPreferencesKey("theme")
    private val scale = floatPreferencesKey("scale_ui")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val jsonFile: Path get() = File(dir, "user_prefs.json").toOkioPath()
    private val protobufFile: Path get() = File(dir, "user_prefs.preferences_pb").toOkioPath()
    private val migratedFile: Path get() = File(dir, "user_prefs.preferences_pb.migrated").toOkioPath()

    @AfterEach
    fun cancelScope() {
        scope.cancel()
    }

    /** Builds the store the same way as `DatastoreModule`. */
    private fun jsonStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, JsonPreferencesSerializer, producePath = { jsonFile }),
        migrations = listOf(ProtobufPreferencesMigration(protobufFile)),
        scope = scope,
    )

    private fun writeProtobufFile(preferences: Preferences) = runBlocking {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(preferences, buffer)
        FileSystem.SYSTEM.write(protobufFile) { writeAll(buffer) }
    }

    @Test
    fun `settings move from the protobuf file to the JSON file`(): Unit = runBlocking {
        writeProtobufFile(preferencesOf(theme to "DARK", scale to 1.25f))

        val data = jsonStore().data.first()

        assertEquals("DARK", data[theme])
        assertEquals(1.25f, data[scale])
        assertFalse(FileSystem.SYSTEM.exists(protobufFile), "the protobuf file is renamed")
        assertTrue(FileSystem.SYSTEM.exists(migratedFile), "the renamed protobuf file is kept")
        val json = FileSystem.SYSTEM.read(jsonFile) { readUtf8() }
        assertTrue(json.contains("\"theme\"") && json.contains("\"DARK\""), json)
    }

    @Test
    fun `without a protobuf file nothing is migrated`(): Unit = runBlocking {
        val store = jsonStore()
        store.edit { it[theme] = "LIGHT" }

        assertEquals("LIGHT", store.data.first()[theme])
        assertFalse(FileSystem.SYSTEM.exists(migratedFile))
    }

    @Test
    fun `values already in the JSON file win over the protobuf file`(): Unit = runBlocking {
        val json = Buffer()
        JsonPreferencesSerializer.writeTo(preferencesOf(theme to "LIGHT"), json)
        FileSystem.SYSTEM.write(jsonFile) { writeAll(json) }
        writeProtobufFile(preferencesOf(theme to "DARK", scale to 2f))

        val data = jsonStore().data.first()

        assertEquals("LIGHT", data[theme])
        assertEquals(2f, data[scale])
    }
}
