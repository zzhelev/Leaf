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

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "develop"),
                WorktreeBranchUse.CheckedOut,
                linkedGitDir(main, "wt-develop"),
            ),
            worktree,
        )
        assertEquals(File(tempDir, "wt-develop").canonicalPath, worktree?.path)
    }

    @Test
    fun `finds a branch checked out in a worktree inside the main one, as agents create them`() {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "worktree", "add", "-b", "claude/agent-1", ".claude/worktrees/agent-1")

        val worktree = worktreeUsing(main, "claude/agent-1")

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "claude/agent-1"),
                WorktreeBranchUse.CheckedOut,
                linkedGitDir(main, "agent-1"),
            ),
            worktree,
        )
        assertEquals(File(main, ".claude/worktrees/agent-1").canonicalPath, worktree?.path)
    }

    @Test
    fun `finds the main worktree's branch from a linked worktree`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)

        val worktree = worktreeUsing(linked, "main")

        assertEquals(
            WorktreeUsingBranch(gitRefusal(linked, "main"), WorktreeBranchUse.CheckedOut, mainGitDir(main)),
            worktree,
        )
        assertEquals(main.canonicalPath, worktree?.path)
    }

    @Test
    fun `finds a branch checked out in another linked worktree`() {
        val main = git.initRepository(File(tempDir, "main"))
        val first = File(tempDir, "wt-first")
        git.run(main, "worktree", "add", "-b", "first", first.path)
        git.run(main, "worktree", "add", "-b", "second", "../wt-second")

        val worktree = worktreeUsing(first, "second")

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(first, "second"),
                WorktreeBranchUse.CheckedOut,
                linkedGitDir(main, "wt-second"),
            ),
            worktree,
        )
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

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "feature"),
                WorktreeBranchUse.Rebasing,
                linkedGitDir(main, "wt-feature"),
            ),
            worktree,
        )
        assertEquals(linked.canonicalPath, worktree?.path)
    }

    @Test
    fun `finds a branch that a worktree is rebasing with the apply backend`() {
        val main = git.initRepository(File(tempDir, "main"))
        worktreeWithConflictingRebase(main, "rebase", "--apply", "main")

        val worktree = worktreeUsing(main, "feature")

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "feature"),
                WorktreeBranchUse.Rebasing,
                linkedGitDir(main, "wt-feature"),
            ),
            worktree,
        )
    }

    @Test
    fun `finds the branch that a worktree started a bisect from`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-feature")
        git.run(main, "worktree", "add", "-b", "feature", linked.path)
        repeat(3) { commitFile(linked, "feature.txt", "feature $it") }
        git.run(linked, "bisect", "start", "HEAD", "HEAD~3")

        val worktree = worktreeUsing(main, "feature")

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "feature"),
                WorktreeBranchUse.Bisecting,
                linkedGitDir(main, "wt-feature"),
            ),
            worktree,
        )
    }

    @Test
    fun `finds a branch checked out in a worktree whose folder was deleted, as git does`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        linked.deleteRecursively()

        val worktree = worktreeUsing(main, "develop")

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "develop"),
                WorktreeBranchUse.CheckedOut,
                linkedGitDir(main, "wt-develop"),
            ),
            worktree,
        )
    }

    @Test
    fun `reads worktree paths that git keeps relative`() {
        val main = git.initRepository(File(tempDir, "main"))
        git.run(main, "-c", "worktree.useRelativePaths=true", "worktree", "add", "-b", "develop", "../wt-develop")
        val gitdirFile = File(main, ".git/worktrees/wt-develop/gitdir")
        assumeTrue(!gitdirFile.readText().startsWith("/")) { "git before 2.48 ignores worktree.useRelativePaths" }

        val worktree = worktreeUsing(main, "develop")

        assertEquals(
            WorktreeUsingBranch(
                gitRefusal(main, "develop"),
                WorktreeBranchUse.CheckedOut,
                linkedGitDir(main, "wt-develop"),
            ),
            worktree,
        )
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

    @Test
    fun `lists every worktree that uses a branch, its own included, main worktree first`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-also-main")
        // git checks out a branch in a second worktree only when forced
        git.run(main, "worktree", "add", "--force", linked.path, "main")
        git.run(main, "worktree", "add", "-b", "develop", "../wt-develop")

        val expected = listOf(
            WorktreeUsingBranch(main.canonicalPath, WorktreeBranchUse.CheckedOut, mainGitDir(main)),
            WorktreeUsingBranch(linked.canonicalPath, WorktreeBranchUse.CheckedOut, linkedGitDir(main, "wt-also-main")),
        )

        assertEquals(expected, worktreesUsing(main, "main"))
        assertEquals(expected, worktreesUsing(linked, "main"))
        // git names the first one too
        assertEquals(main.canonicalPath, gitRefusal(File(tempDir, "wt-develop"), "main"))
    }

    @Test
    fun `lists the worktree it is asked from when it rebases the branch`() {
        val main = git.initRepository(File(tempDir, "main"))
        commitFile(main, "README.md", "Main")
        git.run(main, "switch", "-c", "feature", "HEAD~1")
        commitFile(main, "README.md", "Feature")
        git.runFailing(main, "rebase", "main")

        assertEquals(
            listOf(WorktreeUsingBranch(main.canonicalPath, WorktreeBranchUse.Rebasing, mainGitDir(main))),
            worktreesUsing(main, "feature"),
        )
        assertNull(worktreeUsing(main, "feature"), "findOtherWorktreeUsing leaves out the worktree it is asked from")
    }

    @Test
    fun `reads the branch that each worktree uses, as full names`() {
        val main = git.initRepository(File(tempDir, "main"))
        val rebasing = worktreeWithConflictingRebase(main, "rebase", "main")
        val bisecting = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", bisecting.path)
        repeat(3) { commitFile(bisecting, "develop.txt", "develop $it") }
        git.run(bisecting, "bisect", "start", "HEAD", "HEAD~3")
        // git keeps the branch a bisect started from without refs/heads/
        assertEquals("develop", File(linkedGitDir(main, "wt-develop"), "BISECT_START").readText().trim())

        assertEquals(
            listOf(
                WorktreeHead(main.canonicalPath, mainGitDir(main), "refs/heads/main", null, null),
                WorktreeHead(
                    bisecting.canonicalPath,
                    linkedGitDir(main, "wt-develop"),
                    branch = null,
                    rebasingBranch = null,
                    bisectingBranch = "refs/heads/develop",
                ),
                WorktreeHead(
                    rebasing.canonicalPath,
                    linkedGitDir(main, "wt-feature"),
                    branch = null,
                    rebasingBranch = "refs/heads/feature",
                    bisectingBranch = null,
                ),
            ),
            worktreeHeads(main),
        )
    }

    @Test
    fun `finds no branch for a rebase started on a detached HEAD, as git does`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        commitFile(linked, "README.md", "Develop")
        commitFile(main, "README.md", "Main")
        git.run(linked, "switch", "--detach")
        git.runFailing(linked, "rebase", "main")
        val headName = File(linkedGitDir(main, "wt-develop"), "rebase-merge/head-name")
        assertEquals("detached HEAD", headName.readText().trim())

        assertNull(worktreeHeads(main).single { it.path == linked.canonicalPath }.rebasingBranch)
        assertNull(worktreeUsing(main, "develop"))
        git.run(main, "checkout", "develop")
    }

    @Test
    fun `finds no branch for a bisect started on a detached HEAD, as git does`() {
        val main = git.initRepository(File(tempDir, "main"))
        val linked = File(tempDir, "wt-develop")
        git.run(main, "worktree", "add", "-b", "develop", linked.path)
        repeat(3) { commitFile(linked, "develop.txt", "develop $it") }
        git.run(linked, "switch", "--detach")
        git.run(linked, "bisect", "start", "HEAD", "HEAD~3")
        // git keeps the commit instead of a branch
        val bisectStart = File(linkedGitDir(main, "wt-develop"), "BISECT_START").readText().trim()
        assertEquals(git.run(main, "rev-parse", "develop").trim(), bisectStart)

        assertNull(worktreeHeads(main).single { it.path == linked.canonicalPath }.bisectingBranch)
        assertNull(worktreeUsing(main, "develop"))
        git.run(main, "checkout", "develop")
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

    /** What [worktreesUsing] lists for [branch] from [worktree], opened as Leaf opens it, by its git dir. */
    private fun worktreesUsing(worktree: File, branch: String): List<WorktreeUsingBranch> = runBlocking {
        val gitDir = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()
        val result = jgit.provide(gitDir) { git -> git.repository.worktreesUsing("refs/heads/$branch") }

        assertInstanceOf(Either.Ok::class.java, result).value.let { value ->
            (value as List<*>).map { it as WorktreeUsingBranch }
        }
    }

    /** What [worktreeHeads] reads from [worktree], opened as Leaf opens it, by its git dir. */
    private fun worktreeHeads(worktree: File): List<WorktreeHead> = runBlocking {
        val gitDir = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()
        val result = jgit.provide(gitDir) { git -> git.repository.worktreeHeads() }

        assertInstanceOf(Either.Ok::class.java, result).value.let { value ->
            (value as List<*>).map { it as WorktreeHead }
        }
    }

    private fun mainGitDir(main: File) = File(main, ".git").canonicalFile

    /** The git dir of the linked worktree [name] of [main], which git names after the worktree's folder. */
    private fun linkedGitDir(main: File, name: String) = File(main, ".git/worktrees/$name").canonicalFile

    /** The worktree that git names when it refuses to check out [branch] in [worktree]. */
    private fun gitRefusal(worktree: File, branch: String): String {
        val refusal = Regex("'${Regex.escape(branch)}' is already used by worktree at '(.+)'")
        val output = git.runFailing(worktree, "checkout", branch)

        return refusal.find(output)?.groupValues?.get(1) ?: fail("git refused for another reason: $output")
    }
}
