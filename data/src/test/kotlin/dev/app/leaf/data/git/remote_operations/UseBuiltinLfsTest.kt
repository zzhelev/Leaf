// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.IsolatedSystemReader
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [useBuiltinLfs] turns on JGit's built-in LFS in the cached repository's config for its block, and puts the setting
 * back however the block ends: the next `config.save()` would write it to `.git/config`.
 */
class UseBuiltinLfsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var git: Git

    private val repository: Repository get() = git.repository

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        git = Git.init().setDirectory(File(tempDir, "work")).call()
    }

    @AfterEach
    fun tearDown() {
        git.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the setting is on in the block, and gone after it`() {
        val result = useBuiltinLfs(repository) {
            assertTrue(useJGitBuiltin())
            "result"
        }

        assertEquals("result", result)
        assertFalse(isSet())
    }

    @Test
    fun `a block that throws leaves no setting behind`() {
        assertThrows(IllegalStateException::class.java) {
            useBuiltinLfs(repository) { error("The pull would overwrite local changes") }
        }

        assertFalse(isSet())
        repository.config.save()
        assertFalse(File(repository.directory, "config").readText().contains("usejgitbuiltin", ignoreCase = true))
    }

    @Test
    fun `a block that throws puts back the value set before`() {
        repository.config.setBoolean(
            ConfigConstants.CONFIG_FILTER_SECTION, "lfs", ConfigConstants.CONFIG_KEY_USEJGITBUILTIN, false,
        )

        assertThrows(IllegalStateException::class.java) {
            useBuiltinLfs(repository) { error("The pull would overwrite local changes") }
        }

        assertTrue(isSet())
        assertFalse(useJGitBuiltin())
    }

    private fun isSet() = repository.config
        .getNames(ConfigConstants.CONFIG_FILTER_SECTION, "lfs")
        .contains(ConfigConstants.CONFIG_KEY_USEJGITBUILTIN)

    private fun useJGitBuiltin() = repository.config.getBoolean(
        ConfigConstants.CONFIG_FILTER_SECTION, "lfs", ConfigConstants.CONFIG_KEY_USEJGITBUILTIN, false,
    )
}
