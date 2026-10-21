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
import dev.app.leaf.domain.models.DiffWhitespaceMode
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

/** The diff's "Ignore whitespace" and "Show whitespace characters", through a real settings file (fork-only). */
class DiffWhitespaceSettingTest {
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

    @Test
    fun `without a choice the diff shows every change and no whitespace marks`(): Unit = runBlocking {
        assertNull(repository().diffWhitespace.first())
        assertNull(repository().diffShowWhitespace.first())
        assertEquals(DiffWhitespaceMode.SHOW_ALL, service().diffWhitespace.first())
        assertEquals(false, service().diffShowWhitespace.first())
    }

    @Test
    fun `ignoring whitespace stays after a restart, under a key of its own`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.DiffWhitespace(DiffWhitespaceMode.IGNORE_ALL))

        assertTrue(settingsFile.readText().contains("diff_whitespace"), settingsFile.readText())
        assertEquals(DiffWhitespaceMode.IGNORE_ALL, service().diffWhitespace.first())
        // The marks are another setting
        assertEquals(false, service().diffShowWhitespace.first())

        repository().setConfiguration(AppConfig.DiffWhitespace(DiffWhitespaceMode.SHOW_ALL))

        assertEquals(DiffWhitespaceMode.SHOW_ALL, service().diffWhitespace.first())
    }

    @Test
    fun `the whitespace marks stay after a restart, and are independent of wrapping`(): Unit = runBlocking {
        repository().setConfiguration(AppConfig.DiffShowWhitespace(true))
        repository().setConfiguration(AppConfig.DiffWrapLines(false))

        assertTrue(settingsFile.readText().contains("diff_show_whitespace"), settingsFile.readText())
        assertEquals(true, service().diffShowWhitespace.first())
        assertEquals(false, service().diffWrapLines.first())
        assertEquals(DiffWhitespaceMode.SHOW_ALL, service().diffWhitespace.first())

        repository().setConfiguration(AppConfig.DiffShowWhitespace(false))

        assertEquals(false, service().diffShowWhitespace.first())
        assertEquals(false, service().diffWrapLines.first(), "Turning the marks off leaves wrapping alone")
    }

    @Test
    fun `a settings file from before these switches reads as the defaults`(): Unit = runBlocking {
        settingsFile.writeText(
            """{"diff_wrap_lines": {"type": "boolean", "value": false}, "diff_display_full_file": {"type": "boolean", "value": true}}""",
        )

        assertEquals(DiffWhitespaceMode.SHOW_ALL, service().diffWhitespace.first())
        assertEquals(false, service().diffShowWhitespace.first())
        assertEquals(false, service().diffWrapLines.first(), "The other diff settings are read as they were")
        assertEquals(true, service().diffDisplayFullFile.first())
    }

    @Test
    fun `a mode that this version doesn't know reads as the default`(): Unit = runBlocking {
        for (stored in listOf("ignore_amount", "", "IGNORE_ALL ", "-w")) {
            settingsFile.writeText("""{"diff_whitespace": {"type": "string", "value": "$stored"}}""")

            assertEquals(DiffWhitespaceMode.DEFAULT, service().diffWhitespace.first(), "'$stored'")
        }

        settingsFile.writeText("""{"diff_whitespace": {"type": "string", "value": "ignore_all"}}""")

        assertEquals(DiffWhitespaceMode.IGNORE_ALL, service().diffWhitespace.first())
    }

    @Test
    fun `every mode is stored under a name of its own and reads back as itself`() {
        val names = DiffWhitespaceMode.entries.map { it.name.lowercase() }

        assertEquals(names.size, names.toSet().size)

        for (mode in DiffWhitespaceMode.entries) {
            assertEquals(mode, DiffWhitespaceMode.fromStored(mode.name.lowercase()))
        }

        assertEquals(listOf(false, true), DiffWhitespaceMode.entries.map { it.ignoresWhitespace })
    }
}
