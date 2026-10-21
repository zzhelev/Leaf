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
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.ConventionalCommitsMode
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The commit field's "Conventional Commits suggestions" setting, through a real settings file (fork-only). */
class ConventionalCommitsSettingTest {
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
    fun `without a choice the suggestions are automatic`(): Unit = runBlocking {
        assertNull(repository().conventionalCommitsSuggestions.first())
        assertEquals(ConventionalCommitsMode.AUTOMATIC, AppSettingsService(repository()).conventionalCommitsSuggestions.first())
        assertEquals(CommitMessageHints(), AppSettingsService(repository()).commitMessageHints.first())
    }

    @Test
    fun `a choice stays after a restart, stored by its name, and reaches the field's hints`(): Unit = runBlocking {
        for (mode in ConventionalCommitsMode.entries) {
            repository().setConfiguration(AppConfig.ConventionalCommitsSuggestions(mode))

            assertTrue(settingsFile.readText().contains("\"${mode.name.lowercase()}\""), settingsFile.readText())
            assertEquals(mode, AppSettingsService(repository()).commitMessageHints.first().conventionalCommits)
        }
    }

    @Test
    fun `a value that this version doesn't know reads as no choice`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.ConventionalCommitsSuggestions(ConventionalCommitsMode.OFF))
        settingsFile.writeText(settingsFile.readText().replace("\"off\"", "\"sometimes\""))

        assertNull(repository().conventionalCommitsSuggestions.first())
        assertEquals(ConventionalCommitsMode.AUTOMATIC, AppSettingsService(repository()).conventionalCommitsSuggestions.first())
    }
}
