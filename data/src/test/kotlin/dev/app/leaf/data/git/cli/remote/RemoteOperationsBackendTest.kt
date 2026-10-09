// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.SshNeedsGitError
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val LFS_PRE_PUSH_HOOK = "#!/bin/sh\ncommand -v git-lfs >/dev/null 2>&1 || exit 2\ngit lfs pre-push \"$@\"\n"

/** When a remote operation runs with the git CLI, and when with JGit. */
@DisabledOnOs(OS.WINDOWS)
class RemoteOperationsBackendTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var git: TestGitCli
    private lateinit var work: File

    /** A folder with a fake `git-lfs`, for a PATH where git-lfs is installed. */
    private lateinit var gitLfsFolder: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        git = TestGitCli(File(tempDir, "empty.gitconfig"))
        work = git.initRepository(File(tempDir, "work"))

        gitLfsFolder = File(tempDir, "lfs-bin")
        File(gitLfsFolder, "git-lfs").writeExecutable("#!/bin/sh\necho 'git-lfs/3.8.0'\n")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a repository without LFS uses the git CLI`(): Unit = runBlocking {
        assertTrue(backend().useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `the setting switches to JGit`(): Unit = runBlocking {
        assertFalse(backend(withGit = false).useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `without a usable git, JGit is used`(): Unit = runBlocking {
        assertFalse(backend(gitPath = File(tempDir, "no-git").absolutePath).useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `without the askpass helper, JGit is used`(): Unit = runBlocking {
        assertFalse(backend(helper = null).useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `an LFS repository uses the git CLI when its pre-push hook runs an installed git-lfs`(): Unit = runBlocking {
        useLfs()
        File(work, ".git/hooks/pre-push").writeExecutable(LFS_PRE_PUSH_HOOK)

        assertTrue(backend(path = gitLfsFolder.absolutePath + ":/usr/bin:/bin").useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `an LFS repository uses JGit when git-lfs isn't installed`(): Unit = runBlocking {
        useLfs()
        File(work, ".git/hooks/pre-push").writeExecutable(LFS_PRE_PUSH_HOOK)

        assertFalse(backend(path = "/usr/bin:/bin").useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `an LFS repository uses JGit when its pre-push hook doesn't run git-lfs`(): Unit = runBlocking {
        useLfs()

        assertFalse(backend(path = gitLfsFolder.absolutePath + ":/usr/bin:/bin").useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `LFS objects stored in the git dir count as using LFS`(): Unit = runBlocking {
        File(work, ".git/lfs/objects").mkdirs()

        assertFalse(backend(path = "/usr/bin:/bin").useGitCli(gitDir, uploadsObjects = true))
    }

    @Test
    fun `operations that upload nothing use the git CLI in LFS repositories too`(): Unit = runBlocking {
        useLfs()

        assertTrue(backend(path = "/usr/bin:/bin").useGitCli(gitDir, uploadsObjects = false))
    }

    @Test
    fun `a clone uses the git CLI`(): Unit = runBlocking {
        assertTrue(backend().useGitCli())
    }

    @Test
    fun `the setting switches a clone to JGit`(): Unit = runBlocking {
        assertFalse(backend(withGit = false).useGitCli())
    }

    @Test
    fun `a clone without a usable git or the askpass helper uses JGit`(): Unit = runBlocking {
        assertFalse(backend(gitPath = File(tempDir, "no-git").absolutePath).useGitCli())
        assertFalse(backend(helper = null).useGitCli())
    }

    @Test
    fun `tells why JGit runs, which JGit's SSH says to the user`(): Unit = runBlocking {
        assertEquals(SshNeedsGitError.Reason.SettingOff, backend(withGit = false).whyNotGitCli())
        assertEquals(
            SshNeedsGitError.Reason.GitNotFound,
            backend(gitPath = File(tempDir, "no-git").absolutePath).whyNotGitCli(),
        )
        assertEquals(SshNeedsGitError.Reason.HelperMissing, backend(helper = null).whyNotGitCli())
        assertNull(backend().whyNotGitCli())
    }

    private fun useLfs() {
        File(work, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
    }

    private fun backend(
        withGit: Boolean = true,
        gitPath: String? = null,
        helper: File? = File(tempDir, "leaf-askpass").apply { writeText("") },
        path: String = "/usr/bin:/bin",
    ): RemoteOperationsBackend {
        val settings = AppSettingsService(
            mockk<AppSettingsRepository> {
                every { remoteOperationsWithGit } returns flowOf(withGit)
                every { gitExecutablePath } returns flowOf(gitPath)
            }
        )
        val shellVariables = mapOf(
            "PATH" to path,
            "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1",
        )

        return RemoteOperationsBackend(
            appSettingsService = settings,
            gitExecutableLocator = GitExecutableLocator(ProcessRunner()),
            askpassHelper = AskpassHelper { helper },
            gitCli = testGitCli(shellVariables, configuredPath = gitPath),
            jgit = testJGit(),
        )
    }
}
