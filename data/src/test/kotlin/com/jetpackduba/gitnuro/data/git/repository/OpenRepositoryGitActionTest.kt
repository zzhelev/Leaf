package com.jetpackduba.gitnuro.data.git.repository

import com.jetpackduba.gitnuro.data.git.IsolatedSystemReader
import com.jetpackduba.gitnuro.data.git.TestGitCli
import com.jetpackduba.gitnuro.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenRepositoryGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val openRepositoryGitAction = OpenRepositoryGitAction()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `opens a regular repository`() {
        val repository = git.initRepository(File(tempDir, "repo"))

        assertOpens(repository, expectedGitDir = File(repository, ".git"), expectedBranch = "main")
    }

    @Test
    fun `opens a linked worktree next to the main worktree`() {
        val main = git.initRepository(File(tempDir, "main"))
        val worktree = File(tempDir, "main-feature")
        git.run(main, "worktree", "add", worktree.absolutePath, "-b", "feature")

        assertOpens(worktree, expectedGitDir = File(main, ".git/worktrees/main-feature"), expectedBranch = "feature")
    }

    @Test
    fun `opens a linked worktree nested in the main worktree`() {
        val main = git.initRepository(File(tempDir, "main"))
        val worktree = File(main, ".claude/worktrees/agent")
        git.run(main, "worktree", "add", worktree.absolutePath, "-b", "agent")

        assertOpens(worktree, expectedGitDir = File(main, ".git/worktrees/agent"), expectedBranch = "agent")
    }

    @Test
    fun `opens a linked worktree whose git file uses a relative path`() {
        val main = git.initRepository(File(tempDir, "main"))
        val worktree = File(tempDir, "main-feature")
        git.run(main, "worktree", "add", worktree.absolutePath, "-b", "feature")
        File(worktree, ".git").writeText("gitdir: ../main/.git/worktrees/main-feature\n")

        assertOpens(worktree, expectedGitDir = File(main, ".git/worktrees/main-feature"), expectedBranch = "feature")
    }

    @Test
    fun `opens a submodule`() {
        val library = git.initRepository(File(tempDir, "library"))
        val superproject = git.initRepository(File(tempDir, "superproject"))
        git.run(superproject, "submodule", "add", library.absolutePath, "lib")

        assertOpens(
            File(superproject, "lib"),
            expectedGitDir = File(superproject, ".git/modules/lib"),
            expectedBranch = "main",
        )
    }

    @Test
    fun `returns an error when the folder is not a repository`(): Unit = runBlocking {
        val folder = File(tempDir, "plain").apply { mkdirs() }

        val result = openRepositoryGitAction(folder.absolutePath)

        assertInstanceOf(Either.Err::class.java, result)
    }

    @Test
    fun `returns an error instead of throwing when the git file points to a missing directory`(): Unit = runBlocking {
        val folder = File(tempDir, "broken").apply { mkdirs() }
        File(folder, ".git").writeText("gitdir: ${File(tempDir, "missing").absolutePath}\n")

        val result = openRepositoryGitAction(folder.absolutePath)

        assertInstanceOf(Either.Err::class.java, result)
    }

    private fun assertOpens(directory: File, expectedGitDir: File, expectedBranch: String) = runBlocking {
        val result = openRepositoryGitAction(directory.absolutePath)

        assertInstanceOf(Either.Ok::class.java, result, "Expected $directory to open, got $result")
        val repositoryPath = (result as Either.Ok).value

        // Tabs reopen the repository from the returned git dir, see JGit.provide
        Git.open(File(repositoryPath)).use { git ->
            assertEquals(expectedGitDir.canonicalFile, git.repository.directory.canonicalFile)
            assertEquals(directory.canonicalFile, git.repository.workTree.canonicalFile)
            assertEquals(expectedBranch, git.repository.branch)
        }
    }
}
