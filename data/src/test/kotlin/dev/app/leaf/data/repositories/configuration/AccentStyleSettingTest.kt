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
import dev.app.leaf.domain.models.ui.AccentStyle
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

/** Settings → Appearance → Style, through a real settings file (fork-only). */
class AccentStyleSettingTest {
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
        assertNull(repository().accentStyle.first())
        assertEquals(AccentStyle.TONAL, service().accentStyle.first())
    }

    @Test
    fun `a settings file from before the styles, without the key, is tonal and keeps its theme`(): Unit = runBlocking {
        settingsFile.writeText("""{"theme": {"type": "string", "value": "light"}}""")

        assertNull(repository().accentStyle.first())
        assertEquals(AccentStyle.TONAL, service().accentStyle.first())
        assertEquals(Theme.Light, service().theme.first())
    }

    @Test
    fun `each style stays after a restart, under its lower-case name`(): Unit = runBlocking {
        for (style in listOf(AccentStyle.CLASSIC, AccentStyle.QUIET, AccentStyle.TONAL)) {
            repository().setConfiguration(AppConfig.AccentStyle(style))

            assertTrue(
                settingsFile.readText().contains(""""accent_style"""") &&
                    settingsFile.readText().contains(""""${style.name.lowercase()}""""),
                settingsFile.readText(),
            )
            assertEquals(style, service().accentStyle.first(), "$style after a restart")
        }
    }

    @Test
    fun `the style is a setting of its own, apart from the theme`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.Theme(Theme.Custom))
        repository().setConfiguration(AppConfig.AccentStyle(AccentStyle.QUIET))

        assertEquals(Theme.Custom, service().theme.first())
        assertEquals(AccentStyle.QUIET, service().accentStyle.first(), "A custom theme keeps the style chosen")

        repository().setConfiguration(AppConfig.Theme(Theme.Dark))

        assertEquals(AccentStyle.QUIET, service().accentStyle.first(), "Changing the theme leaves the style")
    }

    @Test
    fun `a value that this version doesn't know reads as tonal, and never throws`(): Unit = runBlocking {
        for (stored in listOf("vivid", "", "TONAL", "Classic", " quiet", "quiet ", "system", "1d")) {
            storeRaw(stored)

            assertEquals(AccentStyle.TONAL, service().accentStyle.first(), "'$stored'")
            // The other settings still read
            assertEquals(AppSettingsService.DEFAULT_THEME, service().theme.first(), "'$stored': theme")
        }

        storeRaw("classic")

        assertEquals(AccentStyle.CLASSIC, service().accentStyle.first())
    }

    @Test
    fun `every style has a name of its own, and Tonal comes first in the menu`() {
        val names = AccentStyle.entries.map { it.name.lowercase() }

        assertEquals(names.size, names.toSet().size)
        assertEquals(listOf(AccentStyle.TONAL, AccentStyle.CLASSIC, AccentStyle.QUIET), AccentStyle.entries)
        assertEquals(AccentStyle.TONAL, AccentStyle.DEFAULT)

        for (style in AccentStyle.entries) {
            assertEquals(style, AccentStyle.fromStored(style.name.lowercase()))
        }

        assertEquals(AccentStyle.DEFAULT, AccentStyle.fromStored(null))
    }
}
