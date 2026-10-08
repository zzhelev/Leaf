// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RemoteBranchCheckout
import dev.app.leaf.domain.models.RemoteBranchCheckout.ChecksOutLocalBranch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GetRemoteBranchCheckoutGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = GetRemoteBranchCheckoutGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `creates a local branch when there is none with the remote branch's name`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 2)

        val result = action(gitDir(clone), remoteBranch("origin", "develop"))

        assertEquals(Either.Ok(RemoteBranchCheckout.CreatesLocalBranch), result)
    }

    @Test
    fun `checks out a local branch at the same commit, with nothing to fast-forward`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 2)
        git.run(clone, "branch", "--no-track", "develop", "origin/develop")

        val checkout = localBranchCheckout(clone, "develop")

        assertEquals(developCheckout(isCurrentBranch = false, commitsAhead = 0, commitsBehind = 0), checkout)
        assertFalse(checkout.canFastForward)
    }

    @Test
    fun `offers to fast-forward a local branch that is behind`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 3)
        git.run(clone, "branch", "--no-track", "develop", "origin/develop~2")

        val checkout = localBranchCheckout(clone, "develop")

        assertEquals(developCheckout(isCurrentBranch = false, commitsAhead = 0, commitsBehind = 2), checkout)
        assertEquals(countsFromGit(clone, "develop"), checkout.commitsAhead to checkout.commitsBehind)
        assertTrue(checkout.canFastForward)
    }

    @Test
    fun `doesn't offer to fast-forward a local branch that is ahead`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 1)
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop")
        git.run(clone, "commit", "--allow-empty", "-m", "Local")
        git.run(clone, "switch", "main")

        val checkout = localBranchCheckout(clone, "develop")

        assertEquals(developCheckout(isCurrentBranch = false, commitsAhead = 1, commitsBehind = 0), checkout)
        assertFalse(checkout.canFastForward)
    }

    @Test
    fun `counts both sides of a local branch that has diverged, as git does`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 3)
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~2")
        git.run(clone, "commit", "--allow-empty", "-m", "Local 1")
        git.run(clone, "commit", "--allow-empty", "-m", "Local 2")
        git.run(clone, "commit", "--allow-empty", "-m", "Local 3")
        git.run(clone, "switch", "main")

        val checkout = localBranchCheckout(clone, "develop")

        assertEquals(3 to 2, checkout.commitsAhead to checkout.commitsBehind)
        assertEquals(countsFromGit(clone, "develop"), checkout.commitsAhead to checkout.commitsBehind)
        assertFalse(checkout.canFastForward)
    }

    @Test
    fun `says when the local branch is the current branch`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 1)
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~1")

        val checkout = localBranchCheckout(clone, "develop")

        assertEquals(developCheckout(isCurrentBranch = true, commitsAhead = 0, commitsBehind = 1), checkout)
        assertTrue(checkout.canFastForward)
    }

    @Test
    fun `finds the local branch of a remote branch with folders`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("feature/login", commits = 1)
        git.run(clone, "branch", "--no-track", "feature/login", "origin/feature/login~1")

        val checkout = localBranchCheckout(clone, "feature/login")

        assertEquals("feature/login", checkout.localBranch)
        assertEquals(1, checkout.commitsBehind)
    }

    @Test
    fun `fails when the remote branch is gone`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", commits = 1)
        git.run(clone, "branch", "--no-track", "develop", "origin/develop")
        git.run(clone, "branch", "-r", "-d", "origin/develop")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"))

        assertInstanceOf(Either.Err::class.java, result)
    }

    private suspend fun localBranchCheckout(clone: File, name: String): ChecksOutLocalBranch {
        val result = action(gitDir(clone), remoteBranch("origin", name))

        return assertInstanceOf(ChecksOutLocalBranch::class.java, (result as Either.Ok).value)
    }

    private fun developCheckout(isCurrentBranch: Boolean, commitsAhead: Int, commitsBehind: Int) =
        ChecksOutLocalBranch("develop", isCurrentBranch, commitsAhead, commitsBehind)

    /** The commits that only the local branch has, then only the remote one, from `git rev-list --left-right`. */
    private fun countsFromGit(clone: File, name: String): Pair<Int, Int> {
        val (ahead, behind) = git.run(clone, "rev-list", "--left-right", "--count", "$name...origin/$name")
            .trim()
            .split("\t")
            .map { it.toInt() }

        return ahead to behind
    }

    /**
     * Clones a repository whose [branchName] has [commits] commits on top of main, so the clone has it only as a remote
     * branch.
     */
    private fun cloneWithRemoteBranch(branchName: String, commits: Int): File {
        val upstream = git.initRepository(File(tempDir, "upstream"))
        git.run(upstream, "switch", "-c", branchName)
        repeat(commits) { index ->
            git.run(upstream, "commit", "--allow-empty", "-m", "$branchName ${index + 1}")
        }
        git.run(upstream, "switch", "main")

        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)

        return clone
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun remoteBranch(remote: String, name: String) =
        Branch(hash = "", name = "refs/remotes/$remote/$name", isLocal = false)
}
