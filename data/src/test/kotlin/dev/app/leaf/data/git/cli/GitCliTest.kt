// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli

import dev.app.leaf.data.git.HOOK_RUNNING_TOOL
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.createHookTool
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

class GitCliTest {
    @TempDir
    lateinit var tempDir: File

    private fun gitCli(configuredPath: String? = null, shellVariables: Map<String, String> = emptyMap()): GitCli {
        val appSettingsRepository = mockk<AppSettingsRepository> {
            every { gitExecutablePath } returns flowOf(configuredPath)
        }

        return GitCli(
            GitExecutableLocator(ProcessRunner()),
            ProcessRunner(),
            AppSettingsService(appSettingsRepository),
            LoginShellEnvironment { shellVariables },
        )
    }

    @Test
    fun `returns stdout of a successful command`(): Unit = runBlocking {
        val gitCli = gitCli()
        val repository = File(tempDir, "repo").apply { mkdirs() }

        assertInstanceOf(Either.Ok::class.java, gitCli.run(repository, listOf("init")))
        assertEquals(Either.Ok("true\n"), gitCli.run(repository, listOf("rev-parse", "--is-inside-work-tree")))
    }

    @Test
    fun `returns the exit code and untranslated stderr of a failed command`(): Unit = runBlocking {
        val notARepository = File(tempDir, "plain").apply { mkdirs() }

        val result = gitCli().run(notARepository, listOf("status"))

        val error = (result as Either.Err).error as GitCliError.CommandFailed
        assertEquals("git status", error.command)
        assertEquals(128, error.exitCode)
        assertTrue(error.stderr.startsWith("fatal: not a git repository"), error.stderr)
    }

    @Test
    fun `the password of a URL is hidden in the command that an error shows`(): Unit = runBlocking {
        val notARepository = File(tempDir, "plain").apply { mkdirs() }

        val url = "https://bob:t0ken@example.com/x.git"

        val result = gitCli().run(notARepository, listOf("remote", "add", "origin", url))

        val error = (result as Either.Err).error as GitCliError.CommandFailed
        assertEquals("git remote add origin https://bob:***@example.com/x.git", error.command)
    }

    @Test
    fun `only a URL's password is hidden`() {
        assertEquals("https://bob:***@host:8443/x.git", redactUrlPassword("https://bob:t0ken@host:8443/x.git"))
        assertEquals("ssh://git:***@example.com/x.git", redactUrlPassword("ssh://git:p%40ss@example.com/x.git"))
        assertEquals("https://bob@example.com/x.git", redactUrlPassword("https://bob@example.com/x.git"))
        assertEquals("git@github.com:team/x.git", redactUrlPassword("git@github.com:team/x.git"))
        assertEquals("https://example.com:8443/a@b", redactUrlPassword("https://example.com:8443/a@b"))
        assertEquals("--progress", redactUrlPassword("--progress"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runs git with a non-interactive, parseable environment`(): Unit = runBlocking {
        val fakeGit = fakeGitExecutable(tempDir, "git", body = "env")

        val result = gitCli(fakeGit.path).run(tempDir, listOf("status"))

        val environment = (result as Either.Ok).value.lines()
        assertTrue("LC_ALL=C" in environment, "$environment")
        assertTrue("GIT_TERMINAL_PROMPT=0" in environment, "$environment")
        assertTrue("GIT_OPTIONAL_LOCKS=0" in environment, "$environment")
        assertTrue(environment.none { it.startsWith("GIT_DIR=") || it.startsWith("GIT_WORK_TREE=") }, "$environment")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `times out`(): Unit = runBlocking {
        val fakeGit = fakeGitExecutable(tempDir, "git", body = "sleep 30")

        val result = gitCli(fakeGit.path).run(tempDir, listOf("fetch"), timeout = 1.seconds)

        assertEquals(Either.Err(GitCliError.TimedOut("git fetch", 1)), result)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `adds the login shell's variables, but its own environment wins`(): Unit = runBlocking {
        val fakeGit = fakeGitExecutable(tempDir, "git", body = "env")
        val shellVariables = mapOf("LEAF_SHELL_VARIABLE" to "from the shell", "LC_ALL" to "en_US.UTF-8")

        val result = gitCli(fakeGit.path, shellVariables).run(tempDir, listOf("status"))

        val environment = (result as Either.Ok).value.lines()
        assertTrue("LEAF_SHELL_VARIABLE=from the shell" in environment, "$environment")
        assertTrue("LC_ALL=C" in environment, "$environment")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `hooks that git runs find the tools on the login shell's PATH`(): Unit = runBlocking {
        val globalConfig = File(tempDir, "config/global.gitconfig")
        val repository = TestGitCli(globalConfig).initRepository(File(tempDir, "repo"))
        val tools = createHookTool(File(tempDir, "tools"))
        File(repository, ".git/hooks/post-checkout").writeExecutable(HOOK_RUNNING_TOOL)
        // Keeps the developer's git config out, as TestGitCli does
        val isolated = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.absolutePath, "GIT_CONFIG_NOSYSTEM" to "1")
        val shellPath = isolated + ("PATH" to "${tools.absolutePath}:${System.getenv("PATH")}")

        fun worktreeAdd(branch: String) = listOf("worktree", "add", "-b", branch, "../$branch")

        val withoutPath = gitCli(shellVariables = isolated).run(repository, worktreeAdd("a"))
        val withPath = gitCli(shellVariables = shellPath).run(repository, worktreeAdd("b"))

        // git creates the worktree, then exits with the post-checkout hook's code
        val error = (withoutPath as Either.Err).error as GitCliError.CommandFailed
        assertTrue("leaf-hook-tool" in error.stderr, error.stderr)
        assertInstanceOf(Either.Ok::class.java, withPath)
        assertTrue(File(tools, "ran").exists())
    }

    @Test
    fun `reports a missing working directory`(): Unit = runBlocking {
        val result = gitCli().run(File(tempDir, "missing"), listOf("status"))

        assertInstanceOf(GitCliError.StartFailed::class.java, (result as Either.Err).error)
    }

    @Test
    fun `reports an unusable configured git`(): Unit = runBlocking {
        val missing = File(tempDir, "missing-git").absolutePath

        val result = gitCli(missing).run(tempDir, listOf("status"))

        assertEquals(Either.Err(GitCliError.InvalidConfiguredPath(missing, "the file does not exist")), result)
    }
}
