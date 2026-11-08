// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.app.leaf.data.UserSettingsDataStore
import dev.app.leaf.data.repositories.configuration.mappers.AvatarProviderMapper
import dev.app.leaf.data.repositories.configuration.mappers.LinesHeightMapper
import dev.app.leaf.data.repositories.configuration.mappers.TextDiffViewTypeMapper
import dev.app.leaf.data.repositories.configuration.mappers.ThemeMapper
import dev.app.leaf.domain.models.AppConfig
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
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Scale stored before 2026-11-08, the scale itself, moved on top of the screen's own (fork-only). */
class AbsoluteScaleMigrationTest {
    @TempDir
    lateinit var dir: File

    private val scaleUi = floatPreferencesKey("scale_ui")
    private val relativeScale = floatPreferencesKey("relative_scale")
    private val theme = stringPreferencesKey("theme")
    private val textSize = intPreferencesKey("text_size")
    private val scopes = mutableListOf<CoroutineScope>()

    private val jsonFile: Path get() = File(dir, "user_prefs.json").toOkioPath()
    private val json get() = FileSystem.SYSTEM.read(jsonFile) { readUtf8() }

    @AfterEach
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    private fun writeSettingsFile(preferences: Preferences) = runBlocking {
        FileSystem.SYSTEM.write(jsonFile) { JsonPreferencesSerializer.writeTo(preferences, this) }
    }

    /** The settings as Leaf reads them at its start, on a screen at [screenScale]. */
    private fun settings(screenScale: Float?): AppSettingsService {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also(scopes::add)
        val store = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, JsonPreferencesSerializer, producePath = { jsonFile }),
            migrations = listOf(RemovedSettingsMigration(), AbsoluteScaleMigration { screenScale }),
            scope = scope,
        )

        return AppSettingsService(
            DataStoreAppSettingsRepository(
                UserSettingsDataStore(store),
                ThemeMapper(),
                LinesHeightMapper(),
                AvatarProviderMapper(),
                TextDiffViewTypeMapper(),
            )
        )
    }

    /** Stops the settings store, as quitting Leaf does: DataStore allows one store a file at a time. */
    private fun restart() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    @Test
    fun `an old scale keeps its size on the screen that Leaf starts on, and the old key goes`(): Unit = runBlocking {
        // (stored, screen, now)
        for ((stored, screen, now) in listOf(Triple(2f, 2f, 1f), Triple(1.5f, 1f, 1.5f), Triple(1f, 2f, 0.5f))) {
            restart()
            writeSettingsFile(preferencesOf(scaleUi to stored, theme to "DARK", textSize to 15))

            assertEquals(now, settings(screen).scaleUi.first(), "$stored on $screen")
            assertFalse("\"scale_ui\"" in json, json)
            assertTrue("\"theme\"" in json && "\"text_size\"" in json, json)
        }
    }

    @Test
    fun `it happens once, so another screen at the next start changes nothing`(): Unit = runBlocking {
        writeSettingsFile(preferencesOf(scaleUi to 1.5f))
        assertEquals(1.5f, settings(1f).scaleUi.first())

        restart()
        assertEquals(1.5f, settings(2f).scaleUi.first())
    }

    @Test
    fun `an old scale that gives nothing right on the screen leaves the default`(): Unit = runBlocking {
        writeSettingsFile(preferencesOf(scaleUi to 0f, theme to "DARK"))

        assertEquals(1f, settings(2f).scaleUi.first())
        assertFalse("scale" in json, json)
    }

    @Test
    fun `a scale set by a newer Leaf wins over an old one`(): Unit = runBlocking {
        writeSettingsFile(preferencesOf(scaleUi to 1f, relativeScale to 1.25f))

        assertEquals(1.25f, settings(2f).scaleUi.first())
        assertFalse("\"scale_ui\"" in json, json)
    }

    @Test
    fun `without a screen the old scale waits, unread`(): Unit = runBlocking {
        writeSettingsFile(preferencesOf(scaleUi to 1f, theme to "DARK"))

        assertEquals(1f, settings(screenScale = null).scaleUi.first())
        assertTrue("\"scale_ui\"" in json, json)

        restart()
        assertEquals(0.5f, settings(2f).scaleUi.first())
    }

    @Test
    fun `a scale picked now is stored on top of the screen's, under its own key`(): Unit = runBlocking {
        val settings = settings(2f)
        settings.setConfiguration(AppConfig.ScaleUi(1.25f))

        assertEquals(1.25f, settings.scaleUi.first())
        assertTrue("\"relative_scale\"" in json && "\"scale_ui\"" !in json, json)

        restart()
        assertEquals(1.25f, settings(1f).scaleUi.first())
    }

    @Test
    fun `no scale reads as the screen's own, and one that can't be right too`(): Unit = runBlocking {
        assertEquals(1f, settings(2f).scaleUi.first())

        restart()
        writeSettingsFile(preferencesOf(relativeScale to -2f))
        assertEquals(1f, settings(2f).scaleUi.first())
    }
}
