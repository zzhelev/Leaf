// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Commit
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A cherry-pick that JGit couldn't apply, or that stopped at conflicts, is an error, as a revert's is. */
class CherryPickCommitGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val action = CherryPickCommitGitAction(testJGit())
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
    fun `a commit that applies is cherry-picked`(): Unit = runBlocking {
        commitOnBranch("feature", "feature.txt", "feature\n")

        assertEquals(Either.Ok(Unit), action(gitDir, commit("feature")))

        assertEquals("feature\n", File(work, "feature.txt").readText())
        assertEquals("Change feature.txt", git.run(work, "log", "-1", "--format=%s").trim())
    }

    @Test
    fun `local changes that the cherry-pick would overwrite stop it, and are kept`(): Unit = runBlocking {
        commitOnBranch("feature", "README.md", "feature\n")
        File(work, "README.md").writeText("uncommitted\n")
        val before = head()

        val result = action(gitDir, commit("feature"))

        assertEquals(
            Either.Err(
                GenericError(
                    "The cherry-pick didn't happen, as it would overwrite your changes to README.md. Commit or " +
                        "stash them, then cherry-pick again."
                )
            ),
            result,
        )
        assertEquals(before, head())
        assertEquals("uncommitted\n", File(work, "README.md").readText())
    }

    @Test
    fun `a cherry-pick that stops at conflicts is an error, and leaves them to resolve`(): Unit = runBlocking {
        commitOnBranch("feature", "README.md", "feature\n")
        File(work, "README.md").writeText("ours\n")
        git.run(work, "commit", "-am", "Ours")

        val result = action(gitDir, commit("feature"))

        assertEquals(
            Either.Err(GenericError("Cherry-pick stopped with conflicts. Fix the conflicts and commit the desired changes.")),
            result,
        )
        Git.open(work).use { assertEquals(RepositoryState.CHERRY_PICKING, it.repository.repositoryState) }
    }

    private fun commit(rev: String): Commit = Git.open(work).use { jgit ->
        RevWalk(jgit.repository).use { walk ->
            JGitCommitMapper(JGitIdentityMapper()).toDomain(walk.parseCommit(jgit.repository.resolve(rev)))
        }
    }

    /** Commits [content] to [file] on a new branch, and comes back to main. */
    private fun commitOnBranch(branch: String, file: String, content: String) {
        git.run(work, "switch", "-c", branch)
        File(work, file).writeText(content)
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Change $file")
        git.run(work, "switch", "main")
    }

    private fun head() = git.run(work, "rev-parse", "HEAD").trim()
}
