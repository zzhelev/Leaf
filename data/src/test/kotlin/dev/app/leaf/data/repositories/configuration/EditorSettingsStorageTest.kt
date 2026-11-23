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
import dev.app.leaf.domain.editor.CUSTOM_EDITOR
import dev.app.leaf.domain.editor.EditorSettings
import dev.app.leaf.domain.editor.LinkedWorktreeOpening
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
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Settings › Editor through a real settings file (fork-only). */
class EditorSettingsStorageTest {
    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()
    private val settingsFile get() = File(dir, "user_prefs.json")

    @AfterEach
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    /** One settings store on the file, as `DatastoreModule` builds it. Each call is a fresh start of the app. */
    private fun service(): AppSettingsService {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        scopes.clear()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also(scopes::add)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, JsonPreferencesSerializer, producePath = { settingsFile.toOkioPath() }),
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

    @Test
    fun `nothing stored is no editor, the worktree's window and no repository of its own`(): Unit = runBlocking {
        assertEquals(EditorSettings(), service().editorSettings.first())
    }

    @Test
    fun `every editor setting survives a restart`(): Unit = runBlocking {
        val editors = mapOf("/Users/me/website/.git" to "vscode", "/Users/me/leaf/.git" to "intellij-idea")

        service().apply {
            setConfiguration(AppConfig.Editor(CUSTOM_EDITOR))
            setConfiguration(AppConfig.EditorCommand("my-editor --line {line} {file}"))
            setConfiguration(AppConfig.EditorLinkedWorktree(LinkedWorktreeOpening.FILE_ALONE))
            setConfiguration(AppConfig.RepositoryEditors(editors))
        }

        assertEquals(
            EditorSettings(CUSTOM_EDITOR, "my-editor --line {line} {file}", LinkedWorktreeOpening.FILE_ALONE, editors),
            service().editorSettings.first(),
        )
    }

    @Test
    fun `taking the editor back to none removes its key`(): Unit = runBlocking {
        service().setConfiguration(AppConfig.Editor("zed"))
        service().setConfiguration(AppConfig.Editor(null))

        assertEquals(null, service().editorSettings.first().editor)
        assertFalse("\"editor\"" in settingsFile.readText(), settingsFile.readText())
    }

    @Test
    fun `values that this version doesn't know read as the defaults`(): Unit = runBlocking {
        settingsFile.writeText(
            """
            {
              "editor_linked_worktree": {"type": "string", "value": "a later one"},
              "repository_editors": {"type": "string", "value": "not json"}
            }
            """.trimIndent()
        )

        assertEquals(EditorSettings(), service().editorSettings.first())
    }
}
