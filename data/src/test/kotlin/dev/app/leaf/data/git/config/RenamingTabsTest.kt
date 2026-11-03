// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.tabs.TabLook
import dev.app.leaf.domain.tabs.TabLooks
import dev.app.leaf.domain.tabs.tabNameOf
import dev.app.leaf.domain.tabs.titleWith
import dev.app.leaf.domain.worktrees.gitDirOf
import dev.app.leaf.domain.worktrees.tabNamesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A repository's tab name and colour (upstream issue 242), through [TabLooks] and the Leaf file, as the tabs use them:
 * every worktree's tab shows them, a tab that switches worktrees keeps them, and they come back after a restart.
 */
class RenamingTabsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    private val main by lazy { git.initRepository(File(tempDir, "app")) }
    private val linked by lazy {
        File(tempDir, "app-fix-login").also { git.run(main, "worktree", "add", it.path, "-b", "fix-login") }
    }

    /** The git dir of a tab open on [folder], as `RepositorySelectionState.Open.path` has it and the tabs save it. */
    private fun tabPath(folder: File) = gitDirOf(folder.path)

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `every worktree's tab shows the repository's name and colour, and keeps them after a switch`(): Unit =
        runBlocking {
            val looks = TabLooks(TabLookConfig())
            val linkedPath = tabPath(linked)

            looks.update(tabPath(main)) { it.copy(name = "Acme app", color = 4) }

            // A tab open on the linked worktree, and the same tab once it switched to the main worktree
            assertEquals(TabLook(name = "Acme app", color = 4), looks.lookOf(linkedPath).first())
            assertEquals(TabLook(name = "Acme app", color = 4), looks.lookOf(tabPath(main)).first())
            assertEquals("Acme app", tabNamesOf(linkedPath).titleWith(looks.lookOf(linkedPath).first()))
            assertEquals("app-fix-login", tabNamesOf(linkedPath).worktree)

            // Renamed from the linked worktree's tab, saved for the repository
            looks.update(linkedPath) { it.copy(name = "Acme") }

            assertEquals("Acme", looks.lookOf(tabPath(main)).first().name)
            assertEquals("Acme", git.run(main, "config", "-f", ".git/leaf", "tab.name").trim())
            assertTrue(File(main, ".git/leaf").isFile)
            assertTrue(!File(linkedPath, "leaf").exists(), "Not in the linked worktree's own git dir")
        }

    @Test
    fun `a rename comes back after a restart, through the saved tabs`(): Unit = runBlocking {
        val before = TabLooks(TabLookConfig())
        before.update(tabPath(main)) { it.copy(name = "Acme app") }
        before.update(tabPath(linked)) { it.copy(color = 2) }

        // As AppViewModel saves the tabs and reads them at the next start
        val saved = Json.encodeToString(listOf(tabPath(main), tabPath(linked)))
        val restored = Json.decodeFromString<List<String>>(saved)
        val after = TabLooks(TabLookConfig())

        for (path in restored) {
            assertEquals(TabLook(name = "Acme app", color = 2), after.lookOf(path).first(), path)
        }

        // A tab saved by its working tree, as one that hadn't loaded yet keeps the path it was opened with
        assertEquals(TabLook(name = "Acme app", color = 2), after.lookOf(linked.path).first())
    }

    @Test
    fun `typing the folder's name, or nothing, goes back to the folder's name`(): Unit = runBlocking {
        val looks = TabLooks(TabLookConfig())
        val names = tabNamesOf(tabPath(main))

        looks.update(tabPath(main)) { it.copy(name = tabNameOf("Acme app", names.repository), color = 1) }
        looks.update(tabPath(main)) { it.copy(name = tabNameOf(" app ", names.repository)) }

        assertEquals(TabLook(color = 1), looks.lookOf(tabPath(main)).first())
        assertEquals("app", names.titleWith(looks.lookOf(tabPath(main)).first()))

        looks.update(tabPath(main)) { it.copy(color = null) }

        assertTrue("[tab]" !in File(main, ".git/leaf").readText())
    }

    @Test
    fun `a damaged file falls back to the folder's name`(): Unit = runBlocking {
        File(main, ".git/leaf").writeText("[tab\n\tname = Acme app\n")

        val look = TabLooks(TabLookConfig()).lookOf(tabPath(main)).first()

        assertEquals(TabLook(), look)
        assertEquals("app", tabNamesOf(tabPath(main)).titleWith(look))
    }

    @Test
    fun `two repositories named app keep their own names`(): Unit = runBlocking {
        val other = git.initRepository(File(tempDir, "other/app"))
        val looks = TabLooks(TabLookConfig())

        looks.update(tabPath(main)) { it.copy(name = "Acme app", color = 4) }
        looks.update(tabPath(other)) { it.copy(name = "Globex app", color = 1) }

        assertEquals(TabLook(name = "Acme app", color = 4), looks.lookOf(tabPath(main)).first())
        assertEquals(TabLook(name = "Globex app", color = 1), looks.lookOf(tabPath(other)).first())
    }

    @Test
    fun `a bare repository keeps its look in its own folder`(): Unit = runBlocking {
        val bare = File(tempDir, "app.git")
        git.run(tempDir, "clone", "--bare", main.path, bare.path)
        val looks = TabLooks(TabLookConfig())

        looks.update(bare.path) { it.copy(name = "Acme app") }

        assertEquals("Acme app", git.run(bare, "config", "-f", "leaf", "tab.name").trim())
        assertEquals("Acme app", TabLooks(TabLookConfig()).lookOf(bare.path).first().name)
    }
}
