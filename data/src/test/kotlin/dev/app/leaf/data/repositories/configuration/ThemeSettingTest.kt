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
import dev.app.leaf.domain.models.ui.Theme
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The theme setting, with System, through the mapper and a real settings file (fork-only). */
class ThemeSettingTest {
    @TempDir
    lateinit var dir: File

    private val mapper = ThemeMapper()
    private val scopes = mutableListOf<CoroutineScope>()
    private val settingsFile get() = File(dir, "user_prefs.json")

    @AfterEach
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    /**
     * One settings store on the file, as `DatastoreModule` builds it. Each call is a fresh start of the app, so the
     * store of the one before is closed first: DataStore allows one store per file.
     */
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
            mapper,
            LinesHeightMapper(),
            AvatarProviderMapper(),
            TextDiffViewTypeMapper(),
        )
    }

    private fun writeTheme(value: String) {
        settingsFile.writeText("""{"theme": {"type": "string", "value": "$value"}}""")
    }

    @Test
    fun `every theme reads back as itself`() {
        for (theme in Theme.entries) {
            assertEquals(theme, mapper.toDomain(mapper.toData(theme)), "$theme")
        }
    }

    @Test
    fun `every theme is stored under a name of its own, and System as system`() {
        val names = Theme.entries.map { mapper.toData(it) }

        assertEquals(names.size, names.toSet().size, "$names")
        assertEquals("system", mapper.toData(Theme.System))
    }

    @Test
    fun `no choice stays no choice`() {
        assertNull(mapper.toData(null))
        assertNull(mapper.toDomain(null))
    }

    @Test
    fun `a value that no Leaf here knows reads as no choice instead of throwing`() {
        for (unknown in listOf("holographic", "", "SYSTEM", "Dark", " dark")) {
            assertNull(mapper.toDomain(unknown), "'$unknown'")
        }
    }

    @Test
    fun `System is saved and is still the choice after a restart`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.Theme(Theme.System))

        assertTrue(settingsFile.readText().contains("\"system\""), settingsFile.readText())
        assertEquals(Theme.System, repository().theme.first())
        assertEquals(Theme.System, AppSettingsService(repository()).theme.first())
    }

    @Test
    fun `every theme survives a restart through the settings service`(): Unit = runBlocking {
        for (theme in Theme.entries) {
            repository().setConfiguration(AppConfig.Theme(theme))

            assertEquals(theme, AppSettingsService(repository()).theme.first(), "$theme")
        }
    }

    @Test
    fun `changing from System to another theme replaces it`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.Theme(Theme.System))
        repository().setConfiguration(AppConfig.Theme(Theme.Light))

        assertEquals(Theme.Light, AppSettingsService(repository()).theme.first())
    }

    @Test
    fun `an unknown theme in the file reads as the default`(): Unit = runBlocking {
        writeTheme("holographic")

        assertNull(repository().theme.first())
        assertEquals(AppSettingsService.DEFAULT_THEME, AppSettingsService(repository()).theme.first())
    }

    @Test
    fun `without a theme in the file the default is still dark`(): Unit = runBlocking {
        assertEquals(Theme.Dark, AppSettingsService.DEFAULT_THEME)
        assertEquals(Theme.Dark, AppSettingsService(repository()).theme.first())
    }

    @Test
    fun `the files that earlier Leaf versions wrote still read`(): Unit = runBlocking {
        for ((stored, theme) in listOf("light" to Theme.Light, "dark" to Theme.Dark, "system" to Theme.System)) {
            writeTheme(stored)

            assertEquals(theme, AppSettingsService(repository()).theme.first(), stored)
        }
    }

    @Test
    fun `the custom theme, which styles replaced, reads as no choice, and never throws`(): Unit = runBlocking {
        writeTheme("custom")

        assertNull(repository().theme.first())
        assertEquals(AppSettingsService.DEFAULT_THEME, AppSettingsService(repository()).theme.first())
        assertNull(ThemeMapper().toDomain("custom"))
    }
}
