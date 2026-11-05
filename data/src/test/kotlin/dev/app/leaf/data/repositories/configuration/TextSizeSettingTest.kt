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
import dev.app.leaf.domain.models.ui.TextSize
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

/** The text size setting through a real settings file (fork-only). */
class TextSizeSettingTest {
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

    @Test
    fun `no text size in the file reads as the default`(): Unit = runBlocking {
        assertNull(repository().textSize.first())
        assertEquals(TextSize.DEFAULT, AppSettingsService(repository()).textSize.first())
    }

    @Test
    fun `every size survives a restart, as text_size`(): Unit = runBlocking {
        for (size in TextSize.CHOICES) {
            repository().setConfiguration(AppConfig.TextSize(size))

            assertEquals(size, AppSettingsService(repository()).textSize.first(), "$size")
            assertTrue(settingsFile.readText().contains("text_size"), settingsFile.readText())
        }
    }

    @Test
    fun `a size in the file that isn't a choice reads as the default`(): Unit = runBlocking {
        for (stored in listOf(0, 10, 19, 99)) {
            settingsFile.writeText("""{"text_size": {"type": "int", "value": $stored}}""")

            assertEquals(stored, repository().textSize.first(), "$stored")
            assertEquals(TextSize.DEFAULT, AppSettingsService(repository()).textSize.first(), "$stored")
        }
    }

    @Test
    fun `the text size and the scale are stored apart`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.ScaleUi(1.5f))
        repository().setConfiguration(AppConfig.TextSize(16))

        val settings = AppSettingsService(repository())
        assertEquals(1.5f, settings.scaleUi.first())
        assertEquals(16, settings.textSize.first())
    }
}
