// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.lfs

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.lfs.GetLfsUrlSettingGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.inject.Provider

/** Where Leaf's built-in LFS client finds the LFS server of a repository's remote. */
class GetLfsUrlGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private var repositories = 0

    @BeforeEach
    fun isolateJGitConfig() {
        // Keeps the developer's git config, and its url.<base>.insteadOf, out of the test
        val configDir = File(tempDir, "config")
        SystemReader.setInstance(object : SystemReader.Delegate(originalReader) {
            override fun openUserConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "user.gitconfig"), fs)

            override fun openSystemConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

            override fun openJGitConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "jgit.config"), fs)
        })
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the LFS server of an SSH remote is the remote, which git-lfs asks with git-lfs-authenticate`(): Unit =
        runBlocking {
            assertEquals(
                LfsServer("ssh://git@example.com:2222/org/repo.git", "ssh://git@example.com:2222/org/repo.git"),
                lfsServerOfOnlyRemote("ssh://git@example.com:2222/org/repo.git"),
            )
            assertEquals(
                LfsServer("git@example.com:org/repo", "git@example.com:org/repo"),
                lfsServerOfOnlyRemote("git@example.com:org/repo"),
            )
        }

    @Test
    fun `the LFS server of an HTTPS remote is under its info-lfs`(): Unit = runBlocking {
        assertEquals(
            LfsServer("https://example.com/org/repo.git/info/lfs", "https://example.com/org/repo"),
            lfsServerOfOnlyRemote("https://example.com/org/repo"),
        )
    }

    @Test
    fun `an lfs url in the repository's config is the LFS server, and the remote's URL is asked for credentials`():
        Unit = runBlocking {
        val server = lfsServerOfOnlyRemote("https://example.com/org/repo") { git ->
            git.repository.config.apply { setString("lfs", null, "url", "https://lfs.example.com/org/repo") }.save()
        }

        assertEquals(LfsServer("https://lfs.example.com/org/repo", "https://example.com/org/repo"), server)
    }

    @Test
    fun `a remote's lfsurl is its LFS server, unless lfs url is set`(): Unit = runBlocking {
        val remoteLfsUrl: (Git) -> Unit = { git ->
            git.repository.config.apply { setString("remote", "origin", "lfsurl", "https://lfs.example.com/origin") }
                .save()
        }
        val expected = LfsServer("https://lfs.example.com/origin", "https://example.com/org/repo")

        // The only remote, and the remote asked for
        assertEquals(expected, lfsServerOfOnlyRemote("https://example.com/org/repo", configure = remoteLfsUrl))
        assertEquals(
            expected,
            lfsServerOfOnlyRemote("https://example.com/org/repo", "refs/remotes/origin", configure = remoteLfsUrl),
        )

        val withLfsUrl: (Git) -> Unit = { git ->
            remoteLfsUrl(git)
            git.repository.config.apply { setString("lfs", null, "url", "https://lfs.example.com/repo") }.save()
        }
        val lfsUrlServer = LfsServer("https://lfs.example.com/repo", "https://example.com/org/repo")
        assertEquals(lfsUrlServer, lfsServerOfOnlyRemote("https://example.com/org/repo", configure = withLfsUrl))
        assertEquals(
            lfsUrlServer,
            lfsServerOfOnlyRemote("https://example.com/org/repo", "refs/remotes/origin", configure = withLfsUrl),
        )
    }

    @Test
    fun `the lfsurl of the remote that the branch tracks`(): Unit = runBlocking {
        val server = lfsServerOfOnlyRemote("https://example.com/org/repo") { git ->
            git.remoteAdd().setName("upstream").setUri(URIish("https://example.com/upstream/repo")).call()
            git.repository.config.apply {
                setString("remote", "origin", "lfsurl", "https://lfs.example.com/origin")
                setString("remote", "upstream", "lfsurl", "https://lfs.example.com/upstream")
                setString("branch", "main", "remote", "upstream")
                setString("branch", "main", "merge", "refs/heads/main")
            }.save()
        }

        assertEquals(LfsServer("https://lfs.example.com/upstream", "https://example.com/upstream/repo"), server)
    }

    /**
     * The LFS server for [remoteName] of a repository whose only remote is `origin` at [url], and whose branch has no
     * upstream, once [configure] has changed them. git isn't found, so the settings come from JGit's config, which the
     * test keeps apart. `git config` is tested in `GetLfsUrlSettingGitActionTest`.
     */
    private suspend fun lfsServerOfOnlyRemote(
        url: String,
        remoteName: String? = null,
        configure: (Git) -> Unit = {},
    ): LfsServer? {
        // A repository of its own each time, as the settings would carry over
        val directory = File(tempDir, "repo-${repositories++}")
        // Nothing here runs a hook or a filter, which would need the login shell's environment
        val jgit = JGit(Provider { error("Only used on Windows") }, LoginShellEnvironment(ProcessRunner()))
        val settings = mockk<AppSettingsRepository> {
            every { gitExecutablePath } returns flowOf(File(tempDir, "no-git").path)
        }
        val withoutGit = GitCli(
            GitExecutableLocator(ProcessRunner()),
            ProcessRunner(),
            AppSettingsService(settings),
            LoginShellEnvironment(ProcessRunner()),
        )
        val action = GetLfsUrlGitAction(
            GetTrackingBranchGitAction(jgit),
            GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit),
            GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
            GetLfsUrlSettingGitAction(withoutGit),
        )

        return Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.commit().setMessage("Initial commit").setAllowEmpty(true).setAuthor(identity).setCommitter(identity).call()
            git.remoteAdd().setName("origin").setUri(URIish(url)).call()
            configure(git)

            action(git.repository, remoteName)
        }
    }
}
