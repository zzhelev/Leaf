// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.lfs.DismissGitLfsNoticeGitAction
import dev.app.leaf.data.git.lfs.GitLfsSetup
import dev.app.leaf.data.git.lfs.SetUpGitLfsGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.lfs.GitLfsNotice
import dev.app.leaf.domain.usecases.DismissGitLfsNoticeUseCase
import dev.app.leaf.domain.usecases.SetUpGitLfsUseCase
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab's strip about git outside Leaf (fork-only): the refresh reads the notice, × hides it, and Set up reads it
 * again, after git-lfs refused to replace a hook too, as the filter is written by then. A fake `git-lfs` stands in for
 * it.
 */
@DisabledOnOs(OS.WINDOWS)
class GitLfsNoticeInTabTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "global.gitconfig").apply { createNewFile() } }
    private val git by lazy { TestGitCli(File(tempDir, "setup.gitconfig")) }
    private val repository by lazy { File(tempDir, "repo") }

    /** Makes the fake `git lfs install` write the filter, then fail as a hook in the way makes git-lfs fail. */
    private val hookInTheWay by lazy { File(tempDir, "hook-in-the-way") }

    private val gitCli by lazy {
        val bin = File(tempDir, "bin")
        File(bin, "git-lfs").writeExecutable(
            """
            #!/bin/sh
            case "${'$'}1" in
                version) printf 'git-lfs/3.8.0 (Fake)\n' ;;
                install)
                    git config --global filter.lfs.process 'git-lfs filter-process'
                    if [ -f '${hookInTheWay.path}' ]; then
                        printf 'Hook already exists: pre-push\n' >&2
                        exit 2
                    fi
                    ;;
            esac
            """.trimIndent() + "\n"
        )

        testGitCli(
            mapOf(
                "PATH" to "${bin.absolutePath}:/usr/bin:/bin",
                "HOME" to tempDir.path,
                "GIT_CONFIG_GLOBAL" to globalConfig.path,
                "GIT_CONFIG_NOSYSTEM" to "1",
            )
        )
    }

    private val jgit = testJGit()
    private val tab by lazy { TestTab(jgit, gitCli) }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        git.run(tempDir, "init", "-q", "-b", "main", repository.path)
        File(repository, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        git.run(repository, "add", ".gitattributes")
        git.run(repository, "commit", "-q", "-m", "Use LFS")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the tab reads the notice, and × hides it`(): Unit = runBlocking {
        tab.openRepository(repository.path)

        assertEquals(GitLfsNotice.NOT_SET_UP, tab.awaitLoaded(tab.repositoryData.gitLfsNotice) { it != null })

        DismissGitLfsNoticeUseCase(tab.useCaseExecutor, DismissGitLfsNoticeGitAction(jgit))(GitLfsNotice.NOT_SET_UP)

        assertNull(tab.awaitLoaded(tab.repositoryData.gitLfsNotice) { it == null })
    }

    @Test
    fun `Set up hides it`(): Unit = runBlocking {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.gitLfsNotice) { it != null }

        setUpGitLfs().join()

        assertNull(tab.awaitLoaded(tab.repositoryData.gitLfsNotice) { it == null })
    }

    @Test
    fun `Set up that git-lfs fails after writing the filter hides it too`(): Unit = runBlocking {
        hookInTheWay.createNewFile()
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.gitLfsNotice) { it != null }

        setUpGitLfs().join()

        assertNull(tab.awaitLoaded(tab.repositoryData.gitLfsNotice) { it == null })
    }

    private fun setUpGitLfs() = SetUpGitLfsUseCase(
        tab.useCaseExecutor,
        SetUpGitLfsGitAction(jgit, gitCli, GitLfsSetup(gitCli)),
    )()
}
