// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli

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

    private fun gitCli(configuredPath: String? = null): GitCli {
        val appSettingsRepository = mockk<AppSettingsRepository> {
            every { gitExecutablePath } returns flowOf(configuredPath)
        }

        return GitCli(GitExecutableLocator(ProcessRunner()), ProcessRunner(), AppSettingsService(appSettingsRepository))
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
