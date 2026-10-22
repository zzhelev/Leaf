// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.app.leaf.data.UserSettingsDataStore
import dev.app.leaf.data.repositories.configuration.mappers.AvatarProviderMapper
import dev.app.leaf.data.repositories.configuration.mappers.LinesHeightMapper
import dev.app.leaf.data.repositories.configuration.mappers.TextDiffViewTypeMapper
import dev.app.leaf.data.repositories.configuration.mappers.ThemeMapper
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The lists spacing setting through the mapper and a real settings file (fork-only). */
class LinesHeightSettingTest {
    @TempDir
    lateinit var dir: File

    private val mapper = LinesHeightMapper()
    private val scopes = mutableListOf<CoroutineScope>()
    private val settingsFile get() = File(dir, "user_prefs.json")

    @AfterEach
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    /** One settings store on the file, as `DatastoreModule` builds it. Each call is a fresh start of the app. */
    private fun repository(): DataStoreAppSettingsRepository {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        scopes.clear()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also(scopes::add)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                FileSystem.SYSTEM,
                JsonPreferencesSerializer,
                producePath = { settingsFile.toOkioPath() },
            ),
            scope = scope,
        )

        return DataStoreAppSettingsRepository(
            UserSettingsDataStore(store),
            ThemeMapper(),
            mapper,
            AvatarProviderMapper(),
            TextDiffViewTypeMapper(),
        )
    }

    private fun writeLinesHeight(value: String) {
        settingsFile.writeText("""{"lines_height": {"type": "string", "value": "$value"}}""")
    }

    @Test
    fun `every spacing reads back as itself`() {
        for (type in LinesHeightType.entries) {
            assertEquals(type, mapper.toDomain(mapper.toData(type)), "$type")
        }
    }

    @Test
    fun `a value that no Leaf here knows reads as no choice instead of throwing`() {
        for (unknown in listOf("comfortable", "", "DENSE", "Compact", " spaced")) {
            assertNull(mapper.toDomain(unknown), "'$unknown'")
        }
    }

    @Test
    fun `an unknown spacing in the file reads as the default`(): Unit = runBlocking {
        writeLinesHeight("comfortable")

        assertNull(repository().linesHeightType.first())
        assertEquals(AppSettingsService.DEFAULT_LINES_HEIGHT, AppSettingsService(repository()).linesHeightType.first())
    }

    @Test
    fun `the values that earlier Leaf versions wrote still read`(): Unit = runBlocking {
        val stored = listOf("spaced" to LinesHeightType.SPACED, "compact" to LinesHeightType.COMPACT, "dense" to LinesHeightType.DENSE)
        for ((value, type) in stored) {
            writeLinesHeight(value)

            assertEquals(type, AppSettingsService(repository()).linesHeightType.first(), value)
        }
    }

    @Test
    fun `every spacing survives a restart`(): Unit = runBlocking {
        for (type in LinesHeightType.entries) {
            repository().setConfiguration(AppConfig.LinesHeight(type))

            assertEquals(type, AppSettingsService(repository()).linesHeightType.first(), "$type")
        }
    }
}
