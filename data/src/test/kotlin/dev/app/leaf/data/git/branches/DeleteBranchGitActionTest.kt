// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeleteBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = DeleteBranchGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `deletes a merged branch without force`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "merged")

        val result = action(gitDir(repository), branch("merged"), force = false)

        assertEquals(Either.Ok(Unit), result)
        assertFalse(hasRef(repository, "refs/heads/merged"))
    }

    @Test
    fun `keeps an unmerged branch without force and counts the commits only it has`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 2)

        val result = action(gitDir(repository), branch("feature"), force = false)

        assertEquals(Either.Err(DeleteRefError.BranchNotMerged("feature", commitsOnlyOnRef = 2)), result)
        assertTrue(hasRef(repository, "refs/heads/feature"))
    }

    @Test
    fun `deletes an unmerged branch with force`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 2)

        val result = action(gitDir(repository), branch("feature"), force = true)

        assertEquals(Either.Ok(Unit), result)
        assertFalse(hasRef(repository, "refs/heads/feature"))
    }

    @Test
    fun `counts no lost commits when another branch has them`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 2)
        git.run(repository, "branch", "copy", "feature")

        val result = action(gitDir(repository), branch("feature"), force = false)

        assertEquals(Either.Err(DeleteRefError.BranchNotMerged("feature", commitsOnlyOnRef = 0)), result)
    }

    @Test
    fun `counts no lost commits when they are pushed`(): Unit = runBlocking {
        val upstream = git.initRepository(File(tempDir, "upstream"))
        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)
        createBranchWithCommits(clone, "feature", commits = 1)
        git.run(clone, "push", "origin", "feature")

        val result = action(gitDir(clone), branch("feature"), force = false)

        assertEquals(
            Either.Err(DeleteRefError.BranchNotMerged("feature", commitsOnlyOnRef = 0)),
            result,
            "Like git branch -d, a branch has to be merged into HEAD; its remote-tracking branch only saves the commits",
        )
    }

    @Test
    fun `keeps a branch without force when HEAD is unborn`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 1)
        git.run(repository, "switch", "--orphan", "empty")

        val withoutForce = action(gitDir(repository), branch("feature"), force = false)

        assertEquals(Either.Err(DeleteRefError.BranchNotMerged("feature", commitsOnlyOnRef = 1)), withoutForce)
        assertTrue(hasRef(repository, "refs/heads/feature"))

        val withForce = action(gitDir(repository), branch("feature"), force = true)

        assertEquals(Either.Ok(Unit), withForce)
        assertFalse(hasRef(repository, "refs/heads/feature"))
    }

    @Test
    fun `checks the merge against the HEAD of the linked worktree it is opened in`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 1)
        val worktree = File(tempDir, "worktree")
        git.run(repository, "worktree", "add", worktree.absolutePath, "-b", "agent", "feature")

        val fromMain = action(gitDir(repository), branch("feature"), force = false)

        assertEquals(
            Either.Err(DeleteRefError.BranchNotMerged("feature", commitsOnlyOnRef = 0)),
            fromMain,
            "The agent branch has the commit",
        )

        val worktreeGitDir = File(repository, ".git/worktrees/worktree").absolutePath
        val fromWorktree = action(worktreeGitDir, branch("feature"), force = false)

        assertEquals(Either.Ok(Unit), fromWorktree)
        assertFalse(hasRef(repository, "refs/heads/feature"))
    }

    /** Creates [name] from main with [commits] commits of its own, then checks out main again. */
    private fun createBranchWithCommits(repository: File, name: String, commits: Int) {
        git.run(repository, "switch", "-c", name)

        repeat(commits) { index ->
            git.run(repository, "commit", "--allow-empty", "-m", "$name ${index + 1}")
        }

        git.run(repository, "switch", "main")
    }

    private fun hasRef(repository: File, refName: String) =
        git.run(repository, "for-each-ref", "--format=%(refname)", refName).isNotBlank()

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun branch(name: String) = Branch(hash = "", name = "refs/heads/$name", isLocal = true)
}
