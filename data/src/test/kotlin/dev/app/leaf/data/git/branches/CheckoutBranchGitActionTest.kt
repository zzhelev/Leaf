// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckoutBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = CheckoutBranchGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `checks out a remote branch as a new local branch that tracks it`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"))

        assertEquals(Either.Ok(Unit), result)
        assertEquals("develop", git.run(clone, "branch", "--show-current").trim())
        assertEquals("origin/develop", git.run(clone, "rev-parse", "--abbrev-ref", "develop@{upstream}").trim())
    }

    @Test
    fun `names both branches when the local branch of a remote one already exists`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("develop")
        git.run(clone, "branch", "develop", "origin/develop")

        val result = action(gitDir(clone), remoteBranch("origin", "develop"))

        assertEquals(
            Either.Err(CheckoutBranchError.LocalBranchAlreadyExists("develop", "origin/develop")),
            result,
        )
        assertEquals("main", git.run(clone, "branch", "--show-current").trim())
    }

    @Test
    fun `keeps the folders of a remote branch in the local branch name`(): Unit = runBlocking {
        val clone = cloneWithRemoteBranch("feature/login")
        git.run(clone, "branch", "feature/login", "origin/feature/login")

        val result = action(gitDir(clone), remoteBranch("origin", "feature/login"))

        assertEquals(
            Either.Err(CheckoutBranchError.LocalBranchAlreadyExists("feature/login", "origin/feature/login")),
            result,
        )
    }

    /** Clones a repository that has [branchName] besides main, so the clone has it only as a remote branch. */
    private fun cloneWithRemoteBranch(branchName: String): File {
        val upstream = git.initRepository(File(tempDir, "upstream"))
        git.run(upstream, "branch", branchName)
        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)

        return clone
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun remoteBranch(remote: String, name: String) =
        Branch(hash = "", name = "refs/remotes/$remote/$name", isLocal = false)
}
