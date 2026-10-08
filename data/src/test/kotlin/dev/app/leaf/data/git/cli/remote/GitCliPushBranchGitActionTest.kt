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
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RejectReason
import dev.app.leaf.domain.errors.RejectedRef
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * Pushing and deleting remote branches with the git CLI ([GitCliPushBranchGitAction],
 * [GitCliDeleteRemoteBranchGitAction]), to a bare repository on disk. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliPushBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

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

        val globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig)

        bare = File(tempDir, "remote.git")
        git.run(tempDir, "init", "--bare", bare.absolutePath)
        work = git.initRepository(File(tempDir, "work"))
        git.run(work, "remote", "add", "origin", bare.absolutePath)
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a branch without upstream is pushed to origin and gets it as upstream`(): Unit = runBlocking {
        assertEquals(Either.Ok(Unit), push())

        assertEquals(head(), remoteRef("refs/heads/main"))
        assertEquals("origin", git.run(work, "config", "branch.main.remote").trim())
        assertEquals("refs/heads/main", git.run(work, "config", "branch.main.merge").trim())
        assertEquals(TaskProgress("Writing objects", 100), remote.progress.lastOrNull { it != null })
        assertEquals(null, remote.stateRepository.taskProgress.value)
    }

    @Test
    fun `new commits go to the upstream`(): Unit = runBlocking {
        push()
        commit("second")

        assertEquals(Either.Ok(Unit), push())
        assertEquals(head(), remoteRef("refs/heads/main"))
    }

    @Test
    fun `a push behind the remote is refused with the reason`(): Unit = runBlocking {
        push()
        pushFromAnotherClone()
        commit("local")

        val error = pushError()

        assertInstanceOf(RemoteOperationError.RefsRejected::class.java, error)
        assertEquals(
            listOf(RejectedRef("refs/heads/main", RejectReason.FETCH_FIRST, "fetch first")),
            (error as RemoteOperationError.RefsRejected).refs,
        )
        assertTrue(error.output.contains("failed to push some refs")) { error.output }
    }

    @Test
    fun `a force push with lease is refused when the remote moved since the last fetch`(): Unit = runBlocking {
        push()
        pushFromAnotherClone()
        git.run(work, "commit", "--amend", "-m", "amended")

        val error = pushError(force = true)

        assertEquals(
            listOf(RejectedRef("refs/heads/main", RejectReason.STALE_INFO, "stale info")),
            (error as RemoteOperationError.RefsRejected).refs,
        )
    }

    @Test
    fun `a force push with a current lease replaces the remote branch`(): Unit = runBlocking {
        push()
        git.run(work, "commit", "--amend", "-m", "amended")

        assertEquals(Either.Ok(Unit), push(force = true))
        assertEquals(head(), remoteRef("refs/heads/main"))
    }

    @Test
    fun `tags are pushed with the branch when asked`(): Unit = runBlocking {
        git.run(work, "tag", "-a", "v1", "-m", "Version 1")

        assertEquals(Either.Ok(Unit), push(pushTags = true))
        assertEquals(git.run(work, "rev-parse", "v1").trim(), remoteRef("refs/tags/v1"))
    }

    @Test
    fun `a refusal by the remote's pre-receive hook shows its message`(): Unit = runBlocking {
        File(bare, "hooks/pre-receive").writeExecutable("#!/bin/sh\necho 'denied by policy' >&2\nexit 1\n")

        val error = pushError() as RemoteOperationError.RefsRejected

        assertEquals(
            listOf(RejectedRef("refs/heads/main", RejectReason.REMOTE_REJECTED, "pre-receive hook declined")),
            error.refs,
        )
        assertTrue(error.output.contains("remote: denied by policy")) { error.output }
    }

    @Test
    fun `a failing pre-push hook stops the push and its output is shown`(): Unit = runBlocking {
        File(work, ".git/hooks/pre-push").writeExecutable("#!/bin/sh\necho 'local hook says no' >&2\nexit 1\n")

        val error = pushError()

        assertInstanceOf(RemoteOperationError.Failed::class.java, error)
        assertTrue((error as RemoteOperationError).output.contains("local hook says no")) { error.output }
        assertNull(remoteRef("refs/heads/main"))
    }

    @Test
    fun `pushing to a chosen remote branch updates that branch only`(): Unit = runBlocking {
        push()
        val pushedBefore = head()
        git.run(work, "push", "origin", "main:other")
        commit("for other")

        val other = Branch(hash = pushedBefore, name = "refs/remotes/origin/other", isLocal = false)
        assertEquals(Either.Ok(Unit), push(specificBranch = other))

        assertEquals(head(), remoteRef("refs/heads/other"))
        assertEquals(pushedBefore, remoteRef("refs/heads/main"))
        assertEquals(
            listOf("refs/heads/main", "refs/heads/other"),
            git.run(bare, "for-each-ref", "--format=%(refname)").lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `a detached HEAD isn't pushed`(): Unit = runBlocking {
        git.run(work, "checkout", "--detach")

        assertInstanceOf(GenericError::class.java, pushError())
    }

    @Test
    fun `cancelling the push stops git and its hooks`(): Unit = runBlocking {
        val pidFile = File(tempDir, "hook.pid")
        File(work, ".git/hooks/pre-push").writeExecutable("#!/bin/sh\necho $$ > '${pidFile.absolutePath}'\nexec sleep 30\n")

        val pushing = async(Dispatchers.Default) { push() }

        withTimeout(10.seconds) {
            while (!pidFile.isFile || pidFile.readText().isBlank()) {
                delay(50)
            }
        }
        val hook = ProcessHandle.of(pidFile.readText().trim().toLong())

        pushing.cancel()
        withTimeout(10.seconds) { pushing.join() }

        assertTrue(pushing.isCancelled)
        assertFalse(hook.map { it.isAlive }.orElse(false)) { "The hook still runs" }
        assertNull(remoteRef("refs/heads/main"))
    }

    @Test
    fun `deleting a remote branch deletes it on the remote and its remote-tracking branch`(): Unit = runBlocking {
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")

        assertEquals(Either.Ok(Unit), deleteRemoteBranch("refs/remotes/origin/feature"))

        assertNull(remoteRef("refs/heads/feature"))
        assertNull(localRef("refs/remotes/origin/feature"))
    }

    @Test
    fun `a remote-tracking branch outside the fetch refspec is deleted too`(): Unit = runBlocking {
        // As after git clone --single-branch: git only updates the remote-tracking branches its refspec maps
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")
        git.run(work, "config", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/main")

        assertEquals(Either.Ok(Unit), deleteRemoteBranch("refs/remotes/origin/feature"))

        assertNull(remoteRef("refs/heads/feature"))
        assertNull(localRef("refs/remotes/origin/feature"))
    }

    @Test
    fun `deleting a remote branch that is already gone removes its remote-tracking branch`(): Unit = runBlocking {
        git.run(work, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")
        git.run(bare, "branch", "-D", "feature")

        assertEquals(Either.Ok(Unit), deleteRemoteBranch("refs/remotes/origin/feature"))
        assertNull(localRef("refs/remotes/origin/feature"))
    }

    private suspend fun push(
        force: Boolean = false,
        pushTags: Boolean = false,
        pushWithLease: Boolean = true,
        specificBranch: Branch? = null,
    ): Either<Unit, GitError> {
        val action = GitCliPushBranchGitAction(jgit, GetTrackingBranchGitAction(jgit), remote.command)

        return action(gitDir, force, pushTags, pushWithLease, specificBranch)
    }

    private suspend fun pushError(force: Boolean = false): GitError {
        val result = push(force = force)
        assertInstanceOf(Either.Err::class.java, result)

        return (result as Either.Err).error
    }

    private suspend fun deleteRemoteBranch(name: String): Either<Unit, GitError> {
        val ref = Branch(hash = head(), name = name, isLocal = false)

        return GitCliDeleteRemoteBranchGitAction(jgit, DeleteBranchGitAction(jgit), remote.command)(gitDir, ref)
    }

    /** Pushes a commit to `main` from another clone, so that the remote has a commit that `work` doesn't. */
    private fun pushFromAnotherClone() {
        val other = File(tempDir, "other")
        git.run(tempDir, "clone", bare.absolutePath, other.absolutePath)
        File(other, "other.txt").writeText("other\n")
        git.run(other, "add", ".")
        git.run(other, "commit", "-m", "From another clone")
        git.run(other, "push", "origin", "main")
    }

    private fun commit(message: String) {
        File(work, "file.txt").appendText("$message\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", message)
    }

    private fun head() = git.run(work, "rev-parse", "HEAD").trim()

    private fun remoteRef(name: String) = runCatching { git.run(bare, "rev-parse", "--verify", "-q", name).trim() }.getOrNull()

    private fun localRef(name: String) = runCatching { git.run(work, "rev-parse", "--verify", "-q", name).trim() }.getOrNull()
}
