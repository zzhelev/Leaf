// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.log.headCommitHash
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Which row of the log is HEAD's (fork-only, upstream issue 255). The log marks the commit that `headCommitHash` names
 * for the tab's `currentBranch`, so these run a real tab on real repositories in each state, and compare with
 * `git rev-parse HEAD`: the marked rows are exactly the log's rows with that commit, which is one, or none when there is
 * no HEAD commit or the log hasn't loaded that far.
 */
@DisabledOnOs(OS.WINDOWS)
class MarkingHeadTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }

    private val main by lazy { File(tempDir, "main") }

    @BeforeEach
    fun isolateGit() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n[init]\n\tdefaultBranch = main\n")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    /** `main` with three commits, and `old` on the first of them. Returns the three commits' hashes, oldest first. */
    private fun createHistory(): List<String> {
        git.initRepository(main)
        git.run(main, "commit", "--allow-empty", "-m", "Second")
        git.run(main, "commit", "--allow-empty", "-m", "Third")
        git.run(main, "branch", "old", "HEAD~2")

        return git.run(main, "rev-list", "--reverse", "HEAD").lines().filter { it.isNotBlank() }
    }

    private fun head(directory: File = main) = git.run(directory, "rev-parse", "HEAD").trim()

    /** What the log marks once the tab shows [expectedHead]: that commit as the tab's HEAD, and the rows that match. */
    private suspend fun marked(expectedHead: String?, waitForRow: Boolean = true): Marked {
        val current = tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.hash == expectedHead }
        val log = tab.awaitLoaded(tab.repositoryData.log) { rows ->
            !waitForRow || expectedHead == null || rows.values.any { it.hash == expectedHead }
        }

        val head = headCommitHash(current)

        return Marked(head, current?.name, log.values.filter { it.hash == head }.map { it.hash }, log)
    }

    private class Marked(val head: String?, val branchName: String?, val rows: List<String>, val log: GraphCommits)

    @Test
    fun `marks the commit of the checked out branch, and moves with a checkout from a terminal`(): Unit = runBlocking {
        val (first, _, third) = createHistory()
        tab.openRepository(main.path)

        val before = marked(third)

        assertEquals(third, before.head)
        assertEquals("refs/heads/main", before.branchName)
        assertEquals(listOf(third), before.rows)

        git.run(main, "checkout", "old")
        tab.refreshData(DataToRefresh.ALL).join()

        val after = marked(first)

        assertEquals(first, after.head)
        assertEquals(listOf(first), after.rows)
        assertTrue(after.log.values.any { it.hash == third }, "the old tip is still in the log, unmarked")
    }

    @Test
    fun `a detached HEAD is a branch named HEAD, and its commit is marked`(): Unit = runBlocking {
        val (_, second, _) = createHistory()
        git.run(main, "checkout", "--detach", second)
        tab.openRepository(main.path)

        val shown = marked(second)

        assertEquals("HEAD", shown.branchName)
        assertEquals(second, shown.head)
        assertEquals(listOf(second), shown.rows)
    }

    @Test
    fun `a detached HEAD at a branch's tip marks that commit once, not once for the branch`(): Unit = runBlocking {
        val (_, _, third) = createHistory()
        git.run(main, "checkout", "--detach")
        tab.openRepository(main.path)

        val shown = marked(third)

        // main is at the same commit, but HEAD is what the tab has checked out
        assertEquals("HEAD", shown.branchName)
        assertEquals(listOf(third), shown.rows)
    }

    @Test
    fun `a commit made on a detached HEAD, which no branch has, is in the log and marked`(): Unit = runBlocking {
        createHistory()
        git.run(main, "checkout", "--detach")
        git.run(main, "commit", "--allow-empty", "-m", "Only HEAD has this")
        val loose = head()
        tab.openRepository(main.path)

        val shown = marked(loose)

        assertEquals(listOf(loose), shown.rows)
    }

    @Test
    fun `a linked worktree's tab marks that worktree's HEAD, not the main worktree's`(): Unit = runBlocking {
        val (_, _, third) = createHistory()
        val feature = File(tempDir, "feature")
        git.run(main, "worktree", "add", feature.path, "-b", "feature", "old")
        git.run(feature, "commit", "--allow-empty", "-m", "Feature work")
        val featureTip = head(feature)
        tab.openRepository(main.path)
        marked(third)

        tab.switchWorktree(feature.path).join()
        val inFeature = marked(featureTip)

        assertEquals("refs/heads/feature", inFeature.branchName)
        assertEquals(listOf(featureTip), inFeature.rows)
        assertNotEquals(third, inFeature.head)
        assertTrue(inFeature.log.values.any { it.hash == third }, "main's tip is in the log, as a branch another worktree uses")

        tab.switchWorktree(main.path).join()
        val back = marked(third)

        assertEquals(listOf(third), back.rows)
        assertTrue(back.log.values.any { it.hash == featureTip }, "feature's tip is in the log, unmarked")
    }

    @Test
    fun `a branch with no commit yet marks nothing, although the log has other commits`(): Unit = runBlocking {
        createHistory()
        git.run(main, "checkout", "--orphan", "fresh")
        tab.openRepository(main.path)

        val shown = marked(expectedHead = null)

        assertNull(shown.branchName)
        assertNull(shown.head)
        assertEquals(emptyList<String>(), shown.rows)
        assertTrue(shown.log.isNotEmpty(), "the other branches' commits are in the log")
    }

    @Test
    fun `a repository with no commit at all marks nothing`(): Unit = runBlocking {
        main.mkdirs()
        git.run(main, "init")
        tab.openRepository(main.path)

        val shown = marked(expectedHead = null)

        assertNull(shown.head)
        assertEquals(emptyList<String>(), shown.rows)
    }

    @Test
    fun `a rebase stopped at a conflict marks the commit HEAD is at`(): Unit = runBlocking {
        git.initRepository(main)
        File(main, "a.txt").writeText("base\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Add a")
        git.run(main, "checkout", "-b", "feature")
        File(main, "a.txt").writeText("feature\n")
        git.run(main, "commit", "-am", "Feature changes a")
        git.run(main, "checkout", "main")
        File(main, "a.txt").writeText("main\n")
        git.run(main, "commit", "-am", "Main changes a")
        git.run(main, "checkout", "feature")
        git.runFailing(main, "rebase", "main")
        val during = head()
        tab.openRepository(main.path)

        val shown = marked(during)

        assertEquals("HEAD", shown.branchName)
        assertEquals(listOf(during), shown.rows)
    }

    @Test
    fun `a bisect marks the commit it checked out`(): Unit = runBlocking {
        val (first, _, _) = createHistory()
        git.run(main, "commit", "--allow-empty", "-m", "Fourth")
        git.run(main, "bisect", "start")
        git.run(main, "bisect", "bad")
        git.run(main, "bisect", "good", first)
        val midway = head()
        tab.openRepository(main.path)

        val shown = marked(midway)

        assertNotEquals(first, midway)
        assertEquals(listOf(midway), shown.rows)
    }

    @Test
    fun `a HEAD older than what the log has loaded marks no row`(): Unit = runBlocking {
        val count = 2_100
        fastImport(main, count)
        val root = git.run(main, "rev-list", "--max-parents=0", "HEAD").trim()
        git.run(main, "checkout", "--detach", root)
        tab.openRepository(main.path)

        val shown = marked(root, waitForRow = false)
        // Waits for the log to have loaded as far as it goes: its first 2,000 commits, which leave the root out
        val log = tab.awaitLoaded(tab.repositoryData.log) { it.size >= 2_000 }

        assertNotNull(shown.head)
        assertEquals(root, shown.head)
        assertTrue(log.size < count, "the log is cut at ${log.size} of $count commits")
        assertEquals(emptyList<String>(), log.values.filter { it.hash == shown.head }.map { it.hash })
    }

    /** Fills [directory] with [count] empty commits, a minute apart, with git fast-import. */
    private fun fastImport(directory: File, count: Int) {
        directory.mkdirs()
        git.run(directory, "init")
        val stream = StringBuilder()

        for (i in 1..count) {
            val message = "Commit $i"
            stream.append("commit refs/heads/main\nmark :$i\ncommitter Leaf Test <test@example.invalid> ${1_700_000_000 + i * 60} +0000\n")
            stream.append("data ${message.length}\n$message\n")
            if (i > 1) stream.append("from :${i - 1}\n")
            stream.append("\n")
        }

        val input = File(tempDir, "stream.txt").apply { writeText(stream.toString()) }
        val process = ProcessBuilder("git", "fast-import", "--quiet")
            .directory(directory)
            .redirectInput(input)
            .redirectErrorStream(true)
            .also {
                it.environment()["GIT_CONFIG_GLOBAL"] = globalConfig.path
                it.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        check(process.waitFor() == 0) { "git fast-import failed: $output" }

        git.run(directory, "checkout", "-f", "main")
    }
}
