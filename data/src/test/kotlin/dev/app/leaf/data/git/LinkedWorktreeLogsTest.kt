// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A linked worktree's HEAD reflog stays in its own git dir ([LinkedWorktreeLogs]), for repositories that Leaf's [JGit]
 * opens. JGit 7.7 alone writes it to the main worktree's `logs/HEAD`, which `docs/fork/probes/worktree-reflog-probe.sh`
 * shows. The git CLI is the reference: what `git reflog` and `@{-1}` show in each worktree.
 */
@DisabledOnOs(OS.WINDOWS)
class LinkedWorktreeLogsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()

    /** main, plus `feature` and `other` with one commit each. The main worktree last came from `other` to `main`. */
    private val repository by lazy {
        git.initRepository(File(tempDir, "repo")).also { repository ->
            commitFile(repository, "feature", "f.txt")
            commitFile(repository, "other", "o.txt")
            git.run(repository, "checkout", "main")
        }
    }
    private val mainLog get() = File(repository, ".git/logs/HEAD")

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private fun commitFile(repository: File, branch: String, file: String) {
        git.run(repository, "checkout", "-b", branch, "main")
        File(repository, file).writeText("$branch\n")
        git.run(repository, "add", file)
        git.run(repository, "commit", "-m", "Add $file")
    }

    /** Adds a linked worktree on a new branch [name] at [path] and returns its git dir, as a Leaf tab opens it. */
    private fun addWorktree(path: File, name: String = path.name, repository: File = this.repository): File {
        git.run(repository, "worktree", "add", "-b", name, path.absolutePath)

        val gitDir = File(git.run(path, "rev-parse", "--absolute-git-dir").trim())
        check(File(gitDir, Constants.COMMONDIR_FILE).isFile) { "$gitDir is not a linked worktree's git dir" }

        return gitDir
    }

    private fun withJGit(gitDir: File, block: (Git) -> Unit) = runBlocking {
        val result = jgit.provide(gitDir.absolutePath) { git -> block(git) }

        check(result is Either.Ok) { "The JGit operation failed: $result" }
    }

    /** The messages of the HEAD reflog in [worktree], newest first, as git shows them. */
    private fun gitReflog(worktree: File): List<String> =
        git.run(worktree, "reflog", "--format=%gs").lines().filter { it.isNotEmpty() }

    private fun previousBranch(worktree: File): String = git.run(worktree, "rev-parse", "--abbrev-ref", "@{-1}").trim()

    @Test
    fun `every operation that moves HEAD in a linked worktree is logged in its own HEAD reflog`() {
        val worktree = File(tempDir, "agent")
        val gitDir = addWorktree(worktree)
        val mainLogBefore = mainLog.readText()
        val reflogBefore = gitReflog(worktree)

        withJGit(gitDir) { git ->
            val repository = git.repository
            git.commit().setMessage("Work").setAllowEmpty(true).setSign(false).call()
            git.commit().setMessage("Work, amended").setAllowEmpty(true).setAmend(true).setSign(false).call()
            git.checkout().setCreateBranch(true).setName("topic").call()
            git.checkout().setName("agent").call()
            git.checkout().setName(repository.resolve("agent~1").name).call()
            git.checkout().setName("agent").call()
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(repository.resolve("agent~1").name).call()
            git.merge().include(repository.resolve("feature")).setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setMessage("Merge feature").call()
            git.cherryPick().include(repository.resolve("other")).call()
            git.revert().include(repository.resolve("HEAD")).call()
            git.checkout().setName("topic").call()
            git.rebase().setUpstream("feature").setOperation(RebaseCommand.Operation.BEGIN).call()
            git.branchRename().setOldName("topic").setNewName("topic2").call()
        }

        assertEquals(mainLogBefore, mainLog.readText(), "The main worktree's HEAD reflog is unchanged")

        val added = gitReflog(worktree).dropLast(reflogBefore.size).reversed()
        val expected = listOf(
            "commit: Work",
            "commit (amend): Work, amended",
            "checkout: moving from agent to topic",
            "checkout: moving from topic to agent",
            "checkout: moving from agent to ",
            "checkout: moving from ",
            ": updating HEAD",
            "merge ",
            "cherry-pick: Add o.txt",
            "revert: Revert \"Add o.txt\"",
            "checkout: moving from agent to topic",
            "checkout: moving from topic to ",
            "rebase finished: returning to refs/heads/topic",
            "Branch: renamed topic to topic2",
        )
        assertEquals(expected.size, added.size, "New entries: $added")
        for ((entry, part) in added.zip(expected)) {
            assertTrue(part in entry, "'$entry' should contain '$part'")
        }
    }

    @Test
    fun `checkouts in a linked worktree set its own previous branch, not the main worktree's`() {
        val worktree = File(tempDir, "agent")
        val gitDir = addWorktree(worktree)

        withJGit(gitDir) { git ->
            git.checkout().setCreateBranch(true).setName("topic").call()
            git.checkout().setName("agent").call()
        }

        assertEquals("topic", previousBranch(worktree))
        assertEquals("other", previousBranch(repository), "The main worktree's own previous branch")
    }

    @Test
    fun `JGit reads back the entries it wrote in a linked worktree`() {
        val gitDir = addWorktree(File(tempDir, "agent"))

        withJGit(gitDir) { git ->
            git.commit().setMessage("Work").setAllowEmpty(true).setSign(false).call()

            val entries = git.repository.refDatabase.getReflogReader(Constants.HEAD)!!.reverseEntries
            assertEquals("commit: Work", entries.first().comment)
        }
    }

    @Test
    fun `the branch's own reflog stays in the common git dir`() {
        val gitDir = addWorktree(File(tempDir, "agent"))

        withJGit(gitDir) { git -> git.commit().setMessage("Work").setAllowEmpty(true).setSign(false).call() }

        val branchLog = File(repository, ".git/logs/refs/heads/agent")
        assertEquals("commit: Work", branchLog.readLines().last().substringAfter('\t'))
        assertFalse(File(gitDir, "logs/refs").exists())
    }

    @Test
    fun `the main worktree's operations still go to the main worktree's HEAD reflog`() {
        val gitDir = addWorktree(File(tempDir, "agent"))
        val worktreeLogBefore = File(gitDir, "logs/HEAD").readText()

        withJGit(File(repository, ".git")) { git ->
            git.commit().setMessage("Main work").setAllowEmpty(true).setSign(false).call()
        }

        assertEquals("commit: Main work", gitReflog(repository).first())
        assertEquals(worktreeLogBefore, File(gitDir, "logs/HEAD").readText())
    }

    @Test
    fun `a worktree nested in the main working tree keeps its own HEAD reflog`() {
        val worktree = File(repository, ".claude/worktrees/agent-1")
        val gitDir = addWorktree(worktree)
        val mainLogBefore = mainLog.readText()

        withJGit(gitDir) { git -> git.commit().setMessage("Nested work").setAllowEmpty(true).setSign(false).call() }

        assertEquals("commit: Nested work", gitReflog(worktree).first())
        assertEquals(mainLogBefore, mainLog.readText())
    }

    @Test
    fun `a linked worktree of a bare repository keeps its own HEAD reflog`() {
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "--bare", repository.absolutePath, bare.absolutePath)
        val worktree = File(tempDir, "bare-agent")
        val gitDir = addWorktree(worktree, "agent", repository = bare)
        val bareLog = File(bare, "logs/HEAD")
        val bareLogBefore = bareLog.takeIf { it.exists() }?.readText()

        withJGit(gitDir) { git -> git.commit().setMessage("Work").setAllowEmpty(true).setSign(false).call() }

        assertEquals("commit: Work", gitReflog(worktree).first())
        assertEquals(bareLogBefore, bareLog.takeIf { it.exists() }?.readText(), "The bare repository's HEAD reflog")
    }

    @Test
    fun `only the common git dir's logs folder is redirected`() {
        val gitDir = addWorktree(File(tempDir, "agent"))
        val commonDir = File(repository, ".git")
        val logs = LinkedWorktreeLogs.of(gitDir)!!

        assertEquals(File(gitDir, "logs").canonicalFile, logs.resolve(commonDir, "logs")?.canonicalFile)
        // JGit reads the common git dir from the commondir file, "../..", as a canonical path
        assertEquals(
            File(gitDir, "logs").canonicalFile,
            logs.resolve(File(gitDir, "../.."), "logs")?.canonicalFile,
        )
        assertNull(logs.resolve(commonDir, "logs/refs/"), "Branch logs are shared")
        assertNull(logs.resolve(commonDir, "objects"))
        assertNull(logs.resolve(commonDir, "config"))
        assertNull(logs.resolve(gitDir, "logs"))
        assertNull(logs.resolve(null, "logs"))
    }

    @Test
    fun `other git dirs are left alone`() {
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "--bare", repository.absolutePath, bare.absolutePath)

        assertNull(LinkedWorktreeLogs.of(File(repository, ".git")), "The main worktree")
        assertNull(LinkedWorktreeLogs.of(bare), "A bare repository")
    }

    @Test
    fun `the file systems keep the redirect in their copies`() {
        val gitDir = addWorktree(File(tempDir, "agent"))
        val commonDir = File(repository, ".git")
        val logs = LinkedWorktreeLogs.of(gitDir)
        val expected = File(gitDir, "logs").canonicalFile
        val posixFs = PosixFs(LoginShellEnvironment { emptyMap() }, logs)
        val windowsFs = WindowsFs { null }

        assertEquals(expected, posixFs.resolve(commonDir, "logs").canonicalFile)
        assertEquals(expected, posixFs.newInstance().resolve(commonDir, "logs").canonicalFile)
        assertEquals(expected, windowsFs.withLinkedWorktreeLogs(logs).resolve(commonDir, "logs").canonicalFile)
        val windowsCopy = windowsFs.withLinkedWorktreeLogs(logs).newInstance()
        assertEquals(expected, windowsCopy.resolve(commonDir, "logs").canonicalFile)
        assertSame(windowsFs, windowsFs.withLinkedWorktreeLogs(null))
        assertEquals(File(commonDir, "logs"), windowsFs.resolve(commonDir, "logs"))
    }

    @Test
    fun `a commit through WindowsFs in a linked worktree is logged in its own HEAD reflog`() {
        val worktree = File(tempDir, "agent")
        val gitDir = addWorktree(worktree)
        val mainLogBefore = mainLog.readText()
        val fs = WindowsFs { null }.withLinkedWorktreeLogs(LinkedWorktreeLogs.of(gitDir))

        Git.open(gitDir, fs).use { git -> git.commit().setMessage("Work").setAllowEmpty(true).setSign(false).call() }

        assertEquals("commit: Work", gitReflog(worktree).first())
        assertEquals(mainLogBefore, mainLog.readText())
    }
}
