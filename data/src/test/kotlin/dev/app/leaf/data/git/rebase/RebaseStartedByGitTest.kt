// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which rebases git goes on with, from the state that git 2.54 and JGit 7.7 leave when a rebase stops. */
@DisabledOnOs(OS.WINDOWS)
class RebaseStartedByGitTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir by lazy { File(repo, ".git") }

    @BeforeEach
    fun createConflict() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        repo.mkdirs()
        git.run(repo, "init", "-b", "main")
        File(repo, "f.txt").writeText("a\n")
        git.run(repo, "add", ".")
        git.run(repo, "commit", "-m", "Initial commit")
        git.run(repo, "switch", "-c", "agent1")
        File(repo, "f.txt").writeText("b\n")
        git.run(repo, "commit", "-am", "Change f on agent1")
        git.run(repo, "switch", "main")
        File(repo, "f.txt").writeText("z\n")
        git.run(repo, "commit", "-am", "Change f on main")
        git.run(repo, "switch", "agent1")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `no rebase at all`() {
        assertFalse(isRebaseStartedByGit(gitDir))
    }

    @Test
    fun `git's rebase, with its merge backend`() {
        git.runFailing(repo, "rebase", "main")

        assertTrue(File(gitDir, "rebase-merge").isDirectory)
        assertTrue(isRebaseStartedByGit(gitDir))
    }

    @Test
    fun `git's rebase, with its apply backend`() {
        git.runFailing(repo, "-c", "rebase.backend=apply", "rebase", "main")

        assertTrue(File(gitDir, "rebase-apply").isDirectory)
        assertTrue(isRebaseStartedByGit(gitDir))
    }

    @Test
    fun `JGit's rebase`() {
        Git.open(repo).use { jgit ->
            val result = jgit.rebase().setUpstream(jgit.repository.resolve("main")).call()
            assertEquals(RebaseResult.Status.STOPPED, result.status)
        }

        assertTrue(File(gitDir, "rebase-merge").isDirectory)
        assertFalse(isRebaseStartedByGit(gitDir))
    }

    @Test
    fun `JGit's interactive rebase`() {
        Git.open(repo).use { jgit ->
            val result = jgit.rebase()
                .setUpstream(jgit.repository.resolve("main"))
                .runInteractively(object : RebaseCommand.InteractiveHandler {
                    override fun prepareSteps(steps: MutableList<RebaseTodoLine>) = Unit
                    override fun modifyCommitMessage(message: String) = message
                })
                .call()
            assertEquals(RebaseResult.Status.STOPPED, result.status)
        }

        assertTrue(File(gitDir, "rebase-merge/interactive").exists())
        assertFalse(isRebaseStartedByGit(gitDir))
    }
}
