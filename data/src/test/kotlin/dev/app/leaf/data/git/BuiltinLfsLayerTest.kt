// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.git.config.IncludedConfig
import dev.app.leaf.data.git.remote_operations.useBuiltinLfs
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * JGit's built-in LFS switch in [IncludedConfigRepository]'s in-memory layer: JGit reads it, `config.save()` never
 * writes it, and the repository's own file still wins.
 */
class BuiltinLfsLayerTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    private lateinit var repository: IncludedConfigRepository

    private val configFile get() = File(tempDir, "repo/.git/config")

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        Git.init().setDirectory(File(tempDir, "repo")).setInitialBranch("main").call().close()
        repository = openWithIncludedConfig(File(tempDir, "repo/.git"), FS.DETECTED).repository as IncludedConfigRepository
    }

    @AfterEach
    fun tearDown() {
        repository.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `JGit reads the switch, and saving the config doesn't write it`() {
        repository.useBuiltinLfs(true)
        assertTrue(usesBuiltinLfs(repository))

        repository.config.setString("user", null, "name", "Someone")
        repository.config.save()

        assertTrue(configFile.readText().contains("Someone"))
        assertFalse(configFile.readText().contains("usejgitbuiltin", ignoreCase = true))
        assertTrue(usesBuiltinLfs(repository))
    }

    @Test
    fun `the switch turns off, and stays through a refresh of what includeIf names`() {
        repository.useBuiltinLfs(true)
        repository.useIncludedConfig(IncludedConfig(entries = emptyList(), files = emptyList()))
        assertTrue(usesBuiltinLfs(repository))

        repository.useBuiltinLfs(false)
        assertFalse(usesBuiltinLfs(repository))
    }

    @Test
    fun `a value in the repository's own config wins`() {
        repository.config.setBoolean(ConfigConstants.CONFIG_FILTER_SECTION, "lfs", "usejgitbuiltin", false)
        repository.config.save()

        repository.useBuiltinLfs(true)

        assertFalse(usesBuiltinLfs(repository))
    }

    @Test
    fun `a pull's own switch leaves the layer as it was, and writes nothing`() {
        repository.useBuiltinLfs(true)

        useBuiltinLfs(repository) {
            assertTrue(usesBuiltinLfs(repository))
        }
        repository.config.save()

        assertTrue(usesBuiltinLfs(repository))
        assertFalse(configFile.readText().contains("usejgitbuiltin", ignoreCase = true))

        repository.useBuiltinLfs(false)
        assertFalse(usesBuiltinLfs(repository))
    }

    private fun usesBuiltinLfs(repository: Repository): Boolean = repository.config.getBoolean(
        ConfigConstants.CONFIG_FILTER_SECTION,
        "lfs",
        ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
        false,
    )
}
