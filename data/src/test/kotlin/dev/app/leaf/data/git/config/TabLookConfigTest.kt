// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.tabs.TabLook
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A tab's name and colour in the repository's Leaf file (upstream issue 242), which git config reads too. */
class TabLookConfigTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val store = TabLookConfig()

    private val repository by lazy { git.initRepository(File(tempDir, "app")) }
    private val gitDir by lazy { File(repository, ".git").canonicalPath }
    private val leafFile by lazy { File(gitDir, LocalConfigConstants.CONFIG_FILE_NAME) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `no file is no name and no colour`() {
        assertEquals(TabLook(), store.load(gitDir))
        assertFalse(leafFile.exists())
    }

    @Test
    fun `the name and colour are saved, and read back`() {
        store.save(gitDir, TabLook(name = "Acme app", color = 4))

        assertEquals(TabLook(name = "Acme app", color = 4), store.load(gitDir))
        assertEquals("Acme app", gitConfig("tab.name"))
        assertEquals("4", gitConfig("tab.color"))
        assertEquals(leafFile.path, store.fileOf(gitDir))
    }

    @Test
    fun `names that git config quotes or escapes are read back as typed`() {
        val names = listOf(
            "Acme \"app\"",
            "C:\\work\\app",
            "app # main ; old",
            "  leading spaces kept out",
            "Ünïcödé 名前 🌿",
        )

        for (name in names) {
            store.save(gitDir, TabLook(name = name))

            assertEquals(name.trim(), store.load(gitDir).name, name)
            assertEquals(name.trim(), gitConfig("tab.name"), name)
        }
    }

    @Test
    fun `the file's other settings stay`() {
        git.run(repository, "config", "-f", leafFile.path, "signoff.enabled", "true")
        git.run(repository, "config", "-f", leafFile.path, "sidePanel.expandedFolder", "feature")

        store.save(gitDir, TabLook(name = "Acme app", color = 1))
        store.save(gitDir, TabLook(color = 2))

        assertEquals("true", gitConfig("signoff.enabled"))
        assertEquals("feature", gitConfig("sidePanel.expandedFolder"))
        assertEquals(TabLook(color = 2), store.load(gitDir))
    }

    @Test
    fun `no name and no colour take the section out`() {
        git.run(repository, "config", "-f", leafFile.path, "signoff.enabled", "true")
        store.save(gitDir, TabLook(name = "Acme app", color = 1))

        store.save(gitDir, TabLook())

        assertFalse("[tab]" in leafFile.readText(), leafFile.readText())
        assertEquals("true", gitConfig("signoff.enabled"))
        assertEquals(TabLook(), store.load(gitDir))
    }

    @Test
    fun `values that can't be read are none, so the tab shows its folder's name`() {
        for (color in listOf("purple", "0", "7", "-1", "")) {
            leafFile.writeText("[tab]\n\tname = \"   \"\n\tcolor = \"$color\"\n")

            assertEquals(TabLook(), store.load(gitDir), "color = $color")
        }

        leafFile.writeText("[tab]\n\tcolor = \" 3 \"\n")
        assertEquals(TabLook(color = 3), store.load(gitDir))

        leafFile.writeText("[tab]\n\tname = \"Acme\\napp\"\n")
        assertEquals(TabLook(name = "Acme app"), store.load(gitDir))
    }

    @Test
    fun `a file that isn't a config file is no look, and the next save replaces it`() {
        leafFile.writeText("[tab\nname = Acme\n")

        assertEquals(TabLook(), store.load(gitDir))

        store.save(gitDir, TabLook(name = "Acme app"))

        assertEquals(TabLook(name = "Acme app"), store.load(gitDir))
        assertEquals("Acme app", gitConfig("tab.name"))
    }

    @Test
    fun `a git dir that is gone can't be saved to`() {
        val gone = File(tempDir, "gone/.git").path

        val failure = runCatching { store.save(gone, TabLook(name = "Acme app")) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException, failure.toString())
        assertEquals(TabLook(), store.load(gone))
    }

    private fun gitConfig(key: String): String = git.run(repository, "config", "-f", leafFile.path, key).trim()
}
