// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The commits that the other worktrees have detached, which the log starts from (fork-only). Each case runs against
 * real repositories, and what git says is the reference: `git rev-parse HEAD` in each worktree.
 */
@DisabledOnOs(OS.WINDOWS)
class GetOtherWorktreeHeadsGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit = testJGit()
    private val action by lazy {
        GetOtherWorktreeHeadsGitAction(
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
            jgit,
        )
    }

    private val main by lazy { File(tempDir, "main") }

    @BeforeEach
    fun isolateGit() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n[init]\n\tdefaultBranch = main\n")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private fun head(directory: File) = git.run(directory, "rev-parse", "HEAD").trim()

    /** A worktree of [repository] that has HEAD detached, with [commits] commits of its own. */
    private fun detachedWorktree(repository: File, name: String, commits: Int = 1): File {
        val worktree = File(tempDir, name)
        git.run(repository, "worktree", "add", "--detach", worktree.path)
        repeat(commits) { git.run(worktree, "commit", "--allow-empty", "-m", "$name work $it") }

        return worktree
    }

    private fun gitDirOf(worktree: File) = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()

    private fun heads(worktree: File): List<String> = runBlocking {
        val result = action(gitDirOf(worktree))

        assertInstanceOf(Either.Ok::class.java, result).value.let { value -> (value as List<*>).map { it as String } }
    }

    private fun readFromFiles(worktree: File): OtherWorktreeHeads = runBlocking {
        val result = jgit.provide(gitDirOf(worktree)) { git -> git.repository.otherWorktreeHeads() }

        (assertInstanceOf(Either.Ok::class.java, result).value) as OtherWorktreeHeads
    }

    @Test
    fun `finds nothing in a repository without linked worktrees`() {
        git.initRepository(main)

        assertEquals(emptyList<String>(), heads(main))
    }

    @Test
    fun `finds the commit of a detached worktree, from the main worktree and from another linked one`() {
        git.initRepository(main)
        val agent1 = detachedWorktree(main, "agent1")
        val agent2 = detachedWorktree(main, "agent2", commits = 2)

        // git's own answer, and the commits are the worktrees' own
        assertNotEquals(head(main), head(agent1))

        assertEquals(listOf(head(agent1), head(agent2)), heads(main))
        // Not its own: the tab of agent1 shows that as the branch HEAD
        assertEquals(listOf(head(agent2)), heads(agent1))
        assertEquals(listOf(head(agent1)), heads(agent2))
    }

    @Test
    fun `finds the main worktree's detached HEAD from a linked worktree's tab`() {
        git.initRepository(main)
        git.run(main, "checkout", "--detach")
        git.run(main, "commit", "--allow-empty", "-m", "Detached in main")
        val feature = File(tempDir, "feature")
        // On the first commit, which the detached HEAD has moved on from
        git.run(main, "worktree", "add", "-b", "feature", feature.path, "main")

        assertNotEquals(head(main), head(feature))
        assertEquals(listOf(head(main)), heads(feature))
        assertEquals(emptyList<String>(), heads(main))
    }

    @Test
    fun `a worktree on a branch adds nothing, its tip is the branch's`() {
        git.initRepository(main)
        val feature = File(tempDir, "feature")
        git.run(main, "worktree", "add", "-b", "feature", feature.path)
        git.run(feature, "commit", "--allow-empty", "-m", "Feature work")

        assertEquals(emptyList<String>(), heads(main))
        assertEquals(emptyList<String>(), heads(feature))
    }

    @Test
    fun `a worktree on a branch with no commit yet adds nothing`() {
        git.initRepository(main)
        val orphan = File(tempDir, "orphan")
        git.run(main, "worktree", "add", "--orphan", "-b", "fresh", orphan.path)

        assertEquals(emptyList<String>(), heads(main))
    }

    @Test
    fun `two worktrees at one commit give it once`() {
        git.initRepository(main)
        val agent1 = detachedWorktree(main, "agent1")
        val agent2 = File(tempDir, "agent2")
        git.run(main, "worktree", "add", "--detach", agent2.path, head(agent1))

        assertEquals(listOf(head(agent1)), heads(main))
    }

    @Test
    fun `a rebase that stopped at a conflict has a detached HEAD, and counts`() {
        git.initRepository(main)
        File(main, "a.txt").writeText("base\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Add a")
        val agent = File(tempDir, "agent")
        git.run(main, "worktree", "add", "-b", "feature", agent.path)
        // The first commit applies, the second conflicts, so HEAD is at a copy of the first that no ref has
        File(agent, "b.txt").writeText("b\n")
        git.run(agent, "add", ".")
        git.run(agent, "commit", "-m", "Feature adds b")
        File(agent, "a.txt").writeText("feature\n")
        git.run(agent, "commit", "-am", "Feature changes a")
        File(main, "a.txt").writeText("main\n")
        git.run(main, "commit", "-am", "Main changes a")
        git.runFailing(agent, "rebase", "main")

        val during = head(agent)

        assertTrue(File(gitDirOf(agent), "rebase-merge").exists(), "the rebase is stopped")
        assertEquals("", git.run(main, "for-each-ref", "--contains", during, "refs/heads"))
        assertEquals(listOf(during), heads(main))
    }

    @Test
    fun `a bisect has a detached HEAD, and counts`() {
        git.initRepository(main)
        val agent = detachedWorktree(main, "agent", commits = 4)
        val first = git.run(agent, "rev-parse", "HEAD~3").trim()
        git.run(agent, "bisect", "start", "HEAD", first)

        val midway = head(agent)

        assertNotEquals(first, midway)
        assertTrue(File(gitDirOf(agent), "BISECT_LOG").exists(), "the bisect is going")
        assertEquals(listOf(midway), heads(main))
    }

    @Test
    fun `leaves out a worktree whose folder is missing, locked or not, and the main worktree stays right`() {
        git.initRepository(main)
        val gone = detachedWorktree(main, "gone")
        val lockedGone = detachedWorktree(main, "locked-gone")
        val kept = detachedWorktree(main, "kept")
        git.run(main, "worktree", "lock", lockedGone.path)
        FileUtils.delete(gone, FileUtils.RECURSIVE)
        FileUtils.delete(lockedGone, FileUtils.RECURSIVE)

        // git calls only the first prunable, as a lock keeps its worktree
        val listed = parseWorktreeList(git.run(main, "worktree", "list", "--porcelain", "-z"))
            .associateBy { File(it.path).name }
        assertTrue(listed.getValue("gone").prunable != null)
        assertEquals(null, listed.getValue("locked-gone").prunable)
        assertTrue(listed.getValue("locked-gone").locked != null)

        assertEquals(listOf(head(kept)), heads(main))
    }

    @Test
    fun `leaves out a worktree whose folder is there but whose git file is gone, which git calls prunable`() {
        git.initRepository(main)
        val broken = detachedWorktree(main, "broken")
        val kept = detachedWorktree(main, "kept")
        File(broken, ".git").delete()

        val listed = parseWorktreeList(git.run(main, "worktree", "list", "--porcelain", "-z"))
            .associateBy { File(it.path).name }
        assertTrue(File(broken, "README.md").exists(), "the folder is there")
        assertTrue(listed.getValue("broken").prunable != null, "git says prunable")

        assertEquals(listOf(head(kept)), heads(main))
    }

    @Test
    fun `leaves out a HEAD file that can't be read or whose commit the repository lacks`() {
        git.initRepository(main)
        val broken = detachedWorktree(main, "broken")
        val noCommit = detachedWorktree(main, "no-commit")
        val kept = detachedWorktree(main, "kept")
        File(gitDirOf(broken), "HEAD").writeText("not a commit\n")
        File(gitDirOf(noCommit), "HEAD").writeText("${"1".repeat(40)}\n")

        assertEquals(listOf(head(kept)), heads(main))
    }

    @Test
    fun `leaves out a worktree git doesn't know, that has no gitdir file`() {
        git.initRepository(main)
        val odd = detachedWorktree(main, "odd")
        val kept = detachedWorktree(main, "kept")
        File(gitDirOf(odd), "gitdir").delete()

        assertEquals(listOf(head(kept)), heads(main))
    }

    @Test
    fun `finds the detached worktrees of a bare repository, and not the bare repository's own HEAD`() {
        git.initRepository(main)
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "--bare", main.path, bare.path)
        val agent = detachedWorktree(bare, "agent")

        assertEquals(listOf(head(agent)), heads(bare))
        // From the linked worktree, the bare repository's HEAD is no worktree's
        assertEquals(emptyList<String>(), heads(agent))
    }

    @Test
    fun `reads twenty worktrees' HEADs, each commit once`() {
        git.initRepository(main)
        val agents = (1..20).map { detachedWorktree(main, "agent$it") }

        // In the order of the worktrees' git dir names, as git lists them
        val found = heads(main)

        assertEquals(20, found.size)
        assertEquals(agents.map(::head).toSet(), found.toSet())
        assertEquals(false, readFromFiles(main).needsGit, "the files were enough")
    }

    @Test
    fun `a repository with files in each HEAD doesn't need git`() {
        git.initRepository(main)
        detachedWorktree(main, "agent")

        assertEquals(false, readFromFiles(main).needsGit)
    }

    // A reftable repository has no commit in a HEAD file, so git is asked: probed with git 2.54 (2026-10-20), where the
    // main worktree's and a linked one's HEAD files are both "ref: refs/heads/.invalid"

    private fun reftableRepository(): File {
        main.mkdirs()
        val initialized = runCatching { git.run(main, "init", "--ref-format=reftable") }
        assumeTrue(initialized.isSuccess) { "git before 2.45 has no reftable" }
        File(main, "README.md").writeText("Test repository\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")

        return main
    }

    @Test
    fun `in a reftable repository the files are stubs, and git is asked`() {
        reftableRepository()
        val agent = detachedWorktree(main, "agent")

        assertEquals("ref: refs/heads/.invalid", File(gitDirOf(agent), "HEAD").readText().trim())
        assertEquals(OtherWorktreeHeads(emptyList(), needsGit = true), readFromFiles(main))
        assertEquals(listOf(head(agent)), heads(main))
    }

    @Test
    fun `in a reftable repository git's list is filtered like the files are`() {
        reftableRepository()
        val agent1 = detachedWorktree(main, "agent1")
        val agent2 = detachedWorktree(main, "agent2")
        val gone = detachedWorktree(main, "gone")
        val feature = File(tempDir, "feature")
        git.run(main, "worktree", "add", "-b", "feature", feature.path)
        git.run(feature, "commit", "--allow-empty", "-m", "Feature work")
        val same = File(tempDir, "same")
        git.run(main, "worktree", "add", "--detach", same.path, head(agent1))
        FileUtils.delete(gone, FileUtils.RECURSIVE)

        // Not the branch's worktree or the missing one, and a commit once
        assertEquals(listOf(head(agent1), head(agent2)), heads(main))
        assertEquals(listOf(head(agent1), head(agent2)), heads(feature))
        // Not the tab's own worktree, whose commit another worktree has too
        assertEquals(listOf(head(agent2), head(agent1)), heads(agent1))
    }

    @Test
    fun `in a reftable repository git's prunable worktrees are left out, whose folder is there`() {
        reftableRepository()
        val broken = detachedWorktree(main, "broken")
        val kept = detachedWorktree(main, "kept")
        File(broken, ".git").delete()

        assertTrue(File(broken, "README.md").exists(), "the folder is there")
        assertEquals(listOf(head(kept)), heads(main))
    }

    @Test
    fun `a bare reftable repository's tab has no folder of its own to leave out`() {
        reftableRepository()
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "--bare", "--ref-format=reftable", main.path, bare.path)
        val agent = detachedWorktree(bare, "agent")

        assertEquals(listOf(head(agent)), heads(bare))
    }
}
