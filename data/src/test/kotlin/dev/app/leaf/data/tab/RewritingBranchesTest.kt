// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.LineRange
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.RewriteDialogKind
import dev.app.leaf.domain.rewrite.RewriteRefusal
import dev.app.leaf.domain.rewrite.RewriteRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * A tab squashes and rewords a branch's commits from what its menus read (fork-only, step A3 of
 * `docs/fork/squash-and-rebase.md`): the branch lines that the refresh keeps, the rewrite and its refresh, Undo, and a
 * refusal, which the task records for its error dialog.
 */
@DisabledOnOs(OS.WINDOWS)
class RewritingBranchesTest {
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

    @BeforeEach
    fun createRepository() {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        git.initRepository(repository)
        git.run(repository, "switch", "-q", "-c", "feat")
        commit("Add the parser", "parser.txt")
        commit("wip", "parser.txt")
        commit("Cache the parsed files", "cache.txt")
    }

    @AfterEach
    fun tearDown() {
        if (builtAskpassHelper() != null) tab.close()
        SystemReader.setInstance(originalReader)
    }

    private fun commit(message: String, file: String) {
        File(repository, file).appendText("$message\n")
        git.run(repository, "add", "-A")
        git.run(repository, "commit", "-q", "-m", message)
    }

    private fun subjects() = git.run(repository, "log", "--format=%s", "main..feat").trim().lines()

    private suspend fun open(): BranchLines {
        tab.openRepository(repository.path)

        return tab.awaitLoaded(tab.repositoryData.branchLines) { it.lines["refs/heads/feat"]?.commits?.size == 3 }
    }

    private fun <T> Either<T, RewriteRefusal>.ok(): T = (this as Either.Ok).value

    private suspend fun lastTask(): CompletedTask =
        withTimeout(10.seconds) { tab.repositoryState.completedTasks.first { it.isNotEmpty() } }.last()

    @Test
    fun `a squash from the menus' lines rewrites the branch, and the lines follow it`(): Unit = runBlocking {
        val lines = open()
        val tree = git.run(repository, "rev-parse", "feat^{tree}").trim()
        // The line is newest first: "Cache the parsed files" and "wip", as the log selects them.
        val newestTwo = lines.lines.getValue("refs/heads/feat").commits.take(2).map { it.hash }
        val range: LineRange = lines.rangeChoice(newestTwo, "refs/heads/feat") { null }.ok()
        val request = RewriteRequest(RewriteDialogKind.SQUASH, range)

        val result = tab.rewriteBranch(request.rewrite(request.messageStart())).await()

        assertInstanceOf(Either.Ok::class.java, result)
        assertEquals(listOf("Cache the parsed files", "Add the parser"), subjects())
        assertEquals("Cache the parsed files", git.run(repository, "log", "-1", "--format=%B", "feat").trim())
        assertEquals(tree, git.run(repository, "rev-parse", "feat^{tree}").trim())

        val after = tab.awaitLoaded(tab.repositoryData.branchLines) { it.lines["refs/heads/feat"]?.commits?.size == 2 }
        assertEquals(
            listOf("Cache the parsed files", "Add the parser"),
            after.lines.getValue("refs/heads/feat").commits.map { it.shortMessage },
        )
    }

    @Test
    fun `Undo puts the branch back, and the lines with it`(): Unit = runBlocking {
        val lines = open()
        val tip = git.run(repository, "rev-parse", "feat").trim()
        val range = lines.commitChoices(tip, "refs/heads/feat") { null }.intoParent.ok()
        val result = (tab.rewriteBranch(range.fixup()).await() as Either.Ok).value

        tab.awaitLoaded(tab.repositoryData.branchLines) { it.lines["refs/heads/feat"]?.commits?.size == 2 }
        assertEquals(Either.Ok(Unit), tab.undoBranchRewrite(result).await())

        assertEquals(tip, git.run(repository, "rev-parse", "feat").trim())
        tab.awaitLoaded(tab.repositoryData.branchLines) { it.lines["refs/heads/feat"]?.commits?.size == 3 }
    }

    @Test
    fun `a branch that moved after the menu read it is refused, and the task says why`(): Unit = runBlocking {
        val lines = open()
        val range = lines.branchChoice("refs/heads/feat") { null }!!.ok()
        commit("An agent's commit", "agent.txt")

        val result = tab.rewriteBranch(range.squash("All of it")).await()

        assertEquals(Either.Err(RewriteBranchError.BranchMoved("feat")), result)
        assertEquals("An agent's commit", subjects().first())

        val task = lastTask()
        assertEquals(TaskType.RewriteBranch, task.taskType)
        assertInstanceOf(CompletedTask.Failure::class.java, task)
    }

    @Test
    fun `squashing the branch onto the latest main, as the dialog checks it, then Undo`(): Unit = runBlocking {
        git.run(repository, "switch", "-q", "main")
        commit("Fix the release notes", "notes.txt")
        git.run(repository, "switch", "-q", "feat")
        val tip = git.run(repository, "rev-parse", "feat").trim()
        val lines = open()
        val range = lines.branchChoice("refs/heads/feat") { null }!!.ok()

        val check = (tab.checkOntoBase(range.line) as Either.Ok).value

        assertInstanceOf(OntoBaseCheck.Clean::class.java, check)
        assertEquals(1, check!!.newCommits)

        val request = RewriteRequest(RewriteDialogKind.SQUASH_BRANCH, range)
        val result = (tab.rewriteBranch(request.rewrite("Add a parser with a cache", check.onto)).await() as Either.Ok).value

        assertEquals(git.run(repository, "rev-parse", "main").trim(), git.run(repository, "rev-parse", "feat~1").trim())
        assertEquals("Fix the release notes\n", File(repository, "notes.txt").readText())
        assertEquals(check.onto, result.onto)
        tab.awaitLoaded(tab.repositoryData.branchLines) { it.lines["refs/heads/feat"]?.commits?.size == 1 }

        assertEquals(Either.Ok(Unit), tab.undoBranchRewrite(result).await())

        assertEquals(tip, git.run(repository, "rev-parse", "feat").trim())
        assertTrue(!File(repository, "notes.txt").exists())
        tab.awaitLoaded(tab.repositoryData.branchLines) { it.lines["refs/heads/feat"]?.commits?.size == 3 }
    }
}
