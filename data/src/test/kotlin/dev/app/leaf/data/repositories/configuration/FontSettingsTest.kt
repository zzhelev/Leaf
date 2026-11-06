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
import dev.app.leaf.domain.models.ui.FontSettings
import dev.app.leaf.domain.models.ui.InterfaceFont
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The interface font, the code font and its ligatures, through a real settings file (fork-only). */
class FontSettingsTest {
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

    private fun fonts(): FontSettings = runBlocking { AppSettingsService(repository()).fonts.first() }

    @Test
    fun `without a choice, Leaf draws with Inter and the bundled code font, without ligatures`() {
        assertEquals(FontSettings(), fonts())
    }

    @Test
    fun `each choice stays after a restart, under keys of their own`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.InterfaceFont(InterfaceFont.SYSTEM))
        repository().setConfiguration(AppConfig.CodeFont("JetBrains Mono"))
        repository().setConfiguration(AppConfig.CodeLigatures(true))

        val text = settingsFile.readText()
        assertTrue(text.contains("interface_font") && text.contains("code_font") && text.contains("code_ligatures"), text)
        assertEquals(FontSettings(InterfaceFont.SYSTEM, "JetBrains Mono", true), fonts())

        repository().setConfiguration(AppConfig.InterfaceFont(InterfaceFont.INTER))
        repository().setConfiguration(AppConfig.CodeLigatures(false))

        assertEquals(FontSettings(InterfaceFont.INTER, "JetBrains Mono", false), fonts())
    }

    @Test
    fun `picking the bundled code font takes the key out, so that a later default would apply`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.CodeFont("Menlo"))
        repository().setConfiguration(AppConfig.CodeFont(null))

        assertFalse(settingsFile.readText().contains("code_font"), settingsFile.readText())
        assertEquals(null, fonts().codeFont)

        repository().setConfiguration(AppConfig.CodeFont("   "))

        assertFalse(settingsFile.readText().contains("code_font"), settingsFile.readText())
    }

    @Test
    fun `a code font's name is stored trimmed`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.CodeFont("  Menlo  "))

        assertEquals("Menlo", fonts().codeFont)
    }

    @Test
    fun `an interface font that this version doesn't know reads as Inter`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.InterfaceFont(InterfaceFont.SYSTEM))
        settingsFile.writeText(settingsFile.readText().replace("\"system\"", "\"handwriting\""))

        assertEquals(InterfaceFont.INTER, fonts().interfaceFont)
    }
}
