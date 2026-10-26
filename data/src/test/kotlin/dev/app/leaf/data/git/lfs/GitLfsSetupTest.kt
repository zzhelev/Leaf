// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.cli.remote.pathWithoutGitLfs
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
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

/**
 * When JGit runs git-lfs, and when Leaf's built-in LFS filters do its LFS work instead: git-lfs only when the config
 * names its filters and `git lfs version` works. A fake `git-lfs` stands in for it, so that the test runs the same
 * with git-lfs installed or not.
 */
@DisabledOnOs(OS.WINDOWS)
class GitLfsSetupTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    private val gitLfsRuns by lazy { File(tempDir, "git-lfs-runs.log") }

    /** A PATH whose `git-lfs` works, and writes each run to [gitLfsRuns]. */
    private val pathWithGitLfs by lazy {
        val bin = File(tempDir, "bin-with-git-lfs")
        File(bin, "git-lfs").writeExecutable(
            "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '${gitLfsRuns.path}'\nprintf 'git-lfs/3.8.0 (Fake)\\n'\n"
        )

        "${bin.absolutePath}:/usr/bin:/bin"
    }

    private val pathWithoutGitLfs by lazy { pathWithoutGitLfs(File(tempDir, "bin-without-git-lfs")) }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `JGit runs git-lfs when the config names its filters and git-lfs works`(): Unit = runBlocking {
        withRepository(gitLfsInstalled = true) { repository ->
            assertTrue(setup(pathWithGitLfs).jgitRunsGitLfs(repository))
        }
    }

    @Test
    fun `Leaf's filters run when the config is left behind and git-lfs doesn't work`(): Unit = runBlocking {
        withRepository(gitLfsInstalled = true) { repository ->
            assertFalse(setup(pathWithoutGitLfs).jgitRunsGitLfs(repository))
        }
    }

    @Test
    fun `Leaf's filters run when git-lfs works but was never set up`(): Unit = runBlocking {
        withRepository(gitLfsInstalled = false) { repository ->
            assertFalse(setup(pathWithGitLfs).jgitRunsGitLfs(repository))
        }
    }

    @Test
    fun `Leaf's filters run when the config names only one of smudge and clean, or a blank one`(): Unit = runBlocking {
        withRepository(gitLfsInstalled = true) { repository ->
            val setup = setup(pathWithGitLfs)

            setFilter(repository, "clean", null)
            assertFalse(setup.jgitRunsGitLfs(repository))

            setFilter(repository, "clean", "git-lfs clean -- %f")
            setFilter(repository, "smudge", " ")
            assertFalse(setup.jgitRunsGitLfs(repository))

            setFilter(repository, "smudge", "git-lfs smudge -- %f")
            assertTrue(setup.jgitRunsGitLfs(repository))
        }
    }

    @Test
    fun `git lfs version is asked again only after a minute`(): Unit = runBlocking {
        var now = 1_000_000L
        val setup = GitLfsSetup(gitCli(pathWithGitLfs), now = { now })

        assertTrue(setup.gitLfsWorks(tempDir))
        assertTrue(setup.gitLfsWorks(tempDir))
        now += 59_000
        assertTrue(setup.gitLfsWorks(tempDir))
        assertEquals(1, versionRuns())

        now += 2_000
        assertTrue(setup.gitLfsWorks(tempDir))
        assertEquals(2, versionRuns())
    }

    @Test
    fun `JGit's repositories get Leaf's filters, in memory, unless git-lfs is set up`(): Unit = runBlocking {
        val repo = File(tempDir, "repo")
        Git.init().setDirectory(repo).setInitialBranch("main").call().close()
        val gitDir = File(repo, ".git").path

        fun jgitFor(path: String) = testJGit(
            includedConfig = GitIncludedConfig(gitCli(path), LoginShellEnvironment { emptyMap() }),
            gitLfsSetup = setup(path),
        )

        val withoutGitLfs = jgitFor(pathWithoutGitLfs)
        assertTrue(usesBuiltinLfs(withoutGitLfs, gitDir))

        val withGitLfs = jgitFor(pathWithGitLfs)
        // Installed, but not set up for the repository: git doesn't know the filter
        assertTrue(usesBuiltinLfs(withGitLfs, gitDir))

        Git.open(repo).use { setFilters(it.repository) }
        assertFalse(usesBuiltinLfs(withGitLfs, gitDir))
        assertTrue(usesBuiltinLfs(withoutGitLfs, gitDir))

        assertFalse(File(gitDir, "config").readText().contains("usejgitbuiltin", ignoreCase = true))
    }

    private fun setup(path: String) = GitLfsSetup(gitCli(path))

    /** git with [path], kept away from the developer's config. */
    private fun gitCli(path: String) = testGitCli(
        mapOf(
            "PATH" to path,
            "HOME" to tempDir.path,
            "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").apply { createNewFile() }.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
        )
    )

    private suspend fun usesBuiltinLfs(jgit: dev.app.leaf.data.git.JGit, gitDir: String): Boolean {
        val result = jgit.provide(gitDir) { git ->
            git.repository.config.getBoolean(
                ConfigConstants.CONFIG_FILTER_SECTION,
                "lfs",
                ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
                false,
            )
        }

        return (result as Either.Ok).value
    }

    private suspend fun withRepository(gitLfsInstalled: Boolean, block: suspend (Repository) -> Unit) {
        Git.init().setDirectory(File(tempDir, "repo")).setInitialBranch("main").call().use { git ->
            if (gitLfsInstalled) {
                setFilters(git.repository)
            }

            block(git.repository)
        }
    }

    /** The filters that `git lfs install` writes. */
    private fun setFilters(repository: Repository) {
        setFilter(repository, "clean", "git-lfs clean -- %f")
        setFilter(repository, "smudge", "git-lfs smudge -- %f")
        setFilter(repository, "process", "git-lfs filter-process")
        repository.config.setBoolean(ConfigConstants.CONFIG_FILTER_SECTION, "lfs", "required", true)
        repository.config.save()
    }

    private fun setFilter(repository: Repository, key: String, command: String?) {
        if (command == null) {
            repository.config.unset(ConfigConstants.CONFIG_FILTER_SECTION, "lfs", key)
        } else {
            repository.config.setString(ConfigConstants.CONFIG_FILTER_SECTION, "lfs", key, command)
        }

        repository.config.save()
    }

    private fun versionRuns(): Int = gitLfsRuns.readLines().count { it == "version" }
}
