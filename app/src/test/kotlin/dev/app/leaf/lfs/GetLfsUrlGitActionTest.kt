// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.lfs

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.lfs.LfsServer
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

    /** The LFS server of a repository whose only remote is [url], and whose branch has no upstream. */
    private suspend fun lfsServerOfOnlyRemote(url: String): LfsServer? {
        val directory = File(tempDir, "repo-${url.hashCode()}")
        // Nothing here runs a hook or a filter, which would need the login shell's environment
        val jgit = JGit(Provider { error("Only used on Windows") }, LoginShellEnvironment(ProcessRunner()))
        val action = GetLfsUrlGitAction(
            GetTrackingBranchGitAction(jgit),
            GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit),
            GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
        )

        return Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.commit().setMessage("Initial commit").setAllowEmpty(true).setAuthor(identity).setCommitter(identity).call()
            git.remoteAdd().setName("origin").setUri(URIish(url)).call()

            action(git.repository, remoteName = null)
        }
    }
}
