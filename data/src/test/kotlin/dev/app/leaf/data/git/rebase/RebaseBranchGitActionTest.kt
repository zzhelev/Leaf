// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val NEW_TXT_OVERWRITTEN =
    "The rebase didn't happen, as it would overwrite your changes to new.txt. Commit or stash them, then rebase again."

/**
 * What a rebase's result means ([RebaseBranchGitAction], [rebaseHasStopped]): only STOPPED is a rebase with conflicts
 * to resolve. When local files stop it, JGit puts the branch back and returns CONFLICTS or FAILED, which are errors.
 */
class RebaseBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val action = RebaseBranchGitAction(testJGit())
    private lateinit var git: TestGitCli
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        git = TestGitCli(File(tempDir, "empty.gitconfig"))
        work = git.initRepository(File(tempDir, "work"))
        git.run(work, "config", "user.name", "Leaf Test")
        git.run(work, "config", "user.email", "test@example.invalid")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a rebase that applies has no conflicts`(): Unit = runBlocking {
        commitOnBranch("incoming", "incoming.txt", "incoming\n")
        commit("ours.txt", "ours\n")

        assertEquals(Either.Ok(false), rebaseOnto("incoming"))

        assertEquals(git.run(work, "rev-parse", "incoming").trim(), git.run(work, "rev-parse", "HEAD~1").trim())
    }

    @Test
    fun `a rebase that stops at conflicts has them`(): Unit = runBlocking {
        commitOnBranch("incoming", "README.md", "incoming\n")
        commit("README.md", "ours\n")

        assertEquals(Either.Ok(true), rebaseOnto("incoming"))

        assertEquals(RepositoryState.REBASING_MERGE, repositoryState())
    }

    @Test
    fun `files that the checkout would overwrite stop the rebase, which is an error`(): Unit = runBlocking {
        commitOnBranch("incoming", "new.txt", "incoming\n")
        commit("ours.txt", "ours\n")
        File(work, "new.txt").writeText("untracked\n")
        val before = head()

        assertEquals(Either.Err(GenericError(NEW_TXT_OVERWRITTEN)), rebaseOnto("incoming").withoutException())

        assertEquals(before, head())
        assertEquals(RepositoryState.SAFE, repositoryState())
        assertEquals("untracked\n", File(work, "new.txt").readText())
    }

    @Test
    fun `files that a commit would overwrite stop the rebase, which is an error`(): Unit = runBlocking {
        commitOnBranch("incoming", "incoming.txt", "incoming\n")
        // new.txt comes back when the rebase applies the first commit, and isn't in HEAD
        commit("new.txt", "ours\n")
        git.run(work, "rm", "-q", "new.txt")
        git.run(work, "commit", "-m", "Remove new.txt")
        File(work, "new.txt").writeText("untracked\n")
        val before = head()

        assertEquals(Either.Err(GenericError(NEW_TXT_OVERWRITTEN)), rebaseOnto("incoming").withoutException())

        assertEquals(before, head())
        assertEquals(RepositoryState.SAFE, repositoryState())
        assertEquals("untracked\n", File(work, "new.txt").readText())
    }

    @Test
    fun `an aborted rebase isn't one that completed`() {
        // JGit's own result, which only an interactive rebase's handler can cause. MockK can't create one on JDK 25.
        val aborted = RebaseResult::class.java.getDeclaredField("ABORTED_RESULT")
            .apply { isAccessible = true }
            .get(null) as RebaseResult
        assertEquals(RebaseResult.Status.ABORTED, aborted.status)

        assertThrows(Exception::class.java) { rebaseHasStopped(aborted) }
    }

    private suspend fun rebaseOnto(branch: String) =
        action(gitDir, Branch(git.run(work, "rev-parse", branch).trim(), "refs/heads/$branch", isLocal = true))

    private fun repositoryState() = Git.open(work).use { it.repository.repositoryState }

    /** Commits [content] to [file] on a new branch, and comes back to main. */
    private fun commitOnBranch(branch: String, file: String, content: String) {
        git.run(work, "switch", "-c", branch)
        commit(file, content)
        git.run(work, "switch", "main")
    }

    private fun commit(file: String, content: String) {
        File(work, file).writeText(content)
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Change $file")
    }

    private fun head() = git.run(work, "rev-parse", "HEAD").trim()

    private fun <T> Either<T, GitError>.withoutException() =
        if (this is Either.Err && error is GenericError) Either.Err((error as GenericError).copy(exception = null)) else this
}
