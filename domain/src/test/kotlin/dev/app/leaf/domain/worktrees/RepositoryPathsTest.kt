// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * The repositories here are laid out by hand, as git lays them out: `WorktreesTest` in the data module checks the
 * same with folders that `git worktree add` made.
 */
class RepositoryPathsTest {
    @TempDir
    lateinit var tempDir: File

    private val repo get() = File(tempDir, "repo")
    private val commonDir get() = File(repo, ".git")
    private val linked get() = File(tempDir, "wt")
    private val linkedGitDir get() = File(commonDir, "worktrees/wt")

    /** A repository with a linked worktree next to it, whose `.git` file and `gitdir` file have absolute paths. */
    private fun createRepositories() {
        File(commonDir, "refs").mkdirs()
        linkedGitDir.mkdirs()
        linked.mkdirs()
        File(linkedGitDir, "commondir").writeText("../..\n")
        File(linkedGitDir, "gitdir").writeText("${File(linked, ".git").path}\n")
        File(linked, ".git").writeText("gitdir: ${linkedGitDir.path}\n")
    }

    @Test
    fun `a working tree and its git dir have the same git dir`() {
        createRepositories()

        assertEquals(commonDir.canonicalPath, gitDirOf(repo.path))
        assertEquals(commonDir.canonicalPath, gitDirOf(commonDir.path))
        assertEquals(linkedGitDir.canonicalPath, gitDirOf(linked.path))
        assertEquals(linkedGitDir.canonicalPath, gitDirOf(linkedGitDir.path))
    }

    @Test
    fun `follows a relative path in a worktree's git file`() {
        createRepositories()
        // git writes this with worktree.useRelativePaths
        File(linked, ".git").writeText("gitdir: ../repo/.git/worktrees/wt\n")

        assertEquals(linkedGitDir.canonicalPath, gitDirOf(linked.path))
        assertEquals(linkedGitDir.path, gitDirFromDotGitFile(linked)?.toPath()?.normalize()?.toString())
    }

