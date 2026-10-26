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
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.styles.CodeColours
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

/** The Code colours setting through a real settings file (fork-only). */
class CodeColoursSettingTest {
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

    private fun writeCodeColours(value: String) {
        settingsFile.writeText("""{"code_colours": {"type": "string", "value": "$value"}}""")
    }

    @Test
    fun `no choice is Leaf's own colours`(): Unit = runBlocking {
        assertNull(repository().codeColours.first())
        assertEquals(CodeColours.LEAF, AppSettingsService(repository()).codeColours.first())
    }

    @Test
    fun `every choice survives a restart, under its key`(): Unit = runBlocking {
        for (choice in CodeColours.entries) {
            repository().setConfiguration(AppConfig.CodeColours(choice))

            assertEquals(choice, AppSettingsService(repository()).codeColours.first(), "$choice")
            assertTrue(settingsFile.readText().contains("\"${choice.key}\""), settingsFile.readText())
        }
        assertEquals(listOf("leaf", "vscode", "intellij"), CodeColours.entries.map { it.key })
    }

    @Test
    fun `a value that this version doesn't know reads as Leaf's own colours`(): Unit = runBlocking {
        for (unknown in listOf("sublime", "", "VSCODE", "VS_CODE", " leaf")) {
            writeCodeColours(unknown)

            assertNull(repository().codeColours.first(), "'$unknown'")
            assertEquals(CodeColours.LEAF, AppSettingsService(repository()).codeColours.first(), "'$unknown'")
        }
    }
}
