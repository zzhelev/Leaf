// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.sorting.RefFolderExpansion
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.inject.Provider

class RefFolderExpansionGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = JGit(Provider { error("Only used on Windows") })
    private val load = LoadRefFolderExpansionGitAction(jgit)
    private val save = SaveRefFolderExpansionGitAction(jgit)

    private val expansion = RefFolderExpansion(
        expanded = setOf("local:bugfix", "remote:origin/feature", "tags:qa"),
        collapsed = setOf("local:feature"),
    )

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a repository without saved folders loads the defaults`(): Unit = runBlocking {
        val gitDir = File(git.initRepository(File(tempDir, "repo")), ".git")

        assertEquals(Either.Ok(RefFolderExpansion()), load(gitDir.absolutePath))
    }

    @Test
    fun `saves and loads the folder state in the repository's Leaf file`(): Unit = runBlocking {
        val gitDir = File(git.initRepository(File(tempDir, "repo")), ".git")

        assertEquals(Either.Ok(Unit), save(gitDir.absolutePath, expansion))
        assertEquals(Either.Ok(expansion), load(gitDir.absolutePath))

        assertEquals(Either.Ok(Unit), save(gitDir.absolutePath, RefFolderExpansion()))
        assertEquals(Either.Ok(RefFolderExpansion()), load(gitDir.absolutePath))
    }

    @Test
    fun `keeps sign-off settings and folder state when either is saved`(): Unit = runBlocking {
        val gitDir = File(git.initRepository(File(tempDir, "repo")), ".git").absolutePath
        val signOff = SignOffConfig(isEnabled = true, format = "Signed-off-by: %s <%s>")

        SaveLocalRepositoryConfigGitAction(jgit)(gitDir, signOff)
        save(gitDir, expansion)

        assertEquals(Either.Ok(signOff), LoadSignOffConfigGitAction(jgit)(gitDir))

        SaveLocalRepositoryConfigGitAction(jgit)(gitDir, signOff.copy(isEnabled = false))

        assertEquals(Either.Ok(expansion), load(gitDir))
    }

    @Test
    fun `an unreadable Leaf file loads as the defaults and is replaced on save`(): Unit = runBlocking {
        val gitDir = File(git.initRepository(File(tempDir, "repo")), ".git")
        File(gitDir, LocalConfigConstants.CONFIG_FILE_NAME).writeText("[sidePanel\n\tbroken = \"\n")

        assertEquals(Either.Ok(RefFolderExpansion()), load(gitDir.absolutePath))
        assertEquals(Either.Ok(Unit), save(gitDir.absolutePath, expansion))
        assertEquals(Either.Ok(expansion), load(gitDir.absolutePath))

        File(gitDir, LocalConfigConstants.CONFIG_FILE_NAME).writeText("[sidePanel\n")
        val signOff = SignOffConfig(isEnabled = true, format = "Signed-off-by: %s <%s>")

        assertEquals(Either.Ok(Unit), SaveLocalRepositoryConfigGitAction(jgit)(gitDir.absolutePath, signOff))
        assertEquals(Either.Ok(signOff), LoadSignOffConfigGitAction(jgit)(gitDir.absolutePath))
    }

    @Test
    fun `linked worktrees share the folder state of their repository`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "worktree")
        git.run(repository, "worktree", "add", worktree.absolutePath, "-b", "agent")
        val worktreeGitDir = File(repository, ".git/worktrees/worktree").absolutePath

        save(worktreeGitDir, expansion)

        assertEquals(Either.Ok(expansion), load(File(repository, ".git").absolutePath))
        assertEquals(Either.Ok(expansion), load(worktreeGitDir))
    }
}
