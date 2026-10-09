// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.branches.SetTrackingBranchGitAction
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.submodules.InitializeAllSubmodulesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.SshNeedsGitError
import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Nothing listens on port 1, so a connection attempt would fail with another error than the one expected. */
private const val SSH_URL = "ssh://git@127.0.0.1:1/repo.git"

/**
 * Leaf's built-in implementation (JGit) refuses SSH remotes before it connects, as Leaf reaches them only with the git
 * CLI, and says why JGit ran. Other remotes still go through JGit.
 */
class JGitWithoutSshTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()
    private lateinit var git: TestGitCli
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        git = TestGitCli(File(tempDir, "empty.gitconfig"))
        work = git.initRepository(File(tempDir, "work"))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a fetch from an SSH remote fails before connecting, with the reason JGit ran`(): Unit = runBlocking {
        git.run(work, "remote", "add", "origin", SSH_URL)

        for (reason in SshNeedsGitError.Reason.entries) {
            assertEquals(Either.Err(SshNeedsGitError(reason)), fetch(transport(reason)), "$reason")
        }
    }

    @Test
    fun `when nothing stops the git CLI, JGit ran for an LFS push that git wouldn't upload`(): Unit = runBlocking {
        git.run(work, "remote", "add", "origin", SSH_URL)
        val push = PushBranchGitAction(transport(reason = null), GetTrackingBranchGitAction(jgit), SetTrackingBranchGitAction(jgit), jgit)

        val result = push(gitDir, force = false, pushTags = false, pushWithLease = false, specificBranch = null)

        assertEquals(Either.Err(SshNeedsGitError(SshNeedsGitError.Reason.LfsPushWithoutGitLfs)), result)
    }

    @Test
    fun `scp-like URLs, and URLs that insteadOf turns into SSH ones, are SSH remotes too`(): Unit = runBlocking {
        val expected = Either.Err(SshNeedsGitError(SshNeedsGitError.Reason.SettingOff))
        git.run(work, "remote", "add", "origin", "git@127.0.0.1:repo.git")
        assertEquals(expected, fetch(transport(SshNeedsGitError.Reason.SettingOff)))

        git.run(work, "remote", "set-url", "origin", "https://example.invalid/repo.git")
        git.run(work, "config", "url.ssh://git@127.0.0.1:1/.insteadOf", "https://example.invalid/")
        assertEquals(expected, fetch(transport(SshNeedsGitError.Reason.SettingOff)))
    }

    @Test
    fun `a fetch of several remotes still fetches the others, and tells why it couldn't fetch the SSH one`(): Unit =
        runBlocking {
            val other = File(tempDir, "other.git")
            git.run(tempDir, "clone", "--bare", work.path, other.path)
            git.run(work, "remote", "add", "local", other.path)
            git.run(work, "remote", "add", "ssh", SSH_URL)

            val result = FetchAllRemotesGitAction(transport(SshNeedsGitError.Reason.SettingOff), jgit)(gitDir, null)

            val message = ((result as Either.Err).error as GenericError).message
            assertTrue(message.contains("Fetch failed for remote ssh:"), message)
            assertTrue(message.contains("\"Use git for remote operations\""), message)
            assertEquals(
                git.run(work, "rev-parse", "main"),
                git.run(work, "rev-parse", "refs/remotes/local/main"),
            )
        }

    @Test
    fun `a fetch that fails for another reason is reported too, which JGit's fetch of all remotes didn't do`(): Unit =
        runBlocking {
            git.run(work, "remote", "add", "gone", File(tempDir, "gone.git").path)

            val result = FetchAllRemotesGitAction(transport(SshNeedsGitError.Reason.SettingOff), jgit)(gitDir, null)

            val message = ((result as Either.Err).error as GenericError).message
            assertTrue(message.contains("Fetch failed for remote gone:"), message)
        }

    @Test
    fun `a clone of an SSH URL fails with the reason JGit ran`(): Unit = runBlocking {
        val clone = CloneRepositoryGitAction(transport(SshNeedsGitError.Reason.GitNotFound), InitializeAllSubmodulesGitAction())

        val states = clone(File(tempDir, "clone"), SSH_URL, cloneSubmodules = false).toList()

        assertEquals(CloneState.Fail(SshNeedsGitError(SshNeedsGitError.Reason.GitNotFound)), states.last())
    }

    @Test
    fun `a file remote still goes through JGit`(): Unit = runBlocking {
        val other = File(tempDir, "other.git")
        git.run(tempDir, "clone", "--bare", work.path, other.path)
        git.run(work, "remote", "add", "origin", other.path)

        assertEquals(Either.Ok(Unit), fetch(transport(SshNeedsGitError.Reason.SettingOff)))
    }

    private fun transport(reason: SshNeedsGitError.Reason?) = HandleTransportGitAction(
        noSshSessionFactory = NoSshSessionFactory { reason },
        httpCredentialsProvider = object : HttpCredentialsFactory {
            override fun create(git: Git?) = error("No HTTPS in this test")
        },
        jgit = jgit,
    )

    /** Fetches origin with JGit, through [transport], as Leaf's JGit actions do. */
    private suspend fun fetch(transport: HandleTransportGitAction) = Git.open(work).use { git ->
        transport(gitDir) {
            git.fetch().setRemote("origin").setTransportConfigCallback { handleTransport(it) }.call()
            Unit
        }
    }
}
