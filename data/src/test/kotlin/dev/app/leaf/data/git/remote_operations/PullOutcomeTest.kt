// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What a pull's merge or rebase means for the user ([mergeHasConflicts], [rebaseHasConflicts]), with JGit's own
 * results. Both pulls, JGit's and the git CLI's, use these.
 */
class PullOutcomeTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var git: TestGitCli
    private lateinit var work: File

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
    fun `a merge that succeeds has no conflicts`() {
        commitOnBranch("incoming", "README.md", "incoming\n")

        assertFalse(mergeHasConflicts(merge("incoming")))
    }

    @Test
    fun `a merge that stops at conflicts has them`() {
        commitOnBranch("incoming", "README.md", "incoming\n")
        commit("README.md", "ours\n")

        assertTrue(mergeHasConflicts(merge("incoming")))
    }

    @Test
    fun `a merge that would overwrite local changes fails with their files`() {
        commitOnBranch("incoming", "README.md", "incoming\n")
        commit("other.txt", "ours\n")
        File(work, "README.md").writeText("uncommitted\n")

        val result = merge("incoming")
        assertEquals(MergeResult.MergeStatus.FAILED, result.mergeStatus)

        val error = assertThrows(Exception::class.java) { mergeHasConflicts(result) }
        assertTrue(error.message!!.contains("README.md")) { error.message }
    }

    @Test
    fun `a rebase that stops at conflicts has them, and one with local changes fails`() {
        commitOnBranch("incoming", "README.md", "incoming\n")
        commit("README.md", "ours\n")

        Git.open(work).use { jgit ->
            val upstream = jgit.repository.resolve("incoming")
            assertTrue(rebaseHasConflicts(jgit.rebase().setUpstream(upstream).call()))
            jgit.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call()

            File(work, "README.md").writeText("uncommitted\n")
            val result = jgit.rebase().setUpstream(upstream).call()
            assertEquals(RebaseResult.Status.UNCOMMITTED_CHANGES, result.status)
            assertThrows(Exception::class.java) { rebaseHasConflicts(result) }
        }
    }

    @Test
    fun `a rebase that local files stop has no conflicts, and fails with their files`() {
        commitOnBranch("incoming", "new.txt", "incoming\n")
        commit("other.txt", "ours\n")
        File(work, "new.txt").writeText("untracked\n")

        Git.open(work).use { jgit ->
            val result = jgit.rebase().setUpstream(jgit.repository.resolve("incoming")).call()
            assertEquals(RebaseResult.Status.CONFLICTS, result.status)

            val error = assertThrows(PullWouldOverwriteException::class.java) { rebaseHasConflicts(result) }
            assertTrue(error.message!!.contains("new.txt")) { error.message }
        }
    }

    private fun merge(branch: String): MergeResult = Git.open(work).use { jgit ->
        jgit.merge().include(jgit.repository.resolve(branch)).call()
    }

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
}
