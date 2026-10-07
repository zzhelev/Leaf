// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.services

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.repository.OpenRepositoryGitAction
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.inject.Provider

class GitProviderServiceTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = JGit(Provider { error("Only used on Windows") })
    private val gitProviderService = GitProviderService(jgit)
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
    fun `keeps the repositories of open tabs, linked worktrees and submodules included`(): Unit = runBlocking {
        val main = git.initRepository(File(tempDir, "main"))
        val worktree = File(tempDir, "main-feature")
        git.run(main, "worktree", "add", worktree.absolutePath, "-b", "feature")
        val library = git.initRepository(File(tempDir, "library"))
        git.run(main, "submodule", "add", library.absolutePath, "lib")

        // None of these git dirs is the working tree + "/.git"
        val openPaths = listOf(open(main), open(worktree), open(File(main, "lib")))
        val cachedGits = openPaths.map { cachedGit(it) }

        gitProviderService.cleanupExcept(openPaths.toSet())

        openPaths.zip(cachedGits).forEach { (path, gitBefore) ->
            assertSame(gitBefore, cachedGit(path), path)
        }
    }

    @Test
    fun `drops the repositories that no tab has open`(): Unit = runBlocking {
        val kept = open(git.initRepository(File(tempDir, "kept")))
        val dropped = open(git.initRepository(File(tempDir, "dropped")))
        val keptGit = cachedGit(kept)
        val droppedGit = cachedGit(dropped)

        gitProviderService.cleanupExcept(setOf(kept))

        assertSame(keptGit, cachedGit(kept))
        assertNotSame(droppedGit, cachedGit(dropped))
    }

    /** Returns the path a tab keeps for [directory] (`RepositorySelectionState.Open.path`). */
    private suspend fun open(directory: File): String =
        (openRepositoryGitAction(directory.absolutePath) as Either.Ok).value

    private suspend fun cachedGit(repositoryPath: String): Git =
        (jgit.provide(repositoryPath) { it } as Either.Ok).value
}
