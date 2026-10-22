// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.rebase.RebaseStop
import dev.app.leaf.domain.rebase.SkippedCommitsNotice
import dev.app.leaf.domain.rebase.SkippedRebaseCommit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * A rebase's Skip of a commit with conflicts (fork-only): the commit that the rebase stopped at, git's and JGit's skip,
 * and the commits that Skip left out, which the tab lists once the rebase has ended, and not after an abort.
 */
@DisabledOnOs(OS.WINDOWS)
class SkippingRebaseCommitsTest {
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
        // git refuses to make an identity up on Linux
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        git.initRepository(repository)
        commit("base", "f1" to "base\n", "f2" to "base\n", "f3" to "base\n", "f4" to "base\n")
        git.run(repository, "switch", "-q", "-c", "topic")
        commit("topic: one", "f1" to "one\n")
        commit("topic: two", "f2" to "two\n")
        commit("topic: three", "f3" to "three\n")
        git.run(repository, "switch", "-q", "main")
        commit("main: f2 and f3", "f2" to "main\n", "f3" to "main\n")
        git.run(repository, "switch", "-q", "topic")
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

    private fun hash(rev: String) = git.run(repository, "rev-parse", rev).trim()

    private fun subjects(range: String) = git.run(repository, "log", "--format=%s", range).trim().lines()

    private suspend fun open() {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun awaitStop(predicate: (RebaseStop?) -> Boolean): RebaseStop? =
        tab.awaitLoaded(tab.repositoryData.rebaseStop, predicate)

    private fun RebaseStop.skipped() = SkippedRebaseCommit(commit, subject, branch)

    @Test
    fun `a rebase that git stopped names the commit and the branch, and isn't an edit stop`(): Unit = runBlocking {
        git.runFailing(repository, "rebase", "main")
        open()

        val stop = awaitStop { it != null }

        assertEquals(RebaseStop(hash("REBASE_HEAD"), "topic: two", "topic", isEditStop = false), stop)
        assertEquals(hash("topic~1"), stop?.commit)
    }

    @Test
    fun `an edit stop says so`(): Unit = runBlocking {
        git.run(
            repository,
            mapOf("GIT_SEQUENCE_EDITOR" to "sed -i.bak 's/^pick \\(.* topic: one\\)/edit \\1/'"),
            "rebase", "-i", "main",
        )
        open()

        assertEquals(true, awaitStop { it != null }?.isEditStop)
    }

    @Test
    fun `Skip leaves out a commit with its conflicts open, and the tab lists it once the rebase ends`(): Unit =
        runBlocking {
            git.runFailing(repository, "rebase", "main")
            open()
            val stop = checkNotNull(awaitStop { it != null })

            tab.skipRebase(stop.skipped())

            // three stops at f3's conflicts too: left out the same way
            val next = checkNotNull(awaitStop { it != null && it.commit != stop.commit })
            assertEquals("topic: three", next.subject)
            tab.skipRebase(next.skipped())

            val notice = withTimeout(10.seconds) {
                tab.repositoryState.skippedCommitsNotices.first { it.isNotEmpty() }.single()
            }

            assertEquals(SkippedCommitsNotice("topic", listOf(stop.skipped(), next.skipped())), notice)
            assertEquals(listOf("topic: one", "main: f2 and f3", "base"), subjects("HEAD").take(3))
            assertEquals(null, awaitStop { it == null })
        }

    @Test
    fun `an aborted rebase brings the skipped commits back, and lists nothing`(): Unit = runBlocking {
        git.runFailing(repository, "rebase", "main")
        open()
        val stop = checkNotNull(awaitStop { it != null })
        // The branch keeps its tip until the rebase ends
        val before = hash("refs/heads/topic")

        tab.skipRebase(stop.skipped())
        awaitStop { it != null && it.commit != stop.commit }
        tab.abortRebase()
        awaitStop { it == null }
        tab.awaitLoaded(tab.repositoryData.repositoryState) { !it.isRebasing }
        // The report runs after the state's refresh: give it time to say something, which it mustn't
        delay(2.seconds)

        assertEquals(before, hash("HEAD"))
        assertEquals(emptyList<SkippedCommitsNotice>(), tab.repositoryState.skippedCommitsNotices.value)
    }

    @Test
    fun `a commit that came out empty is left out without a notice`(): Unit = runBlocking {
        git.runFailing(repository, "rebase", "main")
        open()
        awaitStop { it != null }
        git.run(repository, "checkout", "--ours", "f2")
        git.run(repository, "add", "f2")

        // As the status pane does: nothing of the commit is lost, so nothing to list
        tab.skipRebase(null)
        val next = checkNotNull(awaitStop { it != null && it.subject == "topic: three" })
        tab.skipRebase(next.skipped())

        val notice = withTimeout(10.seconds) {
            tab.repositoryState.skippedCommitsNotices.first { it.isNotEmpty() }.single()
        }
        assertEquals(listOf(next.skipped()), notice.commits)
    }

    @Test
    fun `a rebase that JGit stopped, as Leaf's interactive one, is read and skipped the same way`(): Unit = runBlocking {
        Git.open(repository).use { jgit ->
            val result = jgit.rebase()
                .setUpstream(jgit.repository.resolve("main"))
                .runInteractively(object : RebaseCommand.InteractiveHandler {
                    override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {}
                    override fun modifyCommitMessage(message: String) = message
                })
                .call()

            assertEquals(RebaseResult.Status.STOPPED, result.status)
        }
        assertFalse(File(repository, ".git/REBASE_HEAD").exists(), "JGit writes stopped-sha only")
        open()

        val stop = checkNotNull(awaitStop { it != null })
        assertEquals(RebaseStop(hash("topic~1"), "topic: two", "topic", isEditStop = false), stop)

        tab.skipRebase(stop.skipped())
        val next = checkNotNull(awaitStop { it != null && it.commit != stop.commit })
        tab.skipRebase(next.skipped())

        val notice = withTimeout(10.seconds) {
            tab.repositoryState.skippedCommitsNotices.first { it.isNotEmpty() }.single()
        }
        assertEquals(listOf("topic: two", "topic: three"), notice.commits.map { it.subject })
        assertEquals(listOf("topic: one", "main: f2 and f3", "base"), subjects("HEAD").take(3))
    }

    /**
     * Why Skip is blocked while other tracked files have changes: git's skip and JGit's both reset every tracked file,
     * and those changes are in no commit. Untracked files are kept. If either stops doing so, the block can go.
     */
    @Test
    fun `git's skip and JGit's discard changes to other tracked files, and keep untracked ones`(): Unit = runBlocking {
        git.runFailing(repository, "rebase", "main")
        File(repository, "f4").writeText("edited, in no commit\n")
        File(repository, "notes.txt").writeText("untracked\n")

        // Skipping two stops at three's conflicts
        git.runFailing(repository, "rebase", "--skip")
        git.run(repository, "rebase", "--abort")

        assertEquals("base\n", File(repository, "f4").readText(), "git's skip")
        assertTrue(File(repository, "notes.txt").exists())

        Git.open(repository).use { jgit ->
            jgit.rebase().setUpstream(jgit.repository.resolve("main")).call()
            File(repository, "f4").writeText("edited, in no commit\n")
            jgit.rebase().setOperation(RebaseCommand.Operation.SKIP).call()
        }

        assertEquals("base\n", File(repository, "f4").readText(), "JGit's skip")
        assertTrue(File(repository, "notes.txt").exists())
    }
}
