// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.withGitDaemon
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.remote.StoppedWaitingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * The processing screen's Cancel and Stop waiting in a tab (fork-only): a push and a remote branch's delete to a
 * `git daemon`, whose server goes on without git, with a pre-receive hook that takes a few seconds. Stopping after
 * the hand-off starts asking the server, as the tab's view model does, and the tab then shows what the server did.
 * Skipped without the askpass helper.
 */
@DisabledOnOs(OS.WINDOWS)
class StoppingPushesTest {
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

    private val bare by lazy { File(tempDir, "srv/origin.git") }
    private val work by lazy { File(tempDir, "work") }
    private val started by lazy { File(tempDir, "pre-receive.started") }

    @BeforeEach
    fun createRepositories() {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        git.run(tempDir, "init", "--bare", bare.path)
        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", bare.path, "main", "main:feature")
        git.run(tempDir, "clone", bare.path, work.path)
        File(bare, "hooks/pre-receive").writeExecutable("#!/bin/sh\ntouch '${started.path}'\nsleep 4\n")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `stopping a push after the hand-off asks the server, which took it, and the tab shows the branch pushed`(): Unit = runBlocking {
        withDaemon { url ->
            git.run(work, "remote", "set-url", "origin", url)
            git.run(work, "switch", "-c", "new-work")
            File(work, "work.txt").writeText("work\n")
            git.run(work, "add", ".")
            git.run(work, "commit", "-m", "New work")
            val pushed = git.run(work, "rev-parse", "HEAD").trim()
            open()

            tab.push(force = false, pushTags = false)
            awaitHandOffWhileTheServerRuns()

            val handOff = tab.repositoryState.cancelCurrentTask()

            assertEquals("refs/heads/new-work", handOff?.serverRef)
            assertEquals(pushed, handOff?.pushed)
            tab.stoppedWaitingCheck.start(handOff!!)

            assertEquals(StoppedWaitingState.Done(handOff), awaitAnswer())
            tab.awaitLoaded(tab.repositoryData.remotes) { remotes ->
                remotes.flatMap { it.branchesList }.any { it.name == "refs/remotes/origin/new-work" && it.hash == pushed }
            }
            assertEquals("refs/heads/new-work", git.run(work, "config", "branch.new-work.merge").trim())
            // A cancelled task reports nothing of its own: the strip says what happened
            assertTrue(tab.repositoryState.completedTasks.value.none { it.taskType == TaskType.Push })
        }
    }

    @Test
    fun `stopping a delete asks the server too, which deleted it, and its remote-tracking branch goes`(): Unit = runBlocking {
        withDaemon { url ->
            git.run(work, "remote", "set-url", "origin", url)
            open()
            val tip = git.run(work, "rev-parse", "refs/remotes/origin/feature").trim()

            tab.deleteRemoteBranch(Branch(hash = tip, name = "refs/remotes/origin/feature", isLocal = false))
            awaitHandOffWhileTheServerRuns()

            val handOff = tab.repositoryState.cancelCurrentTask()

            assertEquals(
                ServerHandOff("origin", "refs/heads/feature", "refs/remotes/origin/feature", pushed = null, before = tip),
                handOff,
            )
            tab.stoppedWaitingCheck.start(handOff!!)

            assertEquals(StoppedWaitingState.Done(handOff), awaitAnswer())
            tab.awaitLoaded(tab.repositoryData.remotes) { remotes ->
                remotes.flatMap { it.branchesList }.none { it.name == "refs/remotes/origin/feature" }
            }
        }
    }

    @Test
    fun `cancelling a push before it hands anything over leaves nothing to ask`(): Unit = runBlocking {
        val hookStarted = File(tempDir, "pre-push.started")
        File(work, ".git/hooks/pre-push").writeExecutable("#!/bin/sh\ntouch '${hookStarted.path}'\nexec sleep 30\n")
        File(work, "work.txt").writeText("work\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Not pushed")
        val before = git.run(bare, "rev-parse", "main").trim()
        open()

        tab.push(force = false, pushTags = false)
        withTimeout(20.seconds) { while (!hookStarted.exists() || tab.repositoryState.taskProgress.value == null) delay(20) }

        assertNull(tab.repositoryState.cancelCurrentTask())
        withTimeout(10.seconds) { tab.repositoryState.currentTask.first { it == null } }
        assertEquals(before, git.run(bare, "rev-parse", "main").trim())
        assertNull(tab.stoppedWaitingCheck.state.value)
    }

    private suspend fun open() {
        tab.openRepository(work.path)
        tab.awaitLoaded(tab.repositoryData.remotes)
        tab.awaitLoaded(tab.repositoryData.status)
    }

    /** Waits until git has handed the server what it does alone, and the server's hook runs. */
    private suspend fun awaitHandOffWhileTheServerRuns() = withTimeout(20.seconds) {
        while (tab.repositoryState.taskProgress.value?.handedOverAt == null || !started.exists()) delay(20)
    }

    private suspend fun awaitAnswer(): StoppedWaitingState? = withTimeout(30.seconds) {
        tab.stoppedWaitingCheck.state.first { it != null && it !is StoppedWaitingState.Asking }
    }

    private suspend fun withDaemon(block: suspend (url: String) -> Unit) =
        withGitDaemon(bare.parentFile, globalConfig, File(tempDir, "daemon.log")) { base -> block("$base/${bare.name}") }
}
