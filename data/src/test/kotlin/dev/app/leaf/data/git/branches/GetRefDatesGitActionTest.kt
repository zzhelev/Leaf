// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.RefDates
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.inject.Provider

private const val JAN_1 = 1_767_225_600L // 2026-01-01T00:00:00Z
private const val FEB_1 = 1_769_904_000L
private const val MAR_1 = 1_772_323_200L
private const val APR_1 = 1_775_001_600L
private const val MAY_1 = 1_777_593_600L

class GetRefDatesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = GetRefDatesGitAction(JGit(Provider { error("Only used on Windows") }))

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads commit, tag and checkout dates`(): Unit = runBlocking {
        val repository = createRepository()

        val dates = load(File(repository, ".git"))

        assertEquals(JAN_1 * 1000, dates.commitTimes["refs/heads/main"])
        assertEquals(FEB_1 * 1000, dates.commitTimes["refs/heads/feature/a"])
        assertEquals(JAN_1 * 1000, dates.commitTimes["refs/heads/bugfix/b"])

        assertEquals(JAN_1 * 1000, dates.tagTimes["refs/tags/v1"], "Lightweight tag uses the commit date")
        assertEquals(MAR_1 * 1000, dates.tagTimes["refs/tags/v2"], "Annotated tag uses the tagger date")

        assertEquals(APR_1 * 1000, dates.lastCheckoutTimes["feature/a"])
        assertEquals(MAY_1 * 1000, dates.lastCheckoutTimes["main"])
        assertFalse(dates.lastCheckoutTimes.containsKey("bugfix/b"), "Never checked out")
    }

    @Test
    fun `reads remote branch dates`(): Unit = runBlocking {
        val upstream = createRepository()
        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)

        val dates = load(File(clone, ".git"))

        assertEquals(FEB_1 * 1000, dates.commitTimes["refs/remotes/origin/feature/a"])
        assertEquals(JAN_1 * 1000, dates.commitTimes["refs/remotes/origin/bugfix/b"])
    }

    @Test
    fun `reads the dates of a linked worktree`(): Unit = runBlocking {
        val repository = createRepository()
        val worktree = File(tempDir, "worktree")
        git.run(repository, env(APR_1 + 100), "worktree", "add", worktree.absolutePath, "-b", "agent")
        git.run(worktree, env(APR_1 + 200), "checkout", "bugfix/b")

        val dates = load(File(repository, ".git/worktrees/worktree"))

        assertEquals(FEB_1 * 1000, dates.commitTimes["refs/heads/feature/a"])
        assertEquals(JAN_1 * 1000, dates.commitTimes["refs/heads/agent"])
        assertEquals((APR_1 + 200) * 1000, dates.lastCheckoutTimes["bugfix/b"], "The worktree's own HEAD reflog")
    }

    private suspend fun load(gitDir: File): RefDates {
        val result = action(gitDir.absolutePath)

        check(result is Either.Ok) { "Loading failed: $result" }

        return result.value
    }

    /**
     * main (Jan 1) ← feature/a (Feb 1). Tags v1 (lightweight, main) and v2 (annotated Mar 1, feature/a). bugfix/b is
     * created without a checkout. feature/a is checked out on Apr 1 and main on May 1.
     */
    private fun createRepository(): File {
        val repository = File(tempDir, "repo")
        repository.mkdirs()
        git.run(repository, "init")
        File(repository, "a.txt").writeText("a\n")
        git.run(repository, "add", ".")
        git.run(repository, env(JAN_1), "commit", "-m", "First")

        git.run(repository, env(JAN_1), "branch", "bugfix/b")
        git.run(repository, env(JAN_1), "tag", "v1")

        git.run(repository, env(FEB_1), "checkout", "-b", "feature/a")
        File(repository, "b.txt").writeText("b\n")
        git.run(repository, "add", ".")
        git.run(repository, env(FEB_1), "commit", "-m", "Second")
        git.run(repository, env(MAR_1), "tag", "-a", "v2", "-m", "Release")

        git.run(repository, env(APR_1 - 10), "checkout", "main")
        git.run(repository, env(APR_1), "checkout", "feature/a")
        git.run(repository, env(MAY_1), "checkout", "main")

        return repository
    }

    private fun env(epochSeconds: Long) = mapOf(
        "GIT_COMMITTER_DATE" to "@$epochSeconds +0000",
        "GIT_AUTHOR_DATE" to "@$epochSeconds +0000",
    )
}
