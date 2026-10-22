// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.cherrypick.BranchAhead
import dev.app.leaf.domain.cherrypick.BranchCherryPick
import dev.app.leaf.domain.cherrypick.CherryPickRefusal
import dev.app.leaf.domain.cherrypick.cherryPickPlan
import dev.app.leaf.domain.cherrypick.refusal
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab cherry-picks a branch's commits from its dialog (fork-only), and goes on with git's sequencer where git stopped:
 * Continue, Skip and Abort. Each result is checked in the repository with git.
 */
@DisabledOnOs(OS.WINDOWS)
class CherryPickingBranchTest {
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

    private val repository by lazy { File(tempDir, "repo") }
    private val gitDir get() = File(repository, ".git")
    private lateinit var start: String
    private lateinit var fix: Branch

    @BeforeEach
    fun createRepository() {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))
        globalConfig.parentFile.mkdirs()
        // git refuses to make an identity up on Linux
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        git.initRepository(repository)
        commit("base", "a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n")
        git.run(repository, "switch", "-q", "-c", "fix")
        commit("fix: one", "a.txt" to "a fix\n")
        commit("fix: two", "b.txt" to "b fix\n")
        commit("WIP: debug log", "c.txt" to "c log\n")
        git.run(repository, "switch", "-q", "main")
        commit("main: own", "d.txt" to "d\n")
        start = head()
        fix = Branch(git.run(repository, "rev-parse", "fix").trim(), "refs/heads/fix", isLocal = true)
    }

    @AfterEach
    fun tearDown() {
        if (builtAskpassHelper() != null) tab.close()
        SystemReader.setInstance(originalReader)
    }

    private fun commit(message: String, vararg files: Pair<String, String>) {
        for ((path, text) in files) {
            File(repository, path).writeText(text)
        }

        git.run(repository, "add", "-A")
        git.run(repository, "commit", "-q", "-m", message)
    }

    private fun head() = git.run(repository, "rev-parse", "HEAD").trim()

    private fun subjects(count: Int) = git.run(repository, "log", "--format=%s", "-$count").trim().lines()

    private suspend fun open(): BranchCherryPick {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)

        return (tab.getBranchCherryPick(fix) as Either.Ok).value
    }

    private val BranchCherryPick.firstTwo get() = pickable.take(2).map { it.hash }.toSet()

    private suspend fun lastTask(): CompletedTask = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()

    @Test
    fun `the ticked commits become new commits on the current branch, in the branch's order`(): Unit = runBlocking {
        val pick = open()

        assertEquals(listOf("fix: one", "fix: two", "WIP: debug log"), pick.pickable.map { it.shortMessage })
        assertEquals(null, pick.refusal(pick.firstTwo))

        tab.cherryPickCommits(checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = false))).join()

        assertEquals(listOf("fix: two", "fix: one", "main: own"), subjects(3))
        assertEquals(start, git.run(repository, "rev-parse", "HEAD~2").trim())
        assertEquals(CompletedTask.Success(lastTask().date, TaskType.CherryPickCommits), lastTask())

        // The menus count again: the copies are other commits, so the branch still has its three
        val ahead = tab.awaitLoaded(tab.repositoryData.branchesAhead) { it.byRef[fix.name] != null }
        val count = git.run(repository, "rev-list", "--count", "HEAD..fix").trim().toInt()

        assertEquals(BranchAhead(count, tipIsMerge = false), ahead.byRef[fix.name])
    }

    @Test
    fun `as one commit, the changes wait staged, with their messages in SQUASH_MSG`(): Unit = runBlocking {
        val pick = open()
        val plan = checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = true))

        tab.cherryPickCommits(plan).join()

        assertEquals(start, head(), "Nothing is committed")
        assertEquals("M  a.txt\nM  b.txt", git.run(repository, "status", "--porcelain").trim())
        assertEquals(plan.squashMessage, File(gitDir, "SQUASH_MSG").readText())
        assertEquals(TaskType.CherryPickCommitsAsOne, lastTask().taskType)
        assertInstanceOf(CompletedTask.Success::class.java, lastTask())
    }

    @Test
    fun `git stops at a commit's conflicts, and Continue commits it with the field's message, then the rest`(): Unit =
        runBlocking {
            commit("main: a", "a.txt" to "a main\n")
            start = head()
            val pick = open()

            tab.cherryPickCommits(checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = false))).join()

            assertEquals(CompletedTask.Success(lastTask().date, TaskType.CherryPickCommits, true), lastTask())
            val sequence = tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it != null }
            assertEquals(2, sequence?.remaining?.size)
            assertEquals(false, sequence?.noCommit)

            File(repository, "a.txt").writeText("a resolved\n")
            git.run(repository, "add", "a.txt")
            tab.cherryPickSequence.continueSequence("fix: one, resolved\n\n# a comment git drops\n", true)
            tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it == null }

            assertEquals(listOf("fix: two", "fix: one, resolved", "main: a"), subjects(3))
            assertEquals("fix: one, resolved", git.run(repository, "log", "-1", "--format=%B", "HEAD~1").trim())
            assertFalse(File(gitDir, "sequencer").exists())
            assertEquals(TaskType.ContinueCherryPick, lastTask().taskType)
        }

    @Test
    fun `Skip leaves out the commit that came out empty, and picks the rest`(): Unit = runBlocking {
        commit("main: a", "a.txt" to "a main\n")
        val pick = open()

        tab.cherryPickCommits(checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = false))).join()
        tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it != null }
        // Resolved by keeping main's side: nothing is left to commit
        git.run(repository, "checkout", "--ours", "a.txt")
        git.run(repository, "add", "a.txt")

        tab.cherryPickSequence.skip()
        tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it == null }

        assertEquals(listOf("fix: two", "main: a"), subjects(2))
        assertEquals("a main\n", File(repository, "a.txt").readText())
    }

    @Test
    fun `each commit that git stops at has its own message, keyed by that commit`(): Unit = runBlocking {
        commit("main: a and b", "a.txt" to "a main\n", "b.txt" to "b main\n")
        val pick = open()
        val (one, two) = pick.pickable.take(2)

        tab.cherryPickCommits(checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = false))).join()
        val atOne = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.mergeMessageStop == one.hash }
        assertEquals("fix: one", atOne.mergeMessage?.trim())

        File(repository, "a.txt").writeText("a resolved\n")
        git.run(repository, "add", "a.txt")
        tab.cherryPickSequence.continueSequence("fix: one, resolved", true)

        // git went on and stopped at the next commit's conflicts, with that commit's message
        val atTwo = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.mergeMessageStop == two.hash }
        assertEquals("fix: two", atTwo.mergeMessage?.trim())
        assertEquals("fix: one, resolved", subjects(1).single())

        tab.cherryPickSequence.abort()
        val after = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.mergeMessageStop == null }
        assertEquals(null, after.mergeMessage)
    }

    @Test
    fun `Abort takes the commits that it picked so far off the branch`(): Unit = runBlocking {
        commit("main: b", "b.txt" to "b main\n")
        start = head()
        File(repository, "notes.txt").writeText("untracked, kept\n")
        val pick = open()

        tab.cherryPickCommits(checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = false))).join()
        tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it != null }
        assertEquals("fix: one", subjects(1).single(), "The first one was picked before the second stopped")

        tab.cherryPickSequence.abort()
        tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it == null }

        assertEquals(start, head())
        assertEquals("?? notes.txt", git.run(repository, "status", "--porcelain").trim())
    }

    @Test
    fun `aborting a pick as one commit removes the SQUASH_MSG that Leaf wrote for it, as git's abort does`(): Unit =
        runBlocking {
            commit("main: a", "a.txt" to "a main\n")
            start = head()
            val pick = open()

            tab.cherryPickCommits(checkNotNull(pick.cherryPickPlan(pick.firstTwo, asOneCommit = true))).join()
            val sequence = tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it != null }
            assertEquals(true, sequence?.noCommit)
            assertTrue(File(gitDir, "SQUASH_MSG").exists(), "Written though git stopped, for the field at the end")

            tab.cherryPickSequence.abort()
            tab.awaitLoaded(tab.repositoryData.cherryPickSequence) { it == null }

            assertFalse(File(gitDir, "SQUASH_MSG").exists())
            assertEquals(start, head())
            assertEquals("", git.run(repository, "status", "--porcelain").trim())
        }

    @Test
    fun `staged changes are refused before git runs`(): Unit = runBlocking {
        File(repository, "a.txt").writeText("a staged\n")
        git.run(repository, "add", "a.txt")

        val pick = open()

        assertEquals(CherryPickRefusal.StagedChanges(1), pick.refusal(pick.firstTwo))
    }
}
