// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.BranchMerged
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The git actions behind removing, discarding and pruning worktrees, against a repository made with the git CLI: main,
 * and agent next to it, on a branch with two commits that main doesn't have.
 */
@DisabledOnOs(OS.WINDOWS)
class WorktreeLifecycleGitActionsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val gitCli by lazy {
        testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }

    private val main by lazy { File(tempDir, "main") }
    private val agent by lazy { File(tempDir, "agent") }
    private val mainGitDir by lazy { File(main, ".git").path }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init")
        File(main, "a.txt").writeText("one\ntwo\nthree\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")
        git.run(main, "worktree", "add", agent.path, "-b", "agent")

        File(agent, "b.txt").writeText("b\n")
        git.run(agent, "add", "b.txt")
        git.run(agent, "commit", "-m", "Add b")
        File(agent, "a.txt").writeText("one\nTWO\nthree\n")
        git.run(agent, "commit", "-am", "Change two")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a branch with commits that the base doesn't have isn't merged`(): Unit = runBlocking {
        assertEquals(BranchMerged(isMerged = false, commitsNotInBase = 2), merged())
    }

    @Test
    fun `a branch that the base has, by a merge or a fast-forward, is merged`(): Unit = runBlocking {
        git.run(main, "merge", "--ff-only", "agent")

        assertEquals(BranchMerged(isMerged = true, commitsNotInBase = 0), merged())
    }

    @Test
    fun `a squash-merged branch is merged, though its commits aren't in the base`(): Unit = runBlocking {
        // main moves on first, so the squash isn't a fast-forward
        File(main, "c.txt").writeText("c\n")
        git.run(main, "add", "c.txt")
        git.run(main, "commit", "-m", "Main moves on")
        git.run(main, "merge", "--squash", "agent")
        git.run(main, "commit", "-m", "Squashed agent")

        assertEquals(BranchMerged(isMerged = true, commitsNotInBase = 2), merged())

        // Until the branch gets another commit
        File(agent, "d.txt").writeText("d\n")
        git.run(agent, "add", "d.txt")
        git.run(agent, "commit", "-m", "More after the squash")

        assertEquals(BranchMerged(isMerged = false, commitsNotInBase = 3), merged())
    }

    @Test
    fun `a branch whose merge would conflict isn't merged`(): Unit = runBlocking {
        File(main, "a.txt").writeText("one\nMAIN\nthree\n")
        git.run(main, "commit", "-am", "Main changes two")

        assertEquals(BranchMerged(isMerged = false, commitsNotInBase = 2), merged())
    }

    @Test
    fun `a worktree's last change is the newest of its git files and changed files`(): Unit = runBlocking {
        val gitDir = File(git.run(agent, "rev-parse", "--absolute-git-dir").trim())
        val old = System.currentTimeMillis() - 3_600_000
        listOf("index", "HEAD", "logs/HEAD").forEach { File(gitDir, it).setLastModified(old) }

        val changed = File(agent, "b.txt")
        changed.writeText("changed\n")
        changed.setLastModified(old + 60_000)
        val untracked = File(agent, "new/file.txt")
        untracked.parentFile.mkdirs()
        untracked.writeText("new\n")
        untracked.parentFile.setLastModified(old + 120_000)

        val lastChange = GetWorktreeActivityGitAction(gitCli)(agent.path)

        // The untracked folder, which git status lists as itself
        assertEquals(Either.Ok(old + 120_000), lastChange)
    }

    @Test
    fun `a clean worktree's last change is its git files'`(): Unit = runBlocking {
        val gitDir = File(git.run(agent, "rev-parse", "--absolute-git-dir").trim())
        val time = System.currentTimeMillis() - 3_600_000
        File(gitDir, "index").setLastModified(time - 1_000)
        File(gitDir, "HEAD").setLastModified(time)
        File(gitDir, "logs/HEAD").setLastModified(time - 2_000)

        assertEquals(Either.Ok(time), GetWorktreeActivityGitAction(gitCli)(agent.path))
    }

    @Test
    fun `reads the paths that git status lists, an untracked folder as itself`() {
        val output = " M a.txt\u0000?? new/\u0000D  gone.txt\u0000A  dir/x y.txt\u0000"

        assertEquals(listOf("a.txt", "new", "gone.txt", "dir/x y.txt"), parseStatusPaths(output))
    }

    @Test
    fun `lists what prune would remove, in git's words, and removes nothing`(): Unit = runBlocking {
        val gone = File(tempDir, "gone")
        git.run(main, "worktree", "add", gone.path, "-b", "gone")
        gone.deleteRecursively()

        val prunable = GetPrunableWorktreesGitAction(gitCli)(mainGitDir)

        assertEquals(Either.Ok(listOf("Removing worktrees/gone: gitdir file points to non-existent location")), prunable)
        assertTrue(File(main, ".git/worktrees/gone").isDirectory, "the dry run left the worktree's git dir")
    }

    @Test
    fun `keeps only the lines that name a worktree, not git's warnings`() {
        val output = "\nwarning: unable to access '/x/.config/git/attributes': Permission denied\n" +
            "Removing worktrees/a1: gitdir file points to non-existent location\n  \n"

        assertEquals(listOf("Removing worktrees/a1: gitdir file points to non-existent location"), parsePruneLines(output))
    }

    @Test
    fun `lists nothing to prune when every folder exists`(): Unit = runBlocking {
        assertEquals(Either.Ok(emptyList<String>()), GetPrunableWorktreesGitAction(gitCli)(mainGitDir))
    }

    @Test
    fun `a command that git refuses fails with git's message`(): Unit = runBlocking {
        File(agent, "untracked.txt").writeText("work in progress\n")

        val result = RunGitCommandGitAction(gitCli)(main.path, listOf("worktree", "remove", "../agent"))

        val error = assertInstanceOf(GitCliError.CommandFailed::class.java, (result as Either.Err).error)
        assertEquals("git worktree remove ../agent", error.command)
        assertTrue("contains modified or untracked files" in error.stderr, error.stderr)
        assertTrue(agent.isDirectory)
    }

    @Test
    fun `a command runs in the folder it's given`(): Unit = runBlocking {
        val result = RunGitCommandGitAction(gitCli)(main.path, listOf("worktree", "remove", "../agent"))

        assertNotNull((result as? Either.Ok)?.value)
        assertTrue(!agent.exists())
    }

    private suspend fun merged(): BranchMerged? {
        val result = GetBranchMergedGitAction(gitCli)(mainGitDir, "refs/heads/main", "refs/heads/agent")

        return (result as Either.Ok).value
    }
}
