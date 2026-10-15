// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Which `lfs.url` and `remote.<name>.lfsurl` Leaf's built-in LFS client reads ([GetLfsUrlSettingGitAction]): git's
 * config, then `.lfsconfig`.
 */
class GetLfsUrlSettingGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val lfsConfigFile by lazy { File(repository, ".lfsconfig") }

    /** What the login shell adds: git reads the test's global config, not the developer's. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to tempDir.absolutePath,
        )
    }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lfs url in the repository's config names the LFS server`(): Unit = runBlocking {
        git.run(repository, "config", "lfs.url", "https://lfs.example.com/repo")

        assertEquals("https://lfs.example.com/repo", lfsUrl())
    }

    @Test
    fun `git's config comes before lfsconfig, the repository's before the user's`(): Unit = runBlocking {
        assertNull(lfsUrl())

        lfsConfigFile("https://lfsconfig.example.com/repo")
        assertEquals("https://lfsconfig.example.com/repo", lfsUrl())

        git.run(repository, "config", "--global", "lfs.url", "https://user.example.com/repo")
        assertEquals("https://user.example.com/repo", lfsUrl())

        git.run(repository, "config", "lfs.url", "https://repository.example.com/repo")
        assertEquals("https://repository.example.com/repo", lfsUrl())
    }

    @Test
    fun `an lfs url in a file that the user's config includes for the repository counts`(): Unit = runBlocking {
        val included = File(tempDir, "work.gitconfig")
        included.writeText("[lfs]\n\turl = https://work.example.com/lfs\n")
        // git compares the real path: /private/var/... on macOS
        globalConfig.appendText("[includeIf \"gitdir:${repository.canonicalPath}/\"]\n\tpath = ${included.path}\n")

        assertEquals("https://work.example.com/lfs", lfsUrl())
    }

    @Test
    fun `a remote's lfsurl is read from git's config, then from lfsconfig`(): Unit = runBlocking {
        assertNull(remoteLfsUrl("origin"))

        lfsConfigFile("[remote \"origin\"]\n\tlfsurl = https://lfsconfig.example.com/repo\n")
        assertEquals("https://lfsconfig.example.com/repo", remoteLfsUrl("origin"))
        assertNull(remoteLfsUrl("upstream"))

        git.run(repository, "config", "--global", "remote.origin.lfsurl", "https://user.example.com/repo")
        assertEquals("https://user.example.com/repo", remoteLfsUrl("origin"))

        git.run(repository, "config", "remote.origin.lfsurl", "https://repository.example.com/repo")
        assertEquals("https://repository.example.com/repo", remoteLfsUrl("origin"))
        // Not lfs.url
        assertNull(lfsUrl())
    }

    @Test
    fun `a remote whose name has dots`(): Unit = runBlocking {
        lfsConfigFile("[remote \"my.remote\"]\n\tlfsurl = https://lfsconfig.example.com/repo\n")
        assertEquals("https://lfsconfig.example.com/repo", remoteLfsUrl("my.remote"))

        git.run(repository, "config", "remote.my.remote.lfsurl", "https://repository.example.com/repo")
        assertEquals("https://repository.example.com/repo", remoteLfsUrl("my.remote"))
        assertNull(remoteLfsUrl("my"))
    }

    @Test
    fun `without a usable git, JGit's config is read`(): Unit = runBlocking {
        // A user config that only JGit reads, so that the two can be told apart
        File(tempDir, "jgit-config/user.gitconfig").apply { parentFile.mkdirs() }.writeText(
            "[lfs]\n\turl = https://jgit.example.com/repo\n" +
                "[remote \"origin\"]\n\tlfsurl = https://jgit.example.com/origin\n",
        )
        lfsConfigFile(
            "[lfs]\n\turl = https://lfsconfig.example.com/repo\n" +
                "[remote \"origin\"]\n\tlfsurl = https://lfsconfig.example.com/origin\n",
        )

        val withoutGit = GetLfsUrlSettingGitAction(testGitCli(configuredPath = File(tempDir, "no-git").path))

        assertEquals("https://lfsconfig.example.com/repo", lfsUrl())
        assertEquals("https://lfsconfig.example.com/origin", remoteLfsUrl("origin"))
        Git.open(repository).use {
            assertEquals("https://jgit.example.com/repo", withoutGit.lfsUrl(it.repository))
            assertEquals("https://jgit.example.com/origin", withoutGit.remoteLfsUrl(it.repository, "origin"))
        }
    }

    @Test
    fun `the setting is the one that git-lfs uses`(): Unit = runBlocking {
        val gitLfs = ProcessBuilder("/bin/sh", "-c", "command -v git-lfs").start()
            .let { process -> process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() } }
        assumeTrue(gitLfs.isNotEmpty()) { "git-lfs isn't installed" }

        git.run(repository, "remote", "add", "origin", "https://example.com/org/repo")
        git.run(repository, "remote", "add", "upstream", "https://example.com/upstream/repo")
        val settings = mutableListOf<String>()

        /** Sets [key] in [file] (git's config, or `.lfsconfig`), and checks the server against git-lfs's. */
        suspend fun set(key: String, file: String, url: String) {
            git.run(repository, "config", "--file", file, key, url)
            settings += "$key in $file"

            for (remote in listOf("origin", "upstream")) {
                val leaf = lfsUrl() ?: remoteLfsUrl(remote) ?: continue
                assertEquals(gitLfsEndpoint(remote), leaf) { "$remote after $settings" }
            }
        }

        val local = File(repository, ".git/config").path
        val lfsConfig = File(repository, ".lfsconfig").path
        set("remote.upstream.lfsurl", lfsConfig, "https://lfsconfig.example.com/upstream")
        set("remote.origin.lfsurl", lfsConfig, "https://lfsconfig.example.com/origin")
        set("remote.origin.lfsurl", globalConfig.path, "https://user.example.com/origin")
        set("remote.origin.lfsurl", local, "https://repository.example.com/origin")
        // The branch tracks upstream, which git-lfs then lists apart
        git.run(repository, "config", "branch.main.remote", "upstream")
        git.run(repository, "config", "branch.main.merge", "refs/heads/main")
        set("remote.upstream.lfsurl", local, "https://repository.example.com/upstream")
        set("lfs.url", lfsConfig, "https://lfsconfig.example.com/repo")
        set("lfs.url", globalConfig.path, "https://user.example.com/repo")
        set("lfs.url", local, "https://repository.example.com/repo")
        assertEquals("https://repository.example.com/repo", lfsUrl())
    }

    /** The server that `git lfs env` names for [remote]: `Endpoint (upstream)=`, or `Endpoint=` for the default. */
    private fun gitLfsEndpoint(remote: String): String {
        val lines = git.run(repository, mapOf("HOME" to tempDir.absolutePath), "lfs", "env").lines()
        val line = lines.firstOrNull { it.startsWith("Endpoint ($remote)=") }
            ?: lines.first { it.startsWith("Endpoint=") }.takeIf { remote == "origin" }
            ?: error("git lfs env names no server for $remote:\n${lines.joinToString("\n")}")

        return line.substringAfter("=").substringBefore(" (auth=")
    }

    private fun lfsConfigFile(url: String) =
        lfsConfigFile.writeText(if (url.startsWith("[")) url else "[lfs]\n\turl = $url\n")

    private suspend fun lfsUrl(): String? = Git.open(repository).use { git ->
        GetLfsUrlSettingGitAction(testGitCli(shellVariables)).lfsUrl(git.repository)
    }

    private suspend fun remoteLfsUrl(remote: String): String? = Git.open(repository).use { git ->
        GetLfsUrlSettingGitAction(testGitCli(shellVariables)).remoteLfsUrl(git.repository, remote)
    }
}
