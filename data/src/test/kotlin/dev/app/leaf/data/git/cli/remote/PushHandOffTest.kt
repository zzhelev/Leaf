// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.errors.SeveralPushUrlsError
import dev.app.leaf.domain.interfaces.HandOffOutcome
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * When a push or a delete has handed the server what it carries out without git (fork-only): the progress that says
 * so, what stopping before and after it leaves on the server, and asking the server afterwards
 * ([CheckHandOffGitAction]), which also does here what git would have done. A stop is tried over `git daemon`, whose
 * server goes on without git, as ssh and https servers do. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class PushHandOffTest {
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
    fun `a push hands the server everything at git's last object, and only the server's progress shows after it`(): Unit = runBlocking {
        val before = localRef("refs/remotes/origin/main")
        commit("second")

        assertEquals(Either.Ok(Unit), push())

        val progress = progress().filterNotNull()
        val first = progress.indexOfFirst { it.handOff != null }

        assertTrue(first > 0) { "$progress" }
        assertTrue(progress.take(first).all { it.handOff == null && it.handedOverAt == null })
        assertTrue(progress.take(first).any { it.stage == "Writing objects" }) { "$progress" }
        assertEquals(TaskProgress(null, null, mainHandOff(before), progress[first].handedOverAt), progress[first])
        assertTrue(progress.drop(first).all { it.handOff == mainHandOff(before) && it.stage != "Writing objects" }) { "$progress" }
        assertNull(progress().last())
    }

    @Test
    fun `a push with no new objects hands over at Total 0, with the upstream that it sets`(): Unit = runBlocking {
        git.run(work, "switch", "-c", "feature")

        assertEquals(Either.Ok(Unit), push())

        assertEquals(
            listOf(
                ServerHandOff(
                    remote = "origin",
                    serverRef = "refs/heads/feature",
                    trackingRef = "refs/remotes/origin/feature",
                    pushed = head(),
                    before = null,
                    upstreamOf = "refs/heads/feature",
                )
            ),
            handOffs(),
        )
    }

    @Test
    fun `a delete hands over at once, as git doesn't say when its command went`(): Unit = runBlocking {
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")

        assertEquals(Either.Ok(Unit), deleteRemoteBranch("refs/remotes/origin/feature"))

        val progress = progress().filterNotNull()
        val handOff = ServerHandOff("origin", "refs/heads/feature", "refs/remotes/origin/feature", pushed = null, before = head())

        assertEquals(TaskProgress(null, null), progress.first())
        assertTrue(progress.drop(1).isNotEmpty() && progress.drop(1).all { it.handOff == handOff }) { "$progress" }
    }

    @Test
    fun `cancelled before it hands anything over, the push leaves the server as it was`(): Unit = runBlocking {
        val before = remoteRef("refs/heads/main")
        commit("second")
        val hookStarted = File(tempDir, "pre-push.started")
        File(work, ".git/hooks/pre-push").writeExecutable("#!/bin/sh\ntouch '${hookStarted.absolutePath}'\nexec sleep 30\n")

        val pushing = async(Dispatchers.Default) { push() }
        withTimeout(10.seconds) { while (!hookStarted.exists()) delay(20) }
        pushing.cancel()

        assertTrue(wasCancelled(pushing))
        delay(500)
        assertEquals(before, remoteRef("refs/heads/main"))
        assertTrue(handOffs().isEmpty())
    }

    /**
     * What the processing screen's Stop waiting runs into: git is killed while the server's pre-receive hook runs, the
     * server takes the push anyway, and git set nothing here. Asking the server then sees it, and sets the
     * remote-tracking branch and the upstream as git would have.
     */
    @Test
    fun `stopped after the hand-off, the server takes the push, and the check sets what git would have`(): Unit = runBlocking {
        val started = File(tempDir, "pre-receive.started")
        File(bare, "hooks/pre-receive").writeExecutable("#!/bin/sh\ntouch '${started.absolutePath}'\nsleep 5\n")

        withDaemon { url ->
            git.run(work, "remote", "set-url", "origin", url)
            git.run(work, "switch", "-c", "feature")
            commit("feature work")

            val pushing = async(Dispatchers.IO) { push() }
            withTimeout(20.seconds) { while (!started.exists() || handOffs().isEmpty()) delay(20) }
            pushing.cancel()

            assertTrue(wasCancelled(pushing))
            assertNull(localRef("refs/remotes/origin/feature"))
            assertNull(config("branch.feature.merge"))

            val handOff = handOffs().single()
            // The server is still in its hook
            assertEquals(Either.Ok(HandOffOutcome.NotDone(null)), check(handOff))

            assertEquals(HandOffOutcome.Done, checkUntilDone(handOff))
            assertEquals(head(), remoteRef("refs/heads/feature"))
            assertEquals(head(), localRef("refs/remotes/origin/feature"))
            assertEquals("origin", config("branch.feature.remote"))
            assertEquals("refs/heads/feature", config("branch.feature.merge"))
        }
    }

    @Test
    fun `a server whose hook refuses the push after git is gone hasn't taken it`(): Unit = runBlocking {
        val started = File(tempDir, "pre-receive.started")
        val finished = File(tempDir, "pre-receive.finished")
        File(bare, "hooks/pre-receive").writeExecutable(
            "#!/bin/sh\ntouch '${started.absolutePath}'\nsleep 2\ntouch '${finished.absolutePath}'\nexit 1\n"
        )

        withDaemon { url ->
            git.run(work, "remote", "set-url", "origin", url)
            val before = remoteRef("refs/heads/main")
            commit("refused")

            val pushing = async(Dispatchers.IO) { push() }
            withTimeout(20.seconds) { while (!started.exists() || handOffs().isEmpty()) delay(20) }
            pushing.cancel()
            assertTrue(wasCancelled(pushing))
            withTimeout(20.seconds) { while (!finished.exists()) delay(20) }
            delay(300)

            assertEquals(Either.Ok(HandOffOutcome.NotDone(before)), check(handOffs().single()))
            assertEquals(before, localRef("refs/remotes/origin/main"))
        }
    }

    @Test
    fun `a stopped delete is seen once the server deleted it, and the remote-tracking branch goes`(): Unit = runBlocking {
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")
        val started = File(tempDir, "pre-receive.started")
        File(bare, "hooks/pre-receive").writeExecutable("#!/bin/sh\ntouch '${started.absolutePath}'\nsleep 3\n")

        withDaemon { url ->
            git.run(work, "remote", "set-url", "origin", url)

            val deleting = async(Dispatchers.IO) { deleteRemoteBranch("refs/remotes/origin/feature") }
            withTimeout(20.seconds) { while (!started.exists()) delay(20) }
            deleting.cancel()

            assertTrue(wasCancelled(deleting))
            assertEquals(head(), localRef("refs/remotes/origin/feature"))

            assertEquals(HandOffOutcome.Done, checkUntilDone(handOffs().single()))
            assertNull(remoteRef("refs/heads/feature"))
            assertNull(localRef("refs/remotes/origin/feature"))
        }
    }

    @Test
    fun `a remote-tracking branch that a fetch moved meanwhile stays where the fetch put it`(): Unit = runBlocking {
        val before = head()
        commit("pushed")
        val pushed = head()
        git.run(work, "push", "origin", "main")
        commit("fetched later")
        git.run(work, "update-ref", "refs/remotes/origin/main", head())

        val handOff = ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", pushed, before)

        assertEquals(Either.Ok(HandOffOutcome.Done), check(handOff))
        assertEquals(head(), localRef("refs/remotes/origin/main"))
    }

    @Test
    fun `an upstream set meanwhile is kept`(): Unit = runBlocking {
        git.run(work, "switch", "-c", "feature")
        git.run(work, "push", "origin", "feature")
        git.run(work, "config", "branch.feature.remote", "origin")
        git.run(work, "config", "branch.feature.merge", "refs/heads/main")

        val handOff = ServerHandOff(
            "origin", "refs/heads/feature", "refs/remotes/origin/feature", head(), before = null,
            upstreamOf = "refs/heads/feature",
        )

        assertEquals(Either.Ok(HandOffOutcome.Done), check(handOff))
        assertEquals("refs/heads/main", config("branch.feature.merge"))
    }

    @Test
    fun `the server asked is the one the remote pushes to`(): Unit = runBlocking {
        val pushServer = File(tempDir, "push.git")
        git.run(tempDir, "init", "--bare", pushServer.absolutePath)
        git.run(work, "push", pushServer.absolutePath, "main:only-there")
        git.run(work, "push", "origin", "main:only-in-fetch")
        git.run(work, "remote", "set-url", "--push", "origin", pushServer.absolutePath)

        val tips = remote.command.serverTips(work, "origin", listOf("refs/heads/only-there", "refs/heads/only-in-fetch"))

        assertEquals(Either.Ok(mapOf("refs/heads/only-there" to head())), tips)
    }

    @Test
    fun `a remote that pushes where it fetches from is asked by its name, so that its own settings apply`(): Unit = runBlocking {
        val log = File(tempDir, "upload-pack.log")
        val uploadPack = File(tempDir, "upload-pack").apply {
            writeExecutable("#!/bin/sh\necho asked >> '${log.absolutePath}'\nexec git upload-pack \"$@\"\n")
        }
        git.run(work, "config", "remote.origin.uploadpack", uploadPack.absolutePath)

        val tips = remote.command.serverTips(work, "origin", listOf("refs/heads/main"))

        assertEquals(Either.Ok(mapOf("refs/heads/main" to head())), tips)
        assertTrue(log.isFile) { "The remote's uploadpack wasn't used" }
    }

    @Test
    fun `a remote that pushes to several URLs has no one server to ask`(): Unit = runBlocking {
        val second = File(tempDir, "second.git")
        git.run(tempDir, "init", "--bare", second.absolutePath)
        git.run(work, "remote", "set-url", "--add", "--push", "origin", bare.absolutePath)
        git.run(work, "remote", "set-url", "--add", "--push", "origin", second.absolutePath)

        val tips = remote.command.serverTips(work, "origin", listOf("refs/heads/main"))

        assertEquals(Either.Err(SeveralPushUrlsError("origin", listOf(bare.absolutePath, second.absolutePath))), tips)
        assertEquals(
            Either.Ok(HandOffOutcome.CantTell),
            check(ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", head(), head())),
        )
    }

    /** Before the push URL was asked, this asked the fetch URL, which still had the branch, and refused to delete it. */
    @Test
    fun `a delete that the lease refuses asks the push URL whether the branch is already gone`(): Unit = runBlocking {
        val pushServer = File(tempDir, "push.git")
        git.run(tempDir, "clone", "--bare", bare.absolutePath, pushServer.absolutePath)
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")
        git.run(work, "remote", "set-url", "--push", "origin", pushServer.absolutePath)

        assertEquals(Either.Ok(Unit), deleteRemoteBranch("refs/remotes/origin/feature"))
        assertNull(localRef("refs/remotes/origin/feature"))
    }

    @Test
    fun `a delete that the lease refuses on a remote with several push URLs says git's refusal`(): Unit = runBlocking {
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")
        for (name in listOf("first.git", "second.git")) {
            val server = File(tempDir, name)
            git.run(tempDir, "init", "--bare", server.absolutePath)
            git.run(work, "remote", "set-url", "--add", "--push", "origin", server.absolutePath)
        }

        val result = deleteRemoteBranch("refs/remotes/origin/feature")

        val error = (result as? Either.Err)?.error
        assertTrue(error is RemoteOperationError.RefsRejected, "$result")
        assertEquals(head(), localRef("refs/remotes/origin/feature"))
    }

    private suspend fun withDaemon(block: suspend (url: String) -> Unit) =
        withGitDaemon(bare.parentFile, globalConfig, File(tempDir, "daemon.log")) { base -> block("$base/${bare.name}") }

    private suspend fun push(): Either<Unit, GitError> =
        GitCliPushBranchGitAction(jgit, GetTrackingBranchGitAction(jgit), remote.command)(
            gitDir, force = false, pushTags = false, pushWithLease = true, specificBranch = null,
        )

    private suspend fun deleteRemoteBranch(name: String): Either<Unit, GitError> =
        GitCliDeleteRemoteBranchGitAction(jgit, DeleteBranchGitAction(jgit), remote.command)(
            gitDir, Branch(hash = localRef(name).orEmpty(), name = name, isLocal = false),
        )

    private suspend fun check(handOff: ServerHandOff) = CheckHandOffGitAction(jgit, remote.command)(gitDir, handOff)

    /** Asks every 200 ms, as the tab does every 2 s, until the server has done it. */
    private suspend fun checkUntilDone(handOff: ServerHandOff): HandOffOutcome = withTimeout(20.seconds) {
        var outcome = (check(handOff) as Either.Ok).value

        while (outcome != HandOffOutcome.Done) {
            delay(200)
            outcome = (check(handOff) as Either.Ok).value
        }

        outcome
    }

    private fun handOffs(): List<ServerHandOff> = progress().mapNotNull { it?.handOff }.distinct()

    /** What the command reported so far, while it may still report more. */
    private fun progress(): List<TaskProgress?> = synchronized(remote.progress) { remote.progress.toList() }

    private suspend fun wasCancelled(job: Deferred<*>): Boolean = withTimeout(10.seconds) {
        try {
            job.await()
            false
        } catch (e: CancellationException) {
            true
        }
    }

    private fun mainHandOff(before: String?) =
        ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", pushed = head(), before = before)

    private fun commit(message: String) {
        File(work, "file.txt").appendText("$message\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", message)
    }

    private fun head() = git.run(work, "rev-parse", "HEAD").trim()

    private fun config(key: String) = runCatching { git.run(work, "config", key).trim() }.getOrNull()

    private fun remoteRef(name: String) = runCatching { git.run(bare, "rev-parse", "--verify", "-q", name).trim() }.getOrNull()

    private fun localRef(name: String) = runCatching { git.run(work, "rev-parse", "--verify", "-q", name).trim() }.getOrNull()
}
