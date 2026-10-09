// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.WorktreeBranchUse
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BranchWorktreesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `finds nothing in a repository without linked worktrees`() {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "branch", "develop")

        assertNull(worktreeUsing(main, "develop"))
    }

    @Test
    fun `finds a branch checked out in a linked worktree, where git says it is`() {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "worktree", "add", "-b", "develop", "../wt-develop")

        val worktree = worktreeUsing(main, "develop")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "develop"), WorktreeBranchUse.CheckedOut), worktree)
        assertEquals(File(tempDir, "wt-develop").canonicalPath, worktree?.path)
    }

    @Test
    fun `finds a branch checked out in a worktree inside the main one, as agents create them`() {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "worktree", "add", "-b", "claude/agent-1", ".claude/worktrees/agent-1")

        val worktree = worktreeUsing(main, "claude/agent-1")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "claude/agent-1"), WorktreeBranchUse.CheckedOut), worktree)
        assertEquals(File(main, ".claude/worktrees/agent-1").canonicalPath, worktree?.path)
    }

    @Test
    fun `finds the main worktree's branch from a linked worktree`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)

        val worktree = worktreeUsing(linked, "main")

        assertEquals(WorktreeUsingBranch(gitRefusal(linked, "main"), WorktreeBranchUse.CheckedOut), worktree)
        assertEquals(main.canonicalPath, worktree?.path)
    }

    @Test
    fun `finds a branch checked out in another linked worktree`() {
        val main = git.initRepository(File(tempDir, "main"))
        val first = File(tempDir, "wt-first")
        git.run(main, "worktree", "add", "-b", "first", first.path)
        git.run(main, "worktree", "add", "-b", "second", "../wt-second")

        val worktree = worktreeUsing(first, "second")

        assertEquals(WorktreeUsingBranch(gitRefusal(first, "second"), WorktreeBranchUse.CheckedOut), worktree)
    }

    @Test
    fun `leaves out the worktree it is asked from`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)

        assertNull(worktreeUsing(linked, "develop"))
        assertNull(worktreeUsing(main, "main"))
    }

    @Test
    fun `finds nothing for the branch of a worktree whose HEAD is detached`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        git.run(linked, "switch", "--detach")

        assertNull(worktreeUsing(main, "develop"))
        // Which git allows too
        git.run(main, "checkout", "develop")
    }

    @Test
    fun `finds a branch that a worktree is rebasing`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = worktreeWithConflictingRebase(main, "rebase", "main")

        val worktree = worktreeUsing(main, "feature")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "feature"), WorktreeBranchUse.Rebasing), worktree)
        assertEquals(linked.canonicalPath, worktree?.path)
    }

    @Test
    fun `finds a branch that a worktree is rebasing with the apply backend`() {
        val main = git.initRepository(File(tempDir, "main"))
        worktreeWithConflictingRebase(main, "rebase", "--apply", "main")

        val worktree = worktreeUsing(main, "feature")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "feature"), WorktreeBranchUse.Rebasing), worktree)
    }

    @Test
    fun `finds the branch that a worktree started a bisect from`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-feature")
        git.run(main, "worktree", "add", "-b", "feature", linked.path)
        repeat(3) { commitFile(linked, "feature.txt", "feature $it") }
        git.run(linked, "bisect", "start", "HEAD", "HEAD~3")

        val worktree = worktreeUsing(main, "feature")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "feature"), WorktreeBranchUse.Bisecting), worktree)
    }

    @Test
    fun `finds a branch checked out in a worktree whose folder was deleted, as git does`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        linked.deleteRecursively()

        val worktree = worktreeUsing(main, "develop")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "develop"), WorktreeBranchUse.CheckedOut), worktree)
    }

    @Test
    fun `reads worktree paths that git keeps relative`() {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "-c", "worktree.useRelativePaths=true", "worktree", "add", "-b", "develop", "../wt-develop")
        val gitdirFile = File(main, ".git/worktrees/wt-develop/gitdir")
        assumeTrue(!gitdirFile.readText().startsWith("/")) { "git before 2.48 ignores worktree.useRelativePaths" }

        val worktree = worktreeUsing(main, "develop")

        assertEquals(WorktreeUsingBranch(gitRefusal(main, "develop"), WorktreeBranchUse.CheckedOut), worktree)
        assertEquals(File(tempDir, "wt-develop").canonicalPath, worktree?.path)
    }

    @Test
    fun `leaves out a bare main repository, as git does`() {
        val main = git.initRepository(File(tempDir, "main"))
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "--bare", main.path, bare.path)
        val linked = File(tempDir, "wt-develop")
        git.run(bare, "worktree", "add", "-b", "develop", linked.path)

        // The bare repository's HEAD is main
        assertNull(worktreeUsing(linked, "main"))
        git.run(linked, "checkout", "main")
    }

    @Test
    fun `can't see the branch of a worktree whose refs are in a reftable`() {
        val main = File(tempDir, "main").apply { mkdirs() }
        val initialized = runCatching { git.run(main, "init", "--ref-format=reftable") }
        assumeTrue(initialized.isSuccess) { "git before 2.45 has no reftable" }
        commitFile(main, "README.md", "Test repository")
        git.run(main, "worktree", "add", "-b", "develop", "../wt-develop")
        gitRefusal(main, "develop")

        // Its HEAD file is only a stub, and JGit 7.7 reads every worktree's HEAD from the shared reftable, which has
        // main's. If JGit learns to read it, this guard should too.
        assertEquals("ref: refs/heads/.invalid", File(main, ".git/worktrees/wt-develop/HEAD").readText().trim())
        val linkedHead = runBlocking {
            jgit.provide(File(main, ".git/worktrees/wt-develop").path) { git -> git.repository.fullBranch }
        }
        assertEquals(Either.Ok("refs/heads/main"), linkedHead)
        assertNull(worktreeUsing(main, "develop"))
    }

    /**
     * Adds the worktree `wt-feature` on a new branch `feature`, and runs `git <rebaseArgs>` there, which stops on a
     * conflict with a commit on main.
     */
    private fun worktreeWithConflictingRebase(main: File, vararg rebaseArgs: String): File {
        val linked = File(tempDir, "wt-feature")
        git.run(main, "worktree", "add", "-b", "feature", linked.path)
        commitFile(linked, "README.md", "Feature")
        commitFile(main, "README.md", "Main")

        git.runFailing(linked, *rebaseArgs)
        check(git.run(linked, "branch", "--show-current").isBlank()) { "The rebase didn't stop" }

        return linked
    }

    private fun commitFile(repository: File, fileName: String, content: String) {
        File(repository, fileName).writeText(content)
        git.run(repository, "add", fileName)
        git.run(repository, "commit", "-m", content)
    }

    /** What [findOtherWorktreeUsing] finds for [branch] from [worktree], opened as Leaf opens it, by its git dir. */
    private fun worktreeUsing(worktree: File, branch: String): WorktreeUsingBranch? = runBlocking {
        val gitDir = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()
        val result = jgit.provide(gitDir) { git -> git.repository.findOtherWorktreeUsing("refs/heads/$branch") }

        assertInstanceOf(Either.Ok::class.java, result).value as WorktreeUsingBranch?
    }

    /** The worktree that git names when it refuses to check out [branch] in [worktree]. */
    private fun gitRefusal(worktree: File, branch: String): String {
        val refusal = Regex("'${Regex.escape(branch)}' is already used by worktree at '(.+)'")
        val output = git.runFailing(worktree, "checkout", branch)

        return refusal.find(output)?.groupValues?.get(1) ?: fail("git refused for another reason: $output")
    }
}
