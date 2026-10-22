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
import dev.app.leaf.domain.styles.BuiltInStyles
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

/** Settings → Appearance → Style, through a real settings file (fork-only): the selected style's id. */
class StyleSettingTest {
    @TempDir
    lateinit var dir: File

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
            LinesHeightMapper(),
            AvatarProviderMapper(),
            TextDiffViewTypeMapper(),
        )
    }

    private fun service() = AppSettingsService(repository())

    private fun storeRaw(value: String) {
        settingsFile.writeText("""{"accent_style": {"type": "string", "value": "$value"}}""")
    }

    @Test
    fun `a new install, without a settings file, is tonal`(): Unit = runBlocking {
        assertNull(repository().styleId.first())
        assertEquals(BuiltInStyles.TONAL_ID, service().styleId.first())
    }

    @Test
    fun `a settings file from before the styles, without the key, is tonal and keeps its theme`(): Unit = runBlocking {
        settingsFile.writeText("""{"theme": {"type": "string", "value": "light"}}""")

        assertNull(repository().styleId.first())
        assertEquals(BuiltInStyles.TONAL_ID, service().styleId.first())
        assertEquals(Theme.Light, service().theme.first())
    }

    @Test
    fun `the looks chosen before styles could be made are the built-in styles' ids`(): Unit = runBlocking {
        // accent_style held the look's name in lower case from 2026-10-21
        for (stored in listOf("tonal", "classic", "quiet")) {
            storeRaw(stored)

            assertEquals(stored, service().styleId.first())
            assertEquals(stored, BuiltInStyles.byId(stored)?.id)
        }
    }

    @Test
    fun `each style stays after a restart, under its id`(): Unit = runBlocking {
        // A file put in the folder by hand keeps its name as the id, capitals and spaces included
        for (id in listOf("classic", "teal", "solarized-dark-2", "My Style", "quiet", "tonal")) {
            repository().setConfiguration(AppConfig.Style(id))

            assertTrue(
                settingsFile.readText().contains(""""accent_style"""") && settingsFile.readText().contains(""""$id""""),
                settingsFile.readText(),
            )
            assertEquals(id, service().styleId.first(), "$id after a restart")
        }
    }

    @Test
    fun `the style is a setting of its own, apart from the theme`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.Theme(Theme.System))
        repository().setConfiguration(AppConfig.Style("teal"))

        assertEquals(Theme.System, service().theme.first())
        assertEquals("teal", service().styleId.first(), "The theme keeps the style chosen")

        repository().setConfiguration(AppConfig.Theme(Theme.Dark))

        assertEquals("teal", service().styleId.first(), "Changing the theme leaves the style")
    }

    @Test
    fun `any stored id reads as it is, and never stops the other settings from reading`(): Unit = runBlocking {
        // Which style an unknown id draws is the styles service's to say (Tonal)
        for (stored in listOf("vivid", "", "TONAL", " quiet", "system", "1d")) {
            storeRaw(stored)

            assertEquals(stored, service().styleId.first(), "'$stored'")
            assertEquals(AppSettingsService.DEFAULT_THEME, service().theme.first(), "'$stored': theme")
        }
    }
}
