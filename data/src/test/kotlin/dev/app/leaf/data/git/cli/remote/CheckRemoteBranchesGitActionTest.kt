// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.errors.SshNeedsGitError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

/**
 * Asking the server what a stopped delete left there ([CheckRemoteBranchesGitAction]): from a bare repository on disk,
 * and over `git daemon`, a server that goes on without git, as ssh and https servers do. Skipped when the askpass
 * helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class CheckRemoteBranchesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var globalConfig: File
    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var bare: File
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig)

        bare = File(tempDir, "srv/origin.git")
        git.run(tempDir, "init", "--bare", bare.absolutePath)
        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", bare.absolutePath, "main")

        work = File(tempDir, "work")
        git.run(tempDir, "clone", bare.absolutePath, work.absolutePath)
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a branch the server no longer has counts as deleted, and loses its remote-tracking branch`(): Unit = runBlocking {
        pushBranches("gone", "kept", "moved")
        val targets = listOf(target("gone"), target("kept"), target("moved"))
        git.run(bare, "branch", "-D", "gone")
        val theirs = pushFromAnotherClone("moved")

        val outcomes = check(targets)

        assertEquals(
            listOf(
                BranchDeletionOutcome.Deleted(targets[0]),
                BranchDeletionOutcome.Kept(targets[1], BranchKept.StillOnRemote),
                BranchDeletionOutcome.Kept(targets[2], BranchKept.ChangedOnRemote(theirs)),
            ),
            outcomes,
        )
        assertEquals(listOf("origin/HEAD", "origin/kept", "origin/main", "origin/moved"), trackingBranches())
        // It deletes nothing on the server
        assertEquals(listOf("refs/heads/kept", "refs/heads/main", "refs/heads/moved"), serverBranches())
        assertEquals(theirs, git.run(bare, "rev-parse", "moved").trim())
    }

    @Test
    fun `a branch deleted anyway is still there while the server has the commit it was deleted at`(): Unit = runBlocking {
        pushBranches("pushed-to")
        val shown = target("pushed-to")
        val theirs = pushFromAnotherClone("pushed-to")
        val anyway = shown.copy(serverTip = theirs)

        assertEquals(listOf(BranchDeletionOutcome.Kept(anyway, BranchKept.StillOnRemote)), check(listOf(anyway)))

        git.run(bare, "branch", "-D", "pushed-to")

        assertEquals(listOf(BranchDeletionOutcome.Deleted(anyway)), check(listOf(anyway)))
        // Leaf's remote-tracking branch is still at the commit it showed, and goes
        assertTrue("origin/pushed-to" !in trackingBranches())
    }

    @Test
    fun `a remote-tracking branch that a fetch moved meanwhile stays, as its commit isn't the one deleted`(): Unit = runBlocking {
        pushBranches("gone")
        val target = target("gone")
        val theirs = pushFromAnotherClone("gone")
        git.run(work, "fetch", "origin")
        git.run(bare, "branch", "-D", "gone")

        assertEquals(listOf(BranchDeletionOutcome.Deleted(target)), check(listOf(target)))
        assertEquals(theirs, git.run(work, "rev-parse", "refs/remotes/origin/gone").trim())
    }

    @Test
    fun `more branches than it names to git are all checked`(): Unit = runBlocking {
        val names = (1..30).map { "many/b$it" }
        pushBranches(*names.toTypedArray())
        val targets = names.map { target(it) }
        names.filterIndexed { index, _ -> index % 2 == 0 }.forEach { git.run(bare, "branch", "-D", it) }

        val outcomes = check(targets)

        assertEquals(15, outcomes.count { it is BranchDeletionOutcome.Deleted })
        assertEquals(15, outcomes.count { it is BranchDeletionOutcome.Kept && it.reason == BranchKept.StillOnRemote })
    }

    @Test
    fun `a branch that the fetch refspec maps elsewhere is checked under its name on the server`(): Unit = runBlocking {
        pushBranches("feature")
        git.run(work, "config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/team/*")
        git.run(work, "fetch", "origin")
        val target = BranchToDelete(
            "refs/remotes/origin/team/feature",
            git.run(work, "rev-parse", "refs/remotes/origin/team/feature").trim(),
        )

        assertEquals(listOf(BranchDeletionOutcome.Kept(target, BranchKept.StillOnRemote)), check(listOf(target)))
    }

    @Test
    fun `a server out of reach is an error, and nothing changes here`(): Unit = runBlocking {
        pushBranches("gone")
        val target = target("gone")
        git.run(work, "remote", "set-url", "origin", File(tempDir, "nowhere.git").absolutePath)

        val result = action()(gitDir, "origin", listOf(target))

        assertTrue(result is Either.Err && result.error is RemoteOperationError, "$result")
        assertTrue("origin/gone" in trackingBranches())
    }

    @Test
    fun `without git, it says what to turn on`(): Unit = runBlocking {
        val off = CheckRemoteBranchesGitAction(
            jgit,
            testRemoteOperationsBackend(jgit, remote.gitCli, builtAskpassHelper(), remoteOperationsWithGit = false),
            remote.command,
        )

        assertEquals(
            Either.Err(RemoteBranchesNeedGitError(SshNeedsGitError.Reason.SettingOff)),
            off(gitDir, "origin", listOf(BranchToDelete("refs/remotes/origin/main", "0".repeat(40)))),
        )
    }

    /**
     * What the dialog runs into: Stop while the server's hook runs. The server, a `git daemon`, goes on without git and
     * deletes the branches, while git, killed, leaves the remote-tracking branches; the check then finds them gone.
     */
    @Test
    fun `after a stop, a server that went on without git is seen to have deleted the branches`(): Unit = runBlocking {
        pushBranches("first", "second")
        val started = File(tempDir, "started")
        File(bare, "hooks/pre-receive").writeExecutable("#!/bin/sh\ntouch '${started.absolutePath}'\nsleep 3\n")

        withDaemon { url ->
            git.run(work, "remote", "set-url", "origin", url)
            val targets = listOf(target("first"), target("second"))

            val deleting = async(Dispatchers.IO) {
                DeleteRemoteBranchesGitAction(jgit, backend(), remote.command)(gitDir, "origin", targets)
            }

            withTimeout(20.seconds) {
                while (!started.exists()) delay(20)
            }

            deleting.cancel()

            val stopped = withTimeout(10.seconds) {
                try {
                    deleting.await()
                    false
                } catch (e: CancellationException) {
                    true
                }
            }

            assertTrue(stopped)
            // Git is gone and left them; the server is still in its hook
            assertTrue("origin/first" in trackingBranches() && "origin/second" in trackingBranches())

            withTimeout(20.seconds) {
                while ("refs/heads/first" in serverBranches()) delay(50)
            }

            assertEquals(targets.map { BranchDeletionOutcome.Deleted(it) }, check(targets))
            assertEquals(listOf("origin/HEAD", "origin/main"), trackingBranches())
        }
    }

    private fun backend() = testRemoteOperationsBackend(jgit, remote.gitCli, builtAskpassHelper())

    private fun action() = CheckRemoteBranchesGitAction(jgit, backend(), remote.command)

    private suspend fun check(targets: List<BranchToDelete>): List<BranchDeletionOutcome> =
        when (val result = action()(gitDir, "origin", targets)) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The check failed: ${result.error}")
        }

    /** Runs a `git daemon` that serves [bare]'s folder and takes pushes, for [block] with its URL. Skips without one. */
    private suspend fun withDaemon(block: suspend (url: String) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val daemon = ProcessBuilder(
            "git", "daemon", "--base-path=${bare.parentFile.absolutePath}", "--export-all", "--enable=receive-pack",
            "--port=$port", "--listen=127.0.0.1", "--reuseaddr",
        )
            .redirectErrorStream(true)
            .redirectOutput(File(tempDir, "daemon.log"))
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = globalConfig.absolutePath
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()

        try {
            val listening = withTimeout(10.seconds) {
                while (daemon.isAlive) {
                    val accepts = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }
                    if (accepts.isSuccess) return@withTimeout true
                    delay(50)
                }
                false
            }
            assumeTrue(listening) { "git daemon didn't start: ${File(tempDir, "daemon.log").readText()}" }

            block("git://127.0.0.1:$port/${bare.name}")
        } finally {
            daemon.descendants().forEach { it.destroyForcibly() }
            daemon.destroyForcibly()
            daemon.waitFor()
        }
    }

    private fun target(name: String) =
        BranchToDelete("refs/remotes/origin/$name", git.run(work, "rev-parse", "refs/remotes/origin/$name").trim())

    /** Pushes each of [names] to origin from main, with a commit of its own, and fetches them. */
    private fun pushBranches(vararg names: String) {
        for ((index, name) in names.withIndex()) {
            git.run(work, "switch", "-c", name)
            File(work, "file-$index.txt").writeText("$name\n")
            git.run(work, "add", ".")
            git.run(work, "commit", "-m", "Work on $name")
            git.run(work, "switch", "main")
        }

        git.run(work, "push", "origin", *names.map { "refs/heads/$it:refs/heads/$it" }.toTypedArray())
        names.forEach { git.run(work, "branch", "-D", "--", it) }
    }

    /** Someone else pushes a commit to [branch], and the commit's hash. */
    private fun pushFromAnotherClone(branch: String): String {
        val clone = File(tempDir, "other-${branch.replace('/', '-')}")
        git.run(tempDir, "clone", bare.absolutePath, clone.absolutePath)
        git.run(clone, "switch", branch)
        File(clone, "other.txt").writeText("someone else\n")
        git.run(clone, "add", ".")
        git.run(clone, "commit", "-m", "Someone else's work")
        git.run(clone, "push", "origin", branch)

        return git.run(clone, "rev-parse", "HEAD").trim()
    }

    private fun serverBranches(): List<String> =
        git.run(bare, "for-each-ref", "--format=%(refname)", "refs/heads").lines().filter { it.isNotBlank() }

    private fun trackingBranches(): List<String> =
        git.run(work, "for-each-ref", "--format=%(refname)", "refs/remotes")
            .lines()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("refs/remotes/") }
}
