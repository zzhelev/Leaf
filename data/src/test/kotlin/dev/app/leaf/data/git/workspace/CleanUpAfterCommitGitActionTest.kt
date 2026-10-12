// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What Leaf removes after its commit, as git's commit does: a squash merge's message and a merge's leftovers. */
class CleanUpAfterCommitGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val cleanUp = CleanUpAfterCommitGitAction(testJGit())

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val gitDir by lazy { File(repository, ".git") }
    private val files = listOf("SQUASH_MSG", "MERGE_MSG", "MERGE_MODE", "AUTO_MERGE")

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `removes what git's commit removes`(): Unit = runBlocking {
        files.forEach { File(gitDir, it).writeText("left over\n") }

        assertEquals(Either.Ok(Unit), cleanUp(gitDir.path))

        files.forEach { assertFalse(File(gitDir, it).exists(), it) }
        assertTrue(File(gitDir, "HEAD").exists())
    }

    @Test
    fun `does nothing when there's nothing to remove`(): Unit = runBlocking {
        assertEquals(Either.Ok(Unit), cleanUp(gitDir.path))
    }

    @Test
    fun `keeps them while a rebase goes on, which keeps its message in MERGE_MSG`(): Unit = runBlocking {
        File(gitDir, "rebase-merge").mkdirs()
        files.forEach { File(gitDir, it).writeText("rebase\n") }

        assertEquals(Either.Ok(Unit), cleanUp(gitDir.path))

        files.forEach { assertTrue(File(gitDir, it).exists(), it) }
    }
}
