// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.WorktreeBranchUse
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckoutBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = CheckoutBranchGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `checks out a local branch`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "branch", "develop")

        val result = action(gitDir(main), localBranch("develop"))

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(main))
    }

    @Test
    fun `refuses a branch checked out in another worktree, as git does`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)

        val result = action(gitDir(main), localBranch("develop"))

        assertEquals(Either.Err(usedByWorktree("develop", linked, WorktreeBranchUse.CheckedOut)), result)
        assertEquals(gitRefusal(main, "develop"), linked.canonicalPath)
        assertEquals("main", currentBranch(main))
        assertEquals("develop", currentBranch(linked))
    }

    @Test
    fun `refuses, from a linked worktree, the branch of the main worktree`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(main, ".claude/worktrees/agent-1")
        git.run(main, "worktree", "add", "-b", "claude/agent-1", linked.path)

        val result = action(gitDir(linked), localBranch("main"))

        assertEquals(Either.Err(usedByWorktree("main", main, WorktreeBranchUse.CheckedOut)), result)
        assertEquals(gitRefusal(linked, "main"), main.canonicalPath)
        assertEquals("claude/agent-1", currentBranch(linked))
    }

    @Test
    fun `refuses a branch that another worktree is rebasing`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-feature")
        git.run(main, "worktree", "add", "-b", "feature", linked.path)
        commitFile(linked, "README.md", "Feature")
        commitFile(main, "README.md", "Main")
        git.runFailing(linked, "rebase", "main")

        val result = action(gitDir(main), localBranch("feature"))

        assertEquals(Either.Err(usedByWorktree("feature", linked, WorktreeBranchUse.Rebasing)), result)
        assertEquals(gitRefusal(main, "feature"), linked.canonicalPath)
        assertEquals("main", currentBranch(main))
    }

    @Test
    fun `checks out, from a linked worktree, a branch that no other worktree has`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        git.run(main, "branch", "feature")

        val result = action(gitDir(linked), localBranch("feature"))

        assertEquals(Either.Ok(Unit), result)
        assertEquals("feature", currentBranch(linked))
        assertEquals("main", currentBranch(main))
    }

    @Test
    fun `checks out the branch a worktree already has, even when another worktree has it too`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        // How Leaf could put two worktrees on one branch before this guard
        git.run(main, "checkout", "--ignore-other-worktrees", "develop")

        val result = action(gitDir(linked), localBranch("develop"))

        // git says "Already on 'develop'"
        git.run(linked, "checkout", "develop")
        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", currentBranch(linked))
    }

    private fun commitFile(repository: File, fileName: String, content: String) {
        File(repository, fileName).writeText(content)
        git.run(repository, "add", fileName)
        git.run(repository, "commit", "-m", content)
    }

    private fun currentBranch(worktree: File) = git.run(worktree, "branch", "--show-current").trim()

    /** The git dir that Leaf opens for [worktree]: `.git` in the main one, `.git/worktrees/<name>` for a linked one. */
    private fun gitDir(worktree: File) = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()

    private fun localBranch(name: String) = Branch(hash = "", name = "refs/heads/$name", isLocal = true)

    private fun usedByWorktree(branch: String, worktree: File, use: WorktreeBranchUse) =
        CheckoutBranchError.BranchUsedByWorktree(branch, worktree.canonicalPath, use)

    /** The worktree that git names when it refuses to check out [branch] in [worktree]. */
    private fun gitRefusal(worktree: File, branch: String): String {
        val refusal = Regex("'${Regex.escape(branch)}' is already used by worktree at '(.+)'")
        val output = git.runFailing(worktree, "checkout", branch)

        return refusal.find(output)?.groupValues?.get(1) ?: fail("git refused for another reason: $output")
    }
}
