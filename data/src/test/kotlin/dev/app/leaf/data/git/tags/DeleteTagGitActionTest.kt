// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Tag
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeleteTagGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = DeleteTagGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `deletes a tag on a branch's commit without force`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "tag", "v1")

        val result = action(gitDir(repository), tag("v1"), force = false)

        assertEquals(Either.Ok(Unit), result)
        assertFalse(hasRef(repository, "refs/tags/v1"))
    }

    @Test
    fun `keeps a tag that is the only ref on some commits without force`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        tagCommitsOfDeletedBranch(repository, "backup", commits = 2, annotated = false)

        val result = action(gitDir(repository), tag("backup"), force = false)

        assertEquals(Either.Err(DeleteRefError.TagHasOwnCommits("backup", commitsOnlyOnRef = 2)), result)
        assertTrue(hasRef(repository, "refs/tags/backup"))
    }

    @Test
    fun `counts the commits of an annotated tag`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        tagCommitsOfDeletedBranch(repository, "backup", commits = 1, annotated = true)

        val result = action(gitDir(repository), tag("backup"), force = false)

        assertEquals(Either.Err(DeleteRefError.TagHasOwnCommits("backup", commitsOnlyOnRef = 1)), result)
    }

    @Test
    fun `deletes a tag that is the only ref on some commits with force`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        tagCommitsOfDeletedBranch(repository, "backup", commits = 2, annotated = true)

        val result = action(gitDir(repository), tag("backup"), force = true)

        assertEquals(Either.Ok(Unit), result)
        assertFalse(hasRef(repository, "refs/tags/backup"))
    }

    @Test
    fun `deletes a tag on the detached HEAD without force`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        tagCommitsOfDeletedBranch(repository, "backup", commits = 1, annotated = false)
        git.run(repository, "switch", "--detach", "backup")

        val result = action(gitDir(repository), tag("backup"), force = false)

        assertEquals(Either.Ok(Unit), result)
        assertFalse(hasRef(repository, "refs/tags/backup"))
    }

    /** Tags the tip of a branch with [commits] commits of its own, then deletes the branch, as after a reset. */
    private fun tagCommitsOfDeletedBranch(repository: File, tagName: String, commits: Int, annotated: Boolean) {
        git.run(repository, "switch", "-c", "temporary")

        repeat(commits) { index ->
            git.run(repository, "commit", "--allow-empty", "-m", "Commit ${index + 1}")
        }

        if (annotated) {
            git.run(repository, "tag", "-a", tagName, "-m", "Backup")
        } else {
            git.run(repository, "tag", tagName)
        }

        git.run(repository, "switch", "main")
        git.run(repository, "branch", "-D", "temporary")
    }

    private fun hasRef(repository: File, refName: String) =
        git.run(repository, "for-each-ref", "--format=%(refname)", refName).isNotBlank()

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun tag(name: String) = Tag(commitHash = "", hash = "", name = "refs/tags/$name")
}
