// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Creating a repository ([InitLocalRepositoryGitAction]) returns its failure, which the tab then shows. */
class InitLocalRepositoryGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val action = InitLocalRepositoryGitAction()

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a new repository starts on main`(): Unit = runBlocking {
        val folder = File(tempDir, "new")

        assertEquals(Either.Ok(Unit), action(folder))

        assertEquals("ref: refs/heads/main\n", File(folder, ".git/HEAD").readText())
    }

    // Windows ignores the read-only flag on folders
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a folder that can't be written is an error, not an exception`(): Unit = runBlocking {
        val folder = File(tempDir, "read-only").apply { mkdirs() }
        folder.setWritable(false)

        try {
            val result = action(folder)

            assertTrue(result is Either.Err && result.error is GenericError, "$result")
            assertFalse(File(folder, ".git").exists())
        } finally {
            folder.setWritable(true)
        }
    }
}
