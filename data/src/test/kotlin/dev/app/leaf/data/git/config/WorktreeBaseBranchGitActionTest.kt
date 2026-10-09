// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.worktrees.GetWorktreeBaseBranchGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.sorting.RefFolderExpansion
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The worktrees' chosen base branch, saved in the repository's Leaf file and read back with the automatic one. */
class WorktreeBaseBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val save = SaveWorktreeBaseBranchGitAction(jgit)
    private val get = GetWorktreeBaseBranchGitAction(jgit)

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val gitDir by lazy { File(repository, ".git").absolutePath }
    private val leafFile by lazy { File(gitDir, LocalConfigConstants.CONFIG_FILE_NAME) }

    private val automatic = WorktreeBaseBranch(automatic = "refs/heads/main")

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a repository without a chosen branch compares to the automatic one`(): Unit = runBlocking {
        assertEquals(Either.Ok(automatic), get(gitDir))
        assertFalse(leafFile.exists())
    }

    @Test
    fun `saves the chosen branch in the repository's Leaf file, which git config reads`(): Unit = runBlocking {
        git.run(repository, "branch", "develop")

        assertEquals(Either.Ok(Unit), save(gitDir, "refs/heads/develop"))

        val saved = git.run(repository, "config", "--file", leafFile.path, "worktrees.baseBranch").trim()
        assertEquals("refs/heads/develop", saved)
        assertEquals(Either.Ok(automatic.copy(chosen = "refs/heads/develop", chosenExists = true)), get(gitDir))
    }

    @Test
    fun `can choose a remote branch`(): Unit = runBlocking {
        git.run(repository, "update-ref", "refs/remotes/origin/main", "HEAD")

        assertEquals(Either.Ok(Unit), save(gitDir, "refs/remotes/origin/main"))

        assertEquals(Either.Ok(automatic.copy(chosen = "refs/remotes/origin/main", chosenExists = true)), get(gitDir))
    }

    @Test
    fun `going back to automatic removes the choice and keeps the rest of the file`(): Unit = runBlocking {
        val signOff = SignOffConfig(isEnabled = true, format = "Signed-off-by: %s <%s>")
        val expansion = RefFolderExpansion(expanded = setOf("local:feature"), collapsed = emptySet())

        SaveLocalRepositoryConfigGitAction(jgit)(gitDir, signOff)
        SaveRefFolderExpansionGitAction(jgit)(gitDir, expansion)
        save(gitDir, "refs/heads/main")

        assertEquals(Either.Ok(signOff), LoadSignOffConfigGitAction(jgit)(gitDir))
        assertEquals(Either.Ok(expansion), LoadRefFolderExpansionGitAction(jgit)(gitDir))

        // Saving the other settings keeps the choice
        SaveLocalRepositoryConfigGitAction(jgit)(gitDir, signOff.copy(isEnabled = false))
        SaveRefFolderExpansionGitAction(jgit)(gitDir, RefFolderExpansion())

        assertEquals(Either.Ok(automatic.copy(chosen = "refs/heads/main", chosenExists = true)), get(gitDir))

        assertEquals(Either.Ok(Unit), save(gitDir, null))

        assertEquals(Either.Ok(automatic), get(gitDir))
        assertEquals(Either.Ok(signOff.copy(isEnabled = false)), LoadSignOffConfigGitAction(jgit)(gitDir))
        // git config fails to find the key
        git.runFailing(repository, "config", "--file", leafFile.path, "--get", "worktrees.baseBranch")
        assertFalse("[worktrees]" in leafFile.readText(), leafFile.readText())
    }

    @Test
    fun `going back to automatic without a Leaf file creates none`(): Unit = runBlocking {
        assertEquals(Either.Ok(Unit), save(gitDir, null))

        assertFalse(leafFile.exists())
    }

    @Test
    fun `a chosen branch that doesn't exist falls back to the automatic one until it does`(): Unit = runBlocking {
        save(gitDir, "refs/heads/release")

        val missing = automatic.copy(chosen = "refs/heads/release", chosenExists = false)
        assertEquals(Either.Ok(missing), get(gitDir))
        assertEquals("refs/heads/main", missing.effective)

        git.run(repository, "branch", "release")

        assertEquals(Either.Ok(missing.copy(chosenExists = true)), get(gitDir))
    }

    @Test
    fun `ignores a value that isn't a local or remote branch`(): Unit = runBlocking {
        for (value in listOf("develop", "refs/tags/v1", "refs/heads/", "refs/remotes/", "HEAD")) {
            git.run(repository, "config", "--file", leafFile.path, "worktrees.baseBranch", value)

            assertEquals(Either.Ok(automatic), get(gitDir), value)
        }
    }

    @Test
    fun `refuses to choose something that isn't a local or remote branch`(): Unit = runBlocking {
        git.run(repository, "tag", "v1")

        assertInstanceOf(Either.Err::class.java, save(gitDir, "refs/tags/v1"))
        assertFalse(leafFile.exists())
    }

    @Test
    fun `an unreadable Leaf file compares to the automatic branch and is replaced on save`(): Unit = runBlocking {
        leafFile.writeText("[worktrees\n\tbaseBranch = \"\n")

        assertEquals(Either.Ok(automatic), get(gitDir))
        assertEquals(Either.Ok(Unit), save(gitDir, "refs/heads/main"))
        assertEquals(Either.Ok(automatic.copy(chosen = "refs/heads/main", chosenExists = true)), get(gitDir))
    }

    @Test
    fun `every worktree of the repository shares the choice`(): Unit = runBlocking {
        val worktree = File(tempDir, "worktree")
        git.run(repository, "worktree", "add", worktree.absolutePath, "-b", "agent")
        val worktreeGitDir = File(repository, ".git/worktrees/worktree").absolutePath

        save(worktreeGitDir, "refs/heads/agent")

        val chosen = Either.Ok(automatic.copy(chosen = "refs/heads/agent", chosenExists = true))
        assertEquals(chosen, get(gitDir))
        assertEquals(chosen, get(worktreeGitDir))
        assertFalse(File(worktreeGitDir, LocalConfigConstants.CONFIG_FILE_NAME).exists())
    }
}
