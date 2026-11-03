// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Which commits a history has (fork-only, upstream issue 207): the log shows a stash under a branch filter when the
 * commit it was made on is in the shown history. `git merge-base --is-ancestor` is the oracle.
 */
class GetCommitsReachableGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val reachable = GetCommitsReachableGitAction(testJGit())

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val gitDir by lazy { File(repository, ".git").absolutePath }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    /** A commit on top of [parent], at [time] seconds, so that the test sets the order of commit times. */
    private fun commitAt(parent: String, time: Long, message: String): String =
        git.run(
            repository,
            mapOf("GIT_COMMITTER_DATE" to "$time +0000", "GIT_AUTHOR_DATE" to "$time +0000"),
            "commit-tree", "-p", parent, "-m", message, "$parent^{tree}",
        ).trim()

    private fun isAncestor(commit: String, tip: String): Boolean =
        git.run(repository, "rev-list", tip).lines().contains(commit)

    @Test
    fun `finds the commits that the history has, and not the others, as git does`(): Unit = runBlocking {
        val root = git.run(repository, "rev-parse", "HEAD").trim()
        val day = 24 * 60 * 60L
        val start = 1_800_000_000L
        val main = (1..5).fold(root) { parent, i -> commitAt(parent, start + i * day, "main $i") }
        val mainThird = git.run(repository, "rev-parse", "$main~2").trim()
        val side = commitAt(root, start + 2 * day, "side")
        val sideOnTop = commitAt(side, start + 6 * day, "side on top")

        val asked = listOf(mainThird, side, sideOnTop, root)
        val result = reachable(gitDir, listOf(main), asked)

        assertEquals(Either.Ok(asked.filterTo(HashSet()) { isAncestor(it, main) }), result)
        assertEquals(Either.Ok(setOf(mainThird, root)), result)
    }

    @Test
    fun `a parent older than its child by less than a day is still found, past that the walk has stopped`(): Unit =
        runBlocking {
            val root = git.run(repository, "rev-parse", "HEAD").trim()
            val hour = 60 * 60L
            val start = 1_800_000_000L

            // A clock that was wrong left a commit older than the stash's base, which comes after it
            val base = commitAt(root, start + 100 * hour, "the stash's base")
            val slightlyOlder = commitAt(base, start + 80 * hour, "a clock 20 hours behind")
            val farOlder = commitAt(base, start + 50 * hour, "a clock two days behind")
            val tipNear = commitAt(slightlyOlder, start + 120 * hour, "tip")
            val tipFar = commitAt(farOlder, start + 120 * hour, "tip")

            assertEquals(Either.Ok(setOf(base)), reachable(gitDir, listOf(tipNear), listOf(base)))
            assertEquals(Either.Ok(emptySet<String>()), reachable(gitDir, listOf(tipFar), listOf(base)))
        }

    @Test
    fun `a commit that the repository doesn't have is in no history`(): Unit = runBlocking {
        val head = git.run(repository, "rev-parse", "HEAD").trim()

        assertEquals(Either.Ok(emptySet<String>()), reachable(gitDir, listOf(head), listOf("0".repeat(40))))
    }

    @Test
    fun `the walk reads at most so many commits, and a commit further back is out of the shown history`() {
        val root = git.run(repository, "rev-parse", "HEAD").trim()
        val start = 1_800_000_000L
        val tip = (1..10).fold(root) { parent, i -> commitAt(parent, start + i * 60L, "commit $i") }
        val third = git.run(repository, "rev-parse", "$tip~2").trim()
        val ninth = git.run(repository, "rev-parse", "$tip~8").trim()

        Git.open(repository).use { git ->
            val read = { max: Int -> reachableCommits(EmptyCoroutineContext, git.repository, listOf(tip), listOf(third, ninth), max) }

            assertEquals(setOf(third, ninth), read(9))
            assertEquals(setOf(third), read(8))
            assertEquals(emptySet<String>(), read(2))
        }
    }
}
