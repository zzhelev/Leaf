// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The log's branch filter, saved in the repository's Leaf file (fork-only, upstream issue 207). git config is the
 * oracle for what the file holds.
 */
class LogBranchFilterGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val save = SaveLogBranchFilterGitAction(jgit)
    private val get = GetLogBranchFilterGitAction(jgit)

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val gitDir by lazy { File(repository, ".git").absolutePath }
    private val leafFile by lazy { File(gitDir, LocalConfigConstants.CONFIG_FILE_NAME) }

    private val agent = "refs/heads/claude/parser-cache"
    private val originBlame = "refs/remotes/origin/feat/blame"

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private fun leafConfig(vararg args: String) = git.run(repository, "config", "--file", leafFile.path, *args).trim()

    @Test
    fun `without a saved filter, the log shows every branch, and nothing is written`(): Unit = runBlocking {
        assertEquals(Either.Ok(NO_LOG_BRANCH_FILTER), get(gitDir))

        save(gitDir, NO_LOG_BRANCH_FILTER)

        assertFalse(leafFile.exists())
    }

    @Test
    fun `each kind of filter is saved and read back`(): Unit = runBlocking {
        val filters = listOf(
            LogBranchFilter.Only(setOf("refs/heads/main", agent, originBlame)),
            LogBranchFilter.Only(emptySet()),
            LogBranchFilter.All(setOf(agent, originBlame)),
            LogBranchFilter.Current,
            NO_LOG_BRANCH_FILTER,
        )

        for (filter in filters) {
            save(gitDir, filter)

            assertEquals(Either.Ok(filter), get(gitDir), filter.toString())
        }
    }

    @Test
    fun `the file holds what git config reads`(): Unit = runBlocking {
        save(gitDir, LogBranchFilter.Only(setOf(agent, originBlame)))

        assertEquals("only", leafConfig("log.branches"))
        assertEquals("$agent\n$originBlame", leafConfig("--get-all", "log.show"))

        save(gitDir, LogBranchFilter.All(setOf(agent)))

        assertEquals("all", leafConfig("log.branches"))
        assertEquals(agent, leafConfig("--get-all", "log.hide"))
        assertFalse(leafFile.readText().contains("show ="), leafFile.readText())
    }

    @Test
    fun `other sections stay, and showing every branch again removes the section`(): Unit = runBlocking {
        leafConfig("worktrees.baseBranch", "refs/heads/main")

        save(gitDir, LogBranchFilter.Current)
        assertEquals("refs/heads/main", leafConfig("worktrees.baseBranch"))

        save(gitDir, NO_LOG_BRANCH_FILTER)
        assertEquals("refs/heads/main", leafConfig("worktrees.baseBranch"))
        assertFalse(leafFile.readText().contains("[log]"), leafFile.readText())
    }

    @Test
    fun `a value it doesn't know is every branch, and names that aren't branches are left out`(): Unit = runBlocking {
        leafConfig("log.branches", "someday")
        assertEquals(Either.Ok(NO_LOG_BRANCH_FILTER), get(gitDir))

        leafConfig("log.branches", "only")
        leafConfig("--add", "log.show", "main")
        leafConfig("--add", "log.show", "refs/tags/v1")
        leafConfig("--add", "log.show", "refs/heads/")
        leafConfig("--add", "log.show", agent)

        assertEquals(Either.Ok(LogBranchFilter.Only(setOf(agent))), get(gitDir))
    }

    @Test
    fun `a renamed branch is renamed in the filter, and a filter without it is left alone`() {
        Git.open(repository).use { git ->
            LogBranchFilterConfig.save(git.repository, LogBranchFilter.All(setOf(agent, originBlame)))

            assertTrue(LogBranchFilterConfig.renamed(git.repository, agent, "refs/heads/claude/cache"))
            assertEquals(
                LogBranchFilter.All(setOf("refs/heads/claude/cache", originBlame)),
                LogBranchFilterConfig.load(git.repository),
            )

            LogBranchFilterConfig.save(git.repository, LogBranchFilter.Only(setOf(agent)))
            assertTrue(LogBranchFilterConfig.renamed(git.repository, agent, "refs/heads/claude/cache"))
            assertEquals(LogBranchFilter.Only(setOf("refs/heads/claude/cache")), LogBranchFilterConfig.load(git.repository))

            assertFalse(LogBranchFilterConfig.renamed(git.repository, "refs/heads/other", "refs/heads/elsewhere"))

            LogBranchFilterConfig.save(git.repository, LogBranchFilter.Current)
            assertFalse(LogBranchFilterConfig.renamed(git.repository, agent, "refs/heads/claude/cache"))
        }
    }
}
