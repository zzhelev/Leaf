// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli

import dev.app.leaf.common.OS
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.gitcli.GitExecutable
import dev.app.leaf.domain.gitcli.GitVersion
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.condition.OS as JUnitOS

class GitExecutableLocatorTest {
    @TempDir
    lateinit var tempDir: File

    private val locator = GitExecutableLocator(ProcessRunner())

    @Test
    fun `macOS candidates check well-known locations before PATH`() {
        val candidates = gitExecutableCandidates(OS.MAC, "/usr/bin:/bin:/opt/homebrew/bin/") { null }

        assertEquals(listOf("/opt/homebrew/bin/git", "/usr/local/bin/git", "/usr/bin/git", "/bin/git"), candidates)
    }

    @Test
    fun `Linux candidates check PATH first`() {
        val candidates = gitExecutableCandidates(OS.LINUX, "/home/user/bin::/usr/bin") { null }

        assertEquals(listOf("/home/user/bin/git", "/usr/bin/git", "/usr/local/bin/git"), candidates)
    }

    @Test
    fun `Windows candidates check PATH then the default install locations`() {
        val environment = mapOf("ProgramFiles" to "C:\\Program Files")

        val candidates = gitExecutableCandidates(OS.WINDOWS, "C:\\Tools;C:\\Program Files\\Git\\cmd\\") { environment[it] }

        assertEquals(listOf("C:\\Tools\\git.exe", "C:\\Program Files\\Git\\cmd\\git.exe"), candidates)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `picks the first candidate that runs and is recent enough`(): Unit = runBlocking {
        val tooOld = fakeGitExecutable(tempDir, "old-git", versionOutput = "git version 2.30.0")
        val broken = fakeGitExecutable(tempDir, "broken-git", versionOutput = "not git")
        val supported = fakeGitExecutable(tempDir, "git", versionOutput = "git version 2.40.1 (Apple Git-150)")
        val missing = File(tempDir, "missing").absolutePath

        val result = locator.findUsableExecutable(listOf(missing, tooOld.path, broken.path, supported.path))

        assertEquals(Either.Ok(GitExecutable(supported.path, GitVersion(2, 40, 1))), result)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `reports the unsupported version when no candidate is recent enough`(): Unit = runBlocking {
        val tooOld = fakeGitExecutable(tempDir, "git", versionOutput = "git version 2.30.0")

        val result = locator.findUsableExecutable(listOf(tooOld.path))

        assertEquals(Either.Err(GitCliError.UnsupportedVersion(tooOld.path, "2.30.0", "2.36.0")), result)
    }

    @Test
    fun `reports the searched paths when no candidate exists`(): Unit = runBlocking {
        val candidates = listOf(File(tempDir, "a/git").absolutePath, File(tempDir, "b/git").absolutePath)

        assertEquals(Either.Err(GitCliError.GitNotFound(candidates)), locator.findUsableExecutable(candidates))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `uses a configured path`(): Unit = runBlocking {
        val configured = fakeGitExecutable(tempDir, "custom-git")

        val result = locator.locate("  ${configured.path}  ")

        assertEquals(Either.Ok(GitExecutable(configured.path, GitVersion(2, 40, 1))), result)
    }

    @Test
    fun `rejects a configured path that does not exist`(): Unit = runBlocking {
        val missing = File(tempDir, "missing").absolutePath

        val result = locator.locate(missing)

        assertEquals(Either.Err(GitCliError.InvalidConfiguredPath(missing, "the file does not exist")), result)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `rejects a configured path that is not git`(): Unit = runBlocking {
        val notGit = fakeGitExecutable(tempDir, "not-git", versionOutput = "hello")

        val result = locator.locate(notGit.path)

        val error = (result as Either.Err).error as GitCliError.InvalidConfiguredPath
        assertEquals(notGit.path, error.path)
        assertTrue(error.reason.contains("unexpected output"), error.reason)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `rejects a configured git that is too old`(): Unit = runBlocking {
        val tooOld = fakeGitExecutable(tempDir, "old-git", versionOutput = "git version 2.35.9")

        val result = locator.locate(tooOld.path)

        assertEquals(Either.Err(GitCliError.UnsupportedVersion(tooOld.path, "2.35.9", "2.36.0")), result)
    }

    @Test
    fun `finds the git installed on this machine`(): Unit = runBlocking {
        val result = locator.locate(null)

        assertInstanceOf(Either.Ok::class.java, result, "No usable git found: $result")
        assertTrue((result as Either.Ok).value.version >= GitVersion.MINIMUM_SUPPORTED)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `caches the executable until the configured path changes or it is invalidated`(): Unit = runBlocking {
        val configured = fakeGitExecutable(tempDir, "custom-git")
        val expected = Either.Ok(GitExecutable(configured.path, GitVersion(2, 40, 1)))

        assertEquals(expected, locator.locate(configured.path))

        configured.delete()
        assertEquals(expected, locator.locate(configured.path))

        locator.invalidate()
        assertInstanceOf(Either.Err::class.java, locator.locate(configured.path))
    }
}
