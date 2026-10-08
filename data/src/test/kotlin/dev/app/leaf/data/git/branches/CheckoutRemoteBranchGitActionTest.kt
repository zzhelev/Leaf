// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckoutRemoteBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = CheckoutRemoteBranchGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `checks out a remote branch as a new local branch that tracks it`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = false)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals("origin/develop", git.run(clone, "rev-parse", "--abbrev-ref", "develop@{upstream}").trim())
    }

    @Test
    fun `checks out the existing local branch without moving it`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "branch", "--no-track", "develop", "origin/develop~2")
        val localCommit = commitOf(clone, "develop")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = false)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals(localCommit, commitOf(clone, "develop"))
    }

    @Test
    fun `keeps the folders of a remote branch in the local branch name`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("feature/login")
        git.run(clone, "branch", "--no-track", "feature/login", "origin/feature/login")

        val result = action(gitDir(clone), remoteBranch("origin", "feature/login"), fastForward = false)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("feature/login", currentBranch(clone))
    }

    @Test
    fun `checks out a local branch that has diverged without moving it`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~1")
        commitFile(clone, "local.txt", "local")
        val localCommit = commitOf(clone, "develop")
        git.run(clone, "switch", "main")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = false)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals(localCommit, commitOf(clone, "develop"))
    }

    @Test
    fun `fast-forwards the local branch, then checks it out`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "branch", "--no-track", "develop", "origin/develop~2")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals(commitOf(clone, "origin/develop"), commitOf(clone, "develop"))
        assertEquals("develop 2", File(clone, "develop.txt").readText())
        assertEquals("", git.run(clone, "status", "--porcelain"))
        // What `git merge --ff-only` writes, so the reflog reads the same whichever way the branch moved
        assertEquals(
            "merge refs/remotes/origin/develop: Fast-forward",
            git.run(clone, "reflog", "-1", "--format=%gs", "develop").trim(),
        )
    }

    @Test
    fun `fast-forwards the current branch and its working tree`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~2")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals(commitOf(clone, "origin/develop"), commitOf(clone, "develop"))
        assertEquals("develop 2", File(clone, "develop.txt").readText())
        assertEquals("", git.run(clone, "status", "--porcelain"))
        assertEquals(
            "merge refs/remotes/origin/develop: Fast-forward",
            git.run(clone, "reflog", "-1", "--format=%gs", "develop").trim(),
        )
    }

    @Test
    fun `leaves the current branch alone when not asked to fast-forward`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop")
        val reflogBefore = git.run(clone, "reflog", "HEAD")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = false)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals(reflogBefore, git.run(clone, "reflog", "HEAD"))
    }

    @Test
    fun `refuses to fast-forward a local branch that has diverged`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~1")
        commitFile(clone, "local.txt", "local")
        val localCommit = commitOf(clone, "develop")
        git.run(clone, "switch", "main")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        assertEquals(Either.Err(CheckoutBranchError.CannotFastForward("develop", "origin/develop")), result)
        assertEquals("main", currentBranch(clone))
        assertEquals(localCommit, commitOf(clone, "develop"))
    }

    @Test
    fun `refuses to fast-forward the current branch when it has diverged`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~1")
        commitFile(clone, "local.txt", "local")
        val localCommit = commitOf(clone, "develop")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        assertEquals(Either.Err(CheckoutBranchError.CannotFastForward("develop", "origin/develop")), result)
        assertEquals(localCommit, commitOf(clone, "develop"))
        assertEquals("", git.run(clone, "status", "--porcelain"))
    }

    @Test
    fun `checks out a local branch that is ahead without moving it, even when asked to fast-forward`(): Unit =
        runBlocking {
            val clone = cloneWithRemoteBranch("develop")
            git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop")
            commitFile(clone, "local.txt", "local")
            val localCommit = commitOf(clone, "develop")
            git.run(clone, "switch", "main")

            val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

            // Like `git merge --ff-only`, which says "Already up to date"
            assertEquals(Either.Ok(Unit), result)
            assertEquals("develop", currentBranch(clone))
            assertEquals(localCommit, commitOf(clone, "develop"))
        }

    @Test
    fun `carries uncommitted changes that the fast-forward doesn't touch`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "branch", "--no-track", "develop", "origin/develop~2")
        File(clone, "README.md").writeText("Changed\n")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(clone))
        assertEquals(commitOf(clone, "origin/develop"), commitOf(clone, "develop"))
        assertEquals(" M README.md\n", git.run(clone, "status", "--porcelain"))
    }

    @Test
    fun `leaves the branch fast-forwarded when uncommitted changes block its checkout`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", changedFile = "README.md")
        git.run(clone, "branch", "--no-track", "develop", "origin/develop~2")
        File(clone, "README.md").writeText("Changed\n")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        val error = assertInstanceOf(GenericError::class.java, (result as Either.Err).error)
        assertInstanceOf(CheckoutConflictException::class.java, error.exception)
        assertEquals("main", currentBranch(clone))
        assertEquals("Changed\n", File(clone, "README.md").readText())
        // Moving a branch that isn't checked out loses nothing, so the fast-forward isn't undone
        assertEquals(commitOf(clone, "origin/develop"), commitOf(clone, "develop"))
    }

    @Test
    fun `leaves the current branch alone when uncommitted changes block the fast-forward`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop", changedFile = "README.md")
        git.run(clone, "switch", "--no-track", "-c", "develop", "origin/develop~2")
        val localCommit = commitOf(clone, "develop")
        File(clone, "README.md").writeText("Changed\n")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"), fastForward = true)

        val error = assertInstanceOf(GenericError::class.java, (result as Either.Err).error)
        assertInstanceOf(CheckoutConflictException::class.java, error.exception)
        assertEquals(localCommit, commitOf(clone, "develop"))
        assertEquals("Changed\n", File(clone, "README.md").readText())
    }

    /**
     * Clones a repository whose [branchName] has two commits on top of main, which write "<branch> 1" and "<branch> 2"
     * to [changedFile], so the clone has it only as a remote branch.
     */
    private fun cloneWithRemoteBranch(branchName: String, changedFile: String = "develop.txt"): File {
        val upstream = git.initRepository(File(tempDir, "upstream"))
        git.run(upstream, "switch", "-c", branchName)
        commitFile(upstream, changedFile, "$branchName 1")
        commitFile(upstream, changedFile, "$branchName 2")
        git.run(upstream, "switch", "main")

        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)

        return clone
    }

    private fun commitFile(repository: File, fileName: String, content: String) {
        File(repository, fileName).writeText(content)
        git.run(repository, "add", fileName)
        git.run(repository, "commit", "-m", content)
    }

    private fun currentBranch(repository: File) = git.run(repository, "branch", "--show-current").trim()

    private fun commitOf(repository: File, revision: String) = git.run(repository, "rev-parse", revision).trim()

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun remoteBranch(remote: String, name: String) =
        Branch(hash = "", name = "refs/remotes/$remote/$name", isLocal = false)
}
