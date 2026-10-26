// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.remote.pathWithoutGitLfs
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.lfs.GitLfsNotice
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The notice that git outside Leaf lacks git-lfs (fork-only): which one a repository gets, its dismissal, and Set up,
 * which runs `git lfs install`. A fake `git-lfs` stands in for it where git-lfs's own behaviour doesn't matter, and the
 * real one, in a home of the test's own, where it does.
 */
@DisabledOnOs(OS.WINDOWS)
class GitLfsNoticeTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    private val globalConfig by lazy { File(tempDir, "global.gitconfig").apply { createNewFile() } }

    /** Sets the repositories up, with a global config of its own, which the git under test doesn't read. */
    private val testGit by lazy { TestGitCli(File(tempDir, "setup.gitconfig")) }

    private val repo by lazy {
        File(tempDir, "repo").also { testGit.run(tempDir, "init", "-q", "-b", "main", it.path) }
    }

    private val gitDir get() = File(repo, ".git").path

    /** Makes the fake git-lfs fail, as an uninstalled one does, while it's there. */
    private val broken by lazy { File(tempDir, "git-lfs-broken") }

    private val gitLfsRuns by lazy { File(tempDir, "git-lfs-runs.log") }

    /** A PATH whose `git-lfs` answers `version`, and does what `git lfs install` does to the filter. */
    private val pathWithGitLfs by lazy {
        val bin = File(tempDir, "bin-with-git-lfs")
        File(bin, "git-lfs").writeExecutable(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}*" >> '${gitLfsRuns.path}'
            [ -f '${broken.path}' ] && exit 1
            case "${'$'}1" in
                version) printf 'git-lfs/3.8.0 (Fake)\n' ;;
                install) git config --global filter.lfs.process 'git-lfs filter-process' ;;
            esac
            """.trimIndent() + "\n"
        )

        "${bin.absolutePath}:/usr/bin:/bin"
    }

    private val pathWithoutGitLfs by lazy { pathWithoutGitLfs(File(tempDir, "bin-without-git-lfs")) }

    private val jgit = testJGit()

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a repository without LFS gets none, whatever git-lfs is`(): Unit = runBlocking {
        assertNull(notice(pathWithGitLfs))
        assertNull(notice(pathWithoutGitLfs))
    }

    @Test
    fun `without git-lfs and without git's config naming it, git-lfs isn't installed`(): Unit = runBlocking {
        usesLfsByAttributes()

        assertEquals(GitLfsNotice.NOT_INSTALLED, notice(pathWithoutGitLfs))
    }

    @Test
    fun `with git's config naming git-lfs, which doesn't work, it's missing`(): Unit = runBlocking {
        usesLfsByAttributes()
        namesGitLfs()

        assertEquals(GitLfsNotice.MISSING, notice(pathWithoutGitLfs))
    }

    @Test
    fun `with git-lfs working and git's config not naming it, it isn't set up`(): Unit = runBlocking {
        usesLfsByAttributes()

        assertEquals(GitLfsNotice.NOT_SET_UP, notice(pathWithGitLfs))
    }

    @Test
    fun `with git-lfs working and named, git runs it, and there's none`(): Unit = runBlocking {
        usesLfsByAttributes()
        namesGitLfs()

        assertNull(notice(pathWithGitLfs))
    }

    @Test
    fun `LFS objects count as using LFS, without the attributes`(): Unit = runBlocking {
        File(repo, ".git/lfs/objects").mkdirs()

        assertEquals(GitLfsNotice.NOT_SET_UP, notice(pathWithGitLfs))
    }

    @Test
    fun `a bare repository has no working tree for git to write files in`(): Unit = runBlocking {
        val bare = File(tempDir, "bare.git")
        testGit.run(tempDir, "init", "-q", "--bare", bare.path)
        File(bare, "lfs/objects").mkdirs()

        assertNull(GetGitLfsNoticeGitAction(jgit, GitLfsSetup(gitCli(pathWithGitLfs)))(bare.path).value())
    }

    @Test
    fun `a linked worktree counts the common git dir's objects, and its own attributes`(): Unit = runBlocking {
        testGit.run(repo, "commit", "-q", "--allow-empty", "-m", "Start")
        val worktree = File(tempDir, "wt")
        testGit.run(repo, "worktree", "add", "-q", worktree.path)
        val worktreeGitDir = File(repo, ".git/worktrees/wt").path
        val action = GetGitLfsNoticeGitAction(jgit, GitLfsSetup(gitCli(pathWithGitLfs)))

        assertNull(action(worktreeGitDir).value())

        File(worktree, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        assertEquals(GitLfsNotice.NOT_SET_UP, action(worktreeGitDir).value())

        File(worktree, ".gitattributes").delete()
        File(repo, ".git/lfs/objects").mkdirs()
        assertEquals(GitLfsNotice.NOT_SET_UP, action(worktreeGitDir).value())
    }

    @Test
    fun `a dismissed notice is left out in every worktree, and another one shows`(): Unit = runBlocking {
        usesLfsByAttributes()
        testGit.run(repo, "add", ".gitattributes")
        testGit.run(repo, "commit", "-q", "-m", "Start")
        val worktree = File(tempDir, "wt")
        testGit.run(repo, "worktree", "add", "-q", worktree.path)

        val dismissed = DismissGitLfsNoticeGitAction(jgit)(gitDir, GitLfsNotice.NOT_SET_UP)
        assertInstanceOf(Either.Ok::class.java, dismissed)
        assertTrue(File(repo, ".git/leaf").readText().contains("not-set-up"))

        assertNull(notice(pathWithGitLfs))
        val worktreeGitDir = File(repo, ".git/worktrees/wt").path
        assertNull(GetGitLfsNoticeGitAction(jgit, GitLfsSetup(gitCli(pathWithGitLfs)))(worktreeGitDir).value())

        // git-lfs gone since: something else to say
        assertEquals(GitLfsNotice.NOT_INSTALLED, notice(pathWithoutGitLfs))
    }

    @Test
    fun `Set up runs git lfs install in the working tree, and asks git-lfs again`(): Unit = runBlocking {
        usesLfsByAttributes()
        broken.createNewFile()
        val setup = GitLfsSetup(gitCli(pathWithGitLfs), now = { 0L })
        val getNotice = GetGitLfsNoticeGitAction(jgit, setup)

        assertEquals(GitLfsNotice.NOT_INSTALLED, getNotice(gitDir).value())

        // Installed meanwhile, which the minute's answer doesn't know yet
        broken.delete()
        assertEquals(GitLfsNotice.NOT_INSTALLED, getNotice(gitDir).value())

        val result = SetUpGitLfsGitAction(jgit, gitCli(pathWithGitLfs), setup)(gitDir)

        assertInstanceOf(Either.Ok::class.java, result)
        assertTrue(gitLfsRuns.readLines().contains("install"))
        assertNull(getNotice(gitDir).value())
    }

    @Test
    fun `Set up with the real git-lfs writes the filter and the hooks, as git-lfs does`(): Unit = runBlocking {
        val path = realGitLfsPath()
        usesLfsByAttributes()
        val setup = GitLfsSetup(gitCli(path))
        val getNotice = GetGitLfsNoticeGitAction(jgit, setup)

        assertEquals(GitLfsNotice.NOT_SET_UP, getNotice(gitDir).value())

        val result = SetUpGitLfsGitAction(jgit, gitCli(path), setup)(gitDir)

        assertInstanceOf(Either.Ok::class.java, result)
        assertTrue(globalConfig.readText().contains("process = git-lfs filter-process"))
        assertTrue(File(repo, ".git/hooks/pre-push").readText().contains("git lfs pre-push"))
        assertNull(getNotice(gitDir).value())
    }

    @Test
    fun `a hook in the way fails Set up with git-lfs's message, after the filter is written`(): Unit = runBlocking {
        val path = realGitLfsPath()
        usesLfsByAttributes()
        val hook = File(repo, ".git/hooks/pre-push").apply { writeExecutable("#!/bin/sh\necho mine\n") }
        val setup = GitLfsSetup(gitCli(path))

        val result = SetUpGitLfsGitAction(jgit, gitCli(path), setup)(gitDir)

        val error = assertInstanceOf(Either.Err::class.java, result).error
        val failed = assertInstanceOf(GitCliError.CommandFailed::class.java, error)
        assertTrue(failed.stderr.contains("Hook already exists: pre-push")) { failed.stderr }
        assertEquals("#!/bin/sh\necho mine\n", hook.readText())
        // The filter is there, so git outside Leaf runs git-lfs: the strip goes, and the error stays on screen
        assertNull(GetGitLfsNoticeGitAction(jgit, setup)(gitDir).value())
    }

    @Test
    fun `nothing runs git-lfs when the repository doesn't use LFS`(): Unit = runBlocking {
        assertNull(notice(pathWithGitLfs))

        assertFalse(gitLfsRuns.exists())
    }

    private suspend fun notice(path: String): GitLfsNotice? =
        GetGitLfsNoticeGitAction(jgit, GitLfsSetup(gitCli(path)))(gitDir).value()

    private fun usesLfsByAttributes() {
        File(repo, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
    }

    /** What `git lfs install` writes, in the test's global config. */
    private fun namesGitLfs() {
        globalConfig.writeText(
            "[filter \"lfs\"]\n\tclean = git-lfs clean -- %f\n\tsmudge = git-lfs smudge -- %f\n" +
                "\tprocess = git-lfs filter-process\n\trequired = true\n"
        )
    }

    private fun realGitLfsPath(): String {
        val realGitLfs = ProcessBuilder("/bin/sh", "-c", "command -v git-lfs").start()
            .let { process -> process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() } }
        assumeTrue(realGitLfs.isNotEmpty()) { "git-lfs isn't installed" }

        return "${File(realGitLfs).parent}:/usr/bin:/bin"
    }

    /** git with [path], kept away from the developer's config, with the test's own as the global one. */
    private fun gitCli(path: String) = testGitCli(
        mapOf(
            "PATH" to path,
            "HOME" to tempDir.path,
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
        )
    )
}

private fun <T> Either<T, *>.value(): T = when (this) {
    is Either.Ok -> value
    is Either.Err -> throw AssertionError("Expected a value, got $error")
}
