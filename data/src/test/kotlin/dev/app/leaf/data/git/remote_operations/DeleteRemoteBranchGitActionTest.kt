// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.RemoteBranchChangedError
import dev.app.leaf.domain.models.Branch
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

private const val TRACKING_REF = "refs/remotes/origin/feature"

/**
 * The JGit fallback for deleting a remote branch ([DeleteRemoteBranchGitAction]), against a bare repository on disk:
 * the remote-tracking branch goes only once the remote deleted the branch.
 */
class DeleteRemoteBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()
    private lateinit var git: TestGitCli
    private lateinit var work: File
    private lateinit var origin: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        git = TestGitCli(File(tempDir, "empty.gitconfig"))
        work = git.initRepository(File(tempDir, "work"))
        origin = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", "-q", origin.path)
        git.run(work, "remote", "add", "origin", origin.path)
        git.run(work, "push", "-q", "origin", "main", "main:feature")
        git.run(work, "fetch", "-q", "origin")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a deleted branch loses its remote-tracking branch`(): Unit = runBlocking {
        assertEquals(Either.Ok(Unit), delete())

        assertEquals("", git.run(origin, "branch", "--list", "feature"))
        assertEquals("", git.run(work, "for-each-ref", TRACKING_REF))
    }

    @Test
    fun `a branch that the remote refuses to delete keeps its remote-tracking branch`(): Unit = runBlocking {
        git.run(origin, "config", "receive.denyDeletes", "true")

        val result = delete()

        assertTrue(result is Either.Err && result.error is GenericError, "$result")
        assertEquals("  feature\n", git.run(origin, "branch", "--list", "feature"))
        assertEquals(TRACKING_REF, git.run(work, "for-each-ref", "--format=%(refname)", TRACKING_REF).trim())
    }

    @Test
    fun `a branch that someone pushed to since the fetch stays`(): Unit = runBlocking {
        val other = File(tempDir, "other")
        git.run(tempDir, "clone", "-q", "-b", "feature", origin.path, other.path)
        File(other, "theirs.txt").writeText("theirs\n")
        git.run(other, "add", ".")
        git.run(other, "commit", "-q", "-m", "Their work")
        git.run(other, "push", "-q", "origin", "feature")
        val theirs = git.run(other, "rev-parse", "HEAD").trim()

        val was = git.run(work, "rev-parse", TRACKING_REF).trim()

        assertEquals(Either.Err(RemoteBranchChangedError("origin/feature", TRACKING_REF, now = theirs, was = was)), delete())

        assertEquals(theirs, git.run(origin, "rev-parse", "feature").trim())
        assertEquals(TRACKING_REF, git.run(work, "for-each-ref", "--format=%(refname)", TRACKING_REF).trim())
    }

    @Test
    fun `a branch that is already gone from the remote loses its remote-tracking branch`(): Unit = runBlocking {
        git.run(origin, "branch", "-D", "feature")

        assertEquals(Either.Ok(Unit), delete())
        assertEquals("", git.run(work, "for-each-ref", TRACKING_REF))
    }

    private suspend fun delete(): Either<Unit, *> {
        val transport = HandleTransportGitAction(
            noSshSessionFactory = NoSshSessionFactory { null },
            httpCredentialsProvider = object : HttpCredentialsFactory {
                override fun create(git: Git?) = error("No HTTPS in this test")
            },
            jgit = jgit,
        )
        val action = DeleteRemoteBranchGitAction(transport, DeleteBranchGitAction(jgit), jgit)
        val hash = git.run(work, "rev-parse", TRACKING_REF).trim()

        return action(gitDir, Branch(hash, TRACKING_REF, isLocal = false))
    }
}
