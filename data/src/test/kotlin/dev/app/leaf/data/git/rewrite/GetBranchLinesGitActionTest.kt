// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.worktrees.GetWorktreeBaseBranchGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.MAX_LINE_COMMITS
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Every local branch's own line (fork-only, step A3 of `docs/fork/squash-and-rebase.md`), each checked against
 * `git rev-list --first-parent <branch> ^<base>`, and which of its commits its upstream has.
 */
@DisabledOnOs(OS.WINDOWS)
class GetBranchLinesGitActionTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(root, "config/global.gitconfig")) }
    private val jgit by lazy { testJGit() }
    private val action by lazy {
        GetBranchLinesGitAction(jgit, GetWorktreeBaseBranchGitAction(jgit), JGitCommitMapper(JGitIdentityMapper()))
    }
    private lateinit var work: File

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        work = File(root, "work")
        git.run(root, "init", "-q", work.path)
        commit("Base", "a.txt")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    private fun commit(message: String, file: String = message.lowercase().replace(' ', '-') + ".txt"): String {
        File(work, file).appendText("$message\n")
        git.run(work, "add", "-A")
        git.run(work, "commit", "-q", "-m", message)

        return hash("HEAD")
    }

    private fun hash(rev: String) = git.run(work, "rev-parse", rev).trim()

    private fun gitLine(branch: String, base: String = "main"): List<String> =
        git.run(work, "rev-list", "--first-parent", branch, "^$base").lines().filter { it.isNotBlank() }

    private suspend fun lines(): BranchLines = when (val result = action(File(work, ".git").path)) {
        is Either.Ok -> result.value
        is Either.Err -> fail("Expected the lines, got ${result.error}")
    }

    private fun BranchLines.of(branch: String) = lines.getValue("refs/heads/$branch")

    @Test
    fun `each branch's line is git's first-parent list of what the base lacks, newest first`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "feat")
        commit("One")
        commit("Two")
        git.run(work, "switch", "-q", "-c", "stacked")
        commit("Three")
        git.run(work, "switch", "-q", "main")
        commit("Main moves on")

        val lines = lines()

        assertEquals("refs/heads/main", lines.base)
        for (branch in listOf("feat", "stacked")) {
            assertEquals(gitLine(branch), lines.of(branch).commits.map { it.hash }, branch)
        }
        assertEquals(listOf("Two", "One"), lines.of("feat").commits.map { it.shortMessage })
        assertEquals(emptyList<String>(), lines.of("main").commits)
        assertTrue(hash("main") in lines.baseCommits && hash("main~1") in lines.baseCommits)
        assertFalse(hash("feat") in lines.baseCommits)
    }

    @Test
    fun `a merge of the base into the branch is on its line, the base's commits aren't`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "feat")
        commit("One")
        git.run(work, "switch", "-q", "main")
        commit("Main moves on")
        git.run(work, "switch", "-q", "feat")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge main", "main")
        commit("Two")

        val line = lines().of("feat")

        assertEquals(gitLine("feat"), line.commits.map { it.hash })
        assertEquals(listOf("Two", "Merge main", "One"), line.commits.map { it.shortMessage })
        assertEquals(2, line.commits[1].parentCount)
    }

    @Test
    fun `commits that the base merged in aren't the branch's own any more`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "feat")
        val one = commit("One")
        git.run(work, "switch", "-q", "main")
        commit("Main moves on")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge feat", "feat")
        git.run(work, "switch", "-q", "feat")
        commit("Two")

        val line = lines().of("feat")

        assertEquals(gitLine("feat"), line.commits.map { it.hash })
        assertEquals(listOf("Two"), line.commits.map { it.shortMessage })
        assertFalse(line.has(one))
    }

    @Test
    fun `the commits that the upstream has are pushed`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "feat")
        val one = commit("One")
        val two = commit("Two")
        commit("Three")
        // A remote's fetch refspec maps its branches to remote-tracking ones, which the upstream's name comes from
        git.run(work, "remote", "add", "origin", File(root, "nowhere.git").path)
        git.run(work, "update-ref", "refs/remotes/origin/feat", two)
        git.run(work, "config", "branch.feat.remote", "origin")
        git.run(work, "config", "branch.feat.merge", "refs/heads/feat")

        val line = lines().of("feat")

        assertEquals("refs/remotes/origin/feat", line.upstream)
        assertEquals(setOf(one, two), line.pushed)

        // A branch without an upstream has nothing pushed
        git.run(work, "branch", "other", "feat")
        assertNull(lines().of("other").upstream)
        assertEquals(emptySet<String>(), lines().of("other").pushed)
    }

    @Test
    fun `with a remote base, the local branch of its name has its unpushed commits as its own`(): Unit = runBlocking {
        git.run(work, "update-ref", "refs/remotes/origin/main", hash("main"))
        git.run(work, "config", "--file", ".git/leaf", "worktrees.baseBranch", "refs/remotes/origin/main")
        val unpushed = commit("Not pushed yet")

        val lines = lines()

        assertEquals("refs/remotes/origin/main", lines.base)
        assertEquals(listOf(unpushed), lines.of("main").commits.map { it.hash })
        assertEquals(gitLine("main", base = "origin/main"), lines.of("main").commits.map { it.hash })
    }

    @Test
    fun `without a base, a line goes down to the first commit`(): Unit = runBlocking {
        git.run(work, "branch", "-m", "main", "trunk")
        commit("Two")

        val lines = lines()

        assertNull(lines.base)
        assertEquals(listOf("Two", "Base"), lines.of("trunk").commits.map { it.shortMessage })
        assertFalse(lines.of("trunk").isTruncated)
    }

    @Test
    fun `a line longer than Leaf reads is cut, and says so`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "long")
        val count = MAX_LINE_COMMITS + 100
        val stream = buildString {
            for (n in 1..count) {
                val message = "Commit $n"
                append("commit refs/heads/long\n")
                append("committer Leaf Test <test@example.invalid> ${1_700_000_000 + n} +0000\n")
                append("data ${message.length}\n$message\n")
                if (n == 1) append("from ${hash("long")}\n")
                append("M 644 inline f.txt\ndata ${message.length + 1}\n$message\n\n")
            }
        }
        git.runWithInput(work, stream, "fast-import", "--quiet")

        val line = lines().of("long")

        assertEquals(MAX_LINE_COMMITS, line.commits.size)
        assertTrue(line.isTruncated)
        assertEquals("Commit $count", line.commits.first().shortMessage)
    }
}
