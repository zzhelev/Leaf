// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RenameBranchError
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RenameBranchGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = RenameBranchGitAction(JGitBranchMapper(), testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `renames a branch that no worktree has`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "develop")

        val result = action(gitDir(repository), "refs/heads/develop", "feature/develop")

        assertEquals(Either.Ok(branch("feature/develop", repository)), result)
        assertFalse(hasRef(repository, "refs/heads/develop"))
    }

    @Test
    fun `moves another worktree that has the branch to its new name, as git does`(): Unit = runBlocking {
        // The same repository twice: Leaf renames in one, git in the other
        val (leafRepository, leafWorktree) = repositoryWithAgentWorktree("leaf")
        val (gitRepository, gitWorktree) = repositoryWithAgentWorktree("git")

        val result = action(gitDir(leafRepository), "refs/heads/agent", "feature/agent")
        git.run(gitRepository, "branch", "-m", "agent", "feature/agent")

        assertEquals(Either.Ok(branch("feature/agent", leafRepository)), result)

        for (worktree in listOf(leafWorktree, gitWorktree)) {
            assertEquals("refs/heads/feature/agent", head(worktree), "$worktree")
            // Its own change, not every file staged as new as on a branch that doesn't exist
            assertEquals(" M README.md", git.run(worktree, "status", "--porcelain").trimEnd(), "$worktree")
        }

        assertEquals(head(gitRepository), head(leafRepository))
        assertFalse(hasRef(leafRepository, "refs/heads/agent"))
    }

    @Test
    fun `moves every worktree that has the branch, its own included`(): Unit = runBlocking {
        val (repository, worktree) = repositoryWithAgentWorktree("repo")
        val second = File(tempDir, "wt-second")
        // git checks out a branch in a second worktree only when forced
        git.run(repository, "worktree", "add", "--force", second.path, "agent")

        val result = action(File(repository, ".git/worktrees/wt-repo").path, "refs/heads/agent", "renamed")

        assertEquals(Either.Ok(branch("renamed", repository)), result)
        assertEquals("refs/heads/renamed", head(worktree))
        assertEquals("refs/heads/renamed", head(second))
        assertEquals("refs/heads/main", head(repository))
    }

    @Test
    fun `moves the main worktree when its branch is renamed from a linked worktree`(): Unit = runBlocking {
        val (repository, worktree) = repositoryWithAgentWorktree("repo")

        val result = action(File(repository, ".git/worktrees/wt-repo").path, "refs/heads/main", "trunk")

        assertEquals(Either.Ok(branch("trunk", repository)), result)
        assertEquals("refs/heads/trunk", head(repository))
        assertEquals("refs/heads/agent", head(worktree))
    }

    @Test
    fun `moves a worktree whose folder was deleted, as git does`(): Unit = runBlocking {
        val (repository, worktree) = repositoryWithAgentWorktree("repo")
        worktree.deleteRecursively()

        val result = action(gitDir(repository), "refs/heads/agent", "renamed")

        assertEquals(Either.Ok(branch("renamed", repository)), result)
        assertEquals("ref: refs/heads/renamed", File(repository, ".git/worktrees/wt-repo/HEAD").readText().trim())
    }

    @Test
    fun `writes no reflog entry for another worktree's HEAD in the main worktree's reflog`(): Unit = runBlocking {
        val (repository, _) = repositoryWithAgentWorktree("repo")
        val mainReflog = git.run(repository, "reflog", "HEAD")

        action(gitDir(repository), "refs/heads/agent", "renamed")

        // JGit would write the worktree's entry there, as if the main worktree had moved
        assertEquals(mainReflog, git.run(repository, "reflog", "HEAD"))
    }

    @Test
    fun `refuses a branch that a worktree is rebasing, as git does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-feature")
        git.run(repository, "worktree", "add", "-b", "feature", worktree.path)
        File(worktree, "README.md").writeText("Feature")
        git.run(worktree, "commit", "-am", "Feature")
        File(repository, "README.md").writeText("Main")
        git.run(repository, "commit", "-am", "Main")
        git.runFailing(worktree, "rebase", "main")

        val result = action(gitDir(repository), "refs/heads/feature", "renamed")

        assertEquals(
            Either.Err(
                RenameBranchError.BranchRebasedInWorktree("feature", gitRefusal(repository, "feature", "rebased"))
            ),
            result,
        )
        assertEquals(worktree.canonicalPath, gitRefusal(repository, "feature", "rebased"))
        assertTrue(hasRef(repository, "refs/heads/feature"))
        assertFalse(hasRef(repository, "refs/heads/renamed"))
    }

    @Test
    fun `refuses a branch that its own worktree is rebasing, as git does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        File(repository, "README.md").writeText("Main")
        git.run(repository, "commit", "-am", "Main")
        git.run(repository, "switch", "-c", "feature", "HEAD~1")
        File(repository, "README.md").writeText("Feature")
        git.run(repository, "commit", "-am", "Feature")
        git.runFailing(repository, "rebase", "main")

        val result = action(gitDir(repository), "refs/heads/feature", "renamed")

        // Finishing the rebase would bring back feature, next to renamed
        assertEquals(
            Either.Err(
                RenameBranchError.BranchRebasedInWorktree("feature", gitRefusal(repository, "feature", "rebased"))
            ),
            result,
        )
        assertEquals(repository.canonicalPath, gitRefusal(repository, "feature", "rebased"))
        assertFalse(hasRef(repository, "refs/heads/renamed"))
    }

    @Test
    fun `refuses a branch that a worktree is bisecting from, as git does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-feature")
        git.run(repository, "worktree", "add", "-b", "feature", worktree.path)
        repeat(3) { index -> git.run(worktree, "commit", "--allow-empty", "-m", "Feature $index") }
        git.run(worktree, "bisect", "start", "HEAD", "HEAD~3")

        val result = action(gitDir(repository), "refs/heads/feature", "renamed")

        assertEquals(
            Either.Err(
                RenameBranchError.BranchBisectedInWorktree("feature", gitRefusal(repository, "feature", "bisected"))
            ),
            result,
        )
        assertTrue(hasRef(repository, "refs/heads/feature"))
        assertFalse(hasRef(repository, "refs/heads/renamed"))
    }

    @Test
    fun `renames but reports a worktree whose HEAD is locked, as git does`(): Unit = runBlocking {
        val (leafRepository, leafWorktree) = repositoryWithAgentWorktree("leaf")
        val (gitRepository, gitWorktree) = repositoryWithAgentWorktree("git")
        File(leafRepository, ".git/worktrees/wt-leaf/HEAD.lock").createNewFile()
        File(gitRepository, ".git/worktrees/wt-git/HEAD.lock").createNewFile()

        val result = action(gitDir(leafRepository), "refs/heads/agent", "renamed")
        val gitOutput = git.runFailing(gitRepository, "branch", "-m", "agent", "renamed")

        assertEquals(
            Either.Err(RenameBranchError.WorktreeHeadNotMoved("agent", "renamed", leafWorktree.canonicalPath)),
            result,
        )
        assertTrue("branch renamed to renamed, but HEAD is not updated" in gitOutput, gitOutput)

        for ((repository, worktree) in listOf(leafRepository to leafWorktree, gitRepository to gitWorktree)) {
            assertTrue(hasRef(repository, "refs/heads/renamed"), "$repository")
            assertFalse(hasRef(repository, "refs/heads/agent"), "$repository")
            assertEquals("refs/heads/agent", head(worktree), "$worktree")
        }
    }

    /**
     * Creates the repository [name] on main, and its linked worktree `wt-<name>` on the new branch agent, with a change
     * to README.md that isn't committed.
     */
    private fun repositoryWithAgentWorktree(name: String): Pair<File, File> {
        val repository = git.initRepository(File(tempDir, name))
        val worktree = File(tempDir, "wt-$name")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)
        File(worktree, "README.md").appendText("Agent's change\n")

        return repository to worktree
    }

    /** The worktree that git names when it refuses to rename [branch] from [worktree] ("is being rebased at"). */
    private fun gitRefusal(worktree: File, branch: String, use: String): String {
        val refusal = Regex("branch refs/heads/${Regex.escape(branch)} is being $use at (.+)")
        val output = git.runFailing(worktree, "branch", "-m", branch, "git-would-rename")

        return refusal.find(output)?.groupValues?.get(1)?.trim() ?: fail("git refused for another reason: $output")
    }

    /** The branch that the HEAD of [worktree] points to, read by git. */
    private fun head(worktree: File) = git.run(worktree, "symbolic-ref", "HEAD").trim()

    private fun hasRef(repository: File, refName: String) =
        git.run(repository, "for-each-ref", "--format=%(refname)", refName).isNotBlank()

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun branch(name: String, repository: File) = Branch(
        hash = git.run(repository, "rev-parse", "refs/heads/$name").trim(),
        name = "refs/heads/$name",
        isLocal = true,
    )
}
