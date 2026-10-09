// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.DeleteBranchError
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.WorktreeBranchUse
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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

    @Test
    fun `keeps a branch checked out in another worktree, even with force, as git does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)
        git.run(worktree, "commit", "--allow-empty", "-m", "Agent's work")

        for (force in listOf(false, true)) {
            val result = action(gitDir(repository), branch("agent"), force)

            assertEquals(
                Either.Err(usedByWorktree("agent", gitRefusal(repository, "agent"), WorktreeBranchUse.CheckedOut)),
                result,
                "force = $force",
            )
        }

        assertEquals(worktree.canonicalPath, gitRefusal(repository, "agent"))
        assertTrue(hasRef(repository, "refs/heads/agent"))
        assertEquals("agent", git.run(worktree, "branch", "--show-current").trim())
    }

    @Test
    fun `keeps the main worktree's branch when deleting from a linked worktree`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)

        val result = action(File(repository, ".git/worktrees/wt-agent").path, branch("main"), force = true)

        assertEquals(
            Either.Err(usedByWorktree("main", gitRefusal(worktree, "main"), WorktreeBranchUse.CheckedOut)),
            result,
        )
        assertEquals(repository.canonicalPath, gitRefusal(worktree, "main"))
        assertTrue(hasRef(repository, "refs/heads/main"))
    }

    @Test
    fun `keeps the branch of the worktree it is opened in, as git does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))

        val result = action(gitDir(repository), branch("main"), force = true)

        assertEquals(
            Either.Err(usedByWorktree("main", gitRefusal(repository, "main"), WorktreeBranchUse.CheckedOut)),
            result,
        )
        assertTrue(hasRef(repository, "refs/heads/main"))
    }

    @Test
    fun `keeps a branch that its own worktree is rebasing, whose HEAD is detached`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        File(repository, "README.md").writeText("Main")
        git.run(repository, "commit", "-am", "Main")
        git.run(repository, "switch", "-c", "feature", "HEAD~1")
        File(repository, "README.md").writeText("Feature")
        git.run(repository, "commit", "-am", "Feature")
        git.runFailing(repository, "rebase", "main")

        val result = action(gitDir(repository), branch("feature"), force = true)

        assertEquals(
            Either.Err(usedByWorktree("feature", gitRefusal(repository, "feature"), WorktreeBranchUse.Rebasing)),
            result,
        )
        assertTrue(hasRef(repository, "refs/heads/feature"))
        // The rebase can still finish
        git.run(repository, "rebase", "--abort")
        assertEquals("feature", git.run(repository, "branch", "--show-current").trim())
    }

    @Test
    fun `keeps a branch that another worktree is bisecting from`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-feature")
        git.run(repository, "worktree", "add", "-b", "feature", worktree.path)
        repeat(3) { index -> git.run(worktree, "commit", "--allow-empty", "-m", "Feature $index") }
        git.run(worktree, "bisect", "start", "HEAD", "HEAD~3")

        val result = action(gitDir(repository), branch("feature"), force = true)

        assertEquals(
            Either.Err(usedByWorktree("feature", gitRefusal(repository, "feature"), WorktreeBranchUse.Bisecting)),
            result,
        )
        assertTrue(hasRef(repository, "refs/heads/feature"))
    }

    @Test
    fun `keeps a branch checked out in a worktree whose folder was deleted, as git does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)
        worktree.deleteRecursively()

        val result = action(gitDir(repository), branch("agent"), force = true)

        assertEquals(
            Either.Err(usedByWorktree("agent", gitRefusal(repository, "agent"), WorktreeBranchUse.CheckedOut)),
            result,
        )
        assertTrue(hasRef(repository, "refs/heads/agent"))
    }

    @Test
    fun `deletes a branch once its worktree has left it`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)
        git.run(worktree, "switch", "--detach")

        val result = action(gitDir(repository), branch("agent"), force = false)

        assertEquals(Either.Ok(Unit), result)
        assertFalse(hasRef(repository, "refs/heads/agent"))
    }

    /** Creates [name] from main with [commits] commits of its own, then checks out main again. */
    private fun createBranchWithCommits(repository: File, name: String, commits: Int) {
        git.run(repository, "switch", "-c", name)

        repeat(commits) { index ->
            git.run(repository, "commit", "--allow-empty", "-m", "$name ${index + 1}")
        }

        git.run(repository, "switch", "main")
    }

    /** The worktree that git names when it refuses to delete [branch] from [worktree], even with `-D`. */
    private fun gitRefusal(worktree: File, branch: String): String {
        val refusal = Regex("cannot delete branch '${Regex.escape(branch)}' used by worktree at '(.+)'")
        val output = git.runFailing(worktree, "branch", "-D", branch)

        return refusal.find(output)?.groupValues?.get(1) ?: fail("git refused for another reason: $output")
    }

    private fun usedByWorktree(branch: String, worktreePath: String, use: WorktreeBranchUse) =
        DeleteBranchError.BranchUsedByWorktree(branch, worktreePath, use)

    private fun hasRef(repository: File, refName: String) =
        git.run(repository, "for-each-ref", "--format=%(refname)", refName).isNotBlank()

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun branch(name: String) = Branch(hash = "", name = "refs/heads/$name", isLocal = true)
}