    @Test
    fun `reads only a git file that starts with gitdir`() {
        createRepositories()
        val notAGitFile = File(tempDir, "text").apply { mkdirs() }
        File(notAGitFile, ".git").writeText("something else\n")

        assertNull(gitDirFromDotGitFile(notAGitFile))
        assertNull(gitDirFromDotGitFile(repo)) // A folder
        assertNull(gitDirFromDotGitFile(File(tempDir, "missing")))
        assertEquals(notAGitFile.canonicalPath, gitDirOf(notAGitFile.path))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `two paths of one folder have the same git dir`() {
        createRepositories()
        val link = File(tempDir, "link")
        Files.createSymbolicLink(link.toPath(), tempDir.toPath())

        assertEquals(gitDirOf(linked.path), gitDirOf(File(link, "wt").path))
        assertEquals(gitDirOf(repo.path), gitDirOf(File(link, "repo/.git").path))
    }

    @Test
    fun `finds the first tab that has the repository, however the tab opened it`() {
        createRepositories()
        val other = File(tempDir, "other/.git").apply { mkdirs() }.parentFile

        val tabs = listOf(null, other.path, repo.path, linkedGitDir.path, linked.path)

        assertEquals(2, indexOfRepository(repo.path, tabs))
        assertEquals(2, indexOfRepository(commonDir.path, tabs))
        assertEquals(3, indexOfRepository(linked.path, tabs))
        assertEquals(1, indexOfRepository("${other.path}/", tabs))
        assertEquals(-1, indexOfRepository(File(tempDir, "missing").path, tabs))
        assertEquals(-1, indexOfRepository(linked.path, listOf(null, repo.path)))
    }

    @Test
    fun `tells a linked worktree's git dir`() {
        createRepositories()
        val submoduleGitDir = File(commonDir, "modules/lib").apply { mkdirs() }

        assertTrue(isLinkedWorktreeGitDir(linkedGitDir.path))
        assertFalse(isLinkedWorktreeGitDir(commonDir.path))
        assertFalse(isLinkedWorktreeGitDir(submoduleGitDir.path))
        assertFalse(isLinkedWorktreeGitDir(linked.path))
    }

    @Test
    fun `a tab's working tree is its worktree's folder`() {
        createRepositories()

        assertEquals(repo.path, workTreeOf(commonDir.path))
        assertEquals(repo.path, workTreeOf("${commonDir.path}/"))
        assertEquals(repo.path, workTreeOf(repo.path))
        assertEquals(linked.path, workTreeOf(linkedGitDir.path))
        assertEquals(linked.path, workTreeOf(linked.path))

        // git writes a path relative to the git dir with worktree.useRelativePaths
        File(linkedGitDir, "gitdir").writeText("../../../../wt/.git\n")
        assertEquals(linked.path, workTreeOf(linkedGitDir.path))
    }

    @Test
    fun `keeps a linked worktree's git dir when its gitdir file can't be read`() {
        createRepositories()
        File(linkedGitDir, "gitdir").delete()

        assertEquals(linkedGitDir.path, workTreeOf(linkedGitDir.path))
    }

    @Test
    fun `a removed worktree's git dir leads to its repository`() {
        createRepositories()
        makeGitDir(commonDir)

        assertNull(repositoryOfRemovedWorktree(linkedGitDir.path), "the worktree is still there")

        // Deleted by hand: git lists it as prunable
        linked.deleteRecursively()
        assertEquals(commonDir.path, repositoryOfRemovedWorktree(linkedGitDir.path))

        // Pruned, or removed with `git worktree remove`
        linkedGitDir.deleteRecursively()
        assertEquals(commonDir.path, repositoryOfRemovedWorktree(linkedGitDir.path))
    }

    @Test
    fun `finds a worktree whose gitdir file is relative`() {
        createRepositories()
        makeGitDir(commonDir)
        // git writes this with worktree.useRelativePaths
        File(linkedGitDir, "gitdir").writeText("../../../../wt/.git\n")

        assertNull(repositoryOfRemovedWorktree(linkedGitDir.path))
    }

    @Test
    fun `only a linked worktree's git dir in a repository leads anywhere`() {
        createRepositories()
        makeGitDir(commonDir)
        // An agent's worktree folder, whose parent is named worktrees too
        val agentFolder = File(repo, ".claude/worktrees/agent")

        assertNull(repositoryOfRemovedWorktree(agentFolder.path), "a deleted worktree folder")
        assertNull(repositoryOfRemovedWorktree(commonDir.path), "the main worktree's git dir")
        assertNull(repositoryOfRemovedWorktree(repo.path), "a working tree")
        assertNull(repositoryOfRemovedWorktree(File(tempDir, "worktrees/x").path), "a folder that isn't in a git dir")
    }

    @Test
    fun `a worktree's tab is named after its repository and its folder`() {
        createRepositories()

        assertEquals(TabNames("repo", worktree = null), tabNamesOf(repo.path))
        assertEquals(TabNames("repo", worktree = null), tabNamesOf(commonDir.path))
        assertEquals(TabNames("repo", worktree = "wt"), tabNamesOf(linked.path))
        assertEquals(TabNames("repo", worktree = "wt"), tabNamesOf(linkedGitDir.path))
    }

    @Test
    fun `a bare repository's worktree is named after the repository's folder without git`() {
        val bare = File(tempDir, "project.git")
        val agentGitDir = File(bare, "worktrees/agent")
        val agent = File(tempDir, "agent")
        agentGitDir.mkdirs()
        agent.mkdirs()
        File(agentGitDir, "commondir").writeText("../..\n")
        File(agentGitDir, "gitdir").writeText("${File(agent, ".git").path}\n")
        File(agent, ".git").writeText("gitdir: ${agentGitDir.path}\n")

        assertEquals(TabNames("project", worktree = "agent"), tabNamesOf(agent.path))
    }

    @Test
    fun `marks the worktree that the tab switched to as the tab's`() {
        createRepositories()
        val list = WorktreeList(
            WorktreeBaseBranch("refs/heads/main"),
            listOf(info(repo.path, isCurrent = true), info(linked.path, isCurrent = false)),
        )

        // From another path to the same folder, as macOS has /private/var for /var
        val switched = list.withCurrentWorktree(File(linked, "../wt").path)

        assertEquals(listOf(false, true), switched.worktrees.map { it.worktree.isCurrent })
        assertEquals(list.worktrees.map { it.worktree.path }, switched.worktrees.map { it.worktree.path })
    }

    private fun info(path: String, isCurrent: Boolean) = WorktreeInfo(
        Worktree(
            path = path,
            headSha = "0123456789abcdef0123456789abcdef01234567",
            branch = "refs/heads/main",
            isMain = isCurrent,
            isCurrent = isCurrent,
            isDetached = false,
            isBare = false,
            locked = null,
            prunable = null,
        ),
        status = null,
        aheadBehindBase = null,
        lastCommitTime = null,
    )

    /** Makes [dir] look like a git dir to git, with a `HEAD` file and an `objects` folder. */
    private fun makeGitDir(dir: File) {
        File(dir, "objects").mkdirs()
        File(dir, "HEAD").writeText("ref: refs/heads/main\n")
    }
}
