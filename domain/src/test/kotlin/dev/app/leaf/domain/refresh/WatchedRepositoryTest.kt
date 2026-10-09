// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.domain.usecases.DataToRefresh
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class WatchedRepositoryTest {
    @TempDir
    lateinit var tempDir: File

    private val repo get() = File(tempDir, "repo")
    private val commonDir get() = File(repo, ".git")
    private val mainTab get() = WatchedRepository(commonDir, commonDir)
    // The common git dir as JGit reads it from the commondir file
    private val linkedTab get() =
        WatchedRepository(File(commonDir, "worktrees/agent"), File(commonDir, "worktrees/agent/../.."))

    private fun path(vararg parts: String) = parts.fold(tempDir) { file, part -> File(file, part) }.path

    @Test
    fun `in the main worktree, its git dir refreshes everything and the other worktrees only the list`() {
        val tab = mainTab

        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "HEAD")))
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "refs", "heads", "main")))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "worktrees")))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "worktrees", "agent")))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "worktrees", "agent", "index")))
        assertEquals(WatchedChange.WORKING_TREE, tab.changeOf(path("repo", "src", "Main.kt")))
        assertEquals(false, tab.isLinkedWorktree)
    }

    @Test
    fun `files whose names start like the git dir's belong to the working tree`() {
        val tab = mainTab

        assertEquals(WatchedChange.WORKING_TREE, tab.changeOf(path("repo", ".gitignore")))
        assertEquals(WatchedChange.WORKING_TREE, tab.changeOf(path("repo", ".github", "workflows", "build.yml")))
    }

    @Test
    fun `in a linked worktree, its own git dir and the shared refs refresh everything`() {
        val tab = linkedTab

        assertEquals(true, tab.isLinkedWorktree)
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "worktrees", "agent", "HEAD")))
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "worktrees", "agent", "logs", "HEAD")))
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "refs", "heads", "agent")))
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "packed-refs")))
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(path("repo", ".git", "config")))
    }

    @Test
    fun `in a linked worktree, the main worktree and the other linked ones refresh only the list`() {
        val tab = linkedTab

        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "HEAD")))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "index")))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "worktrees", "other", "HEAD")))
        // Its name starts like the tab's
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "worktrees", "agent2", "HEAD")))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(path("repo", ".git", "worktrees")))
    }

    @Test
    fun `Leaf's files, JGit's probe files and edited messages need nothing`() {
        val main = mainTab
        val linked = linkedTab

        for (tab in listOf(main, linked)) {
            assertEquals(WatchedChange.NONE, tab.changeOf(path("repo", ".git", "leaf")))
            assertEquals(WatchedChange.NONE, tab.changeOf(path("repo", ".git", "leaf.lock")))
            assertEquals(WatchedChange.NONE, tab.changeOf(path("repo", ".git", ".probe-1b2c")))
        }

        assertEquals(WatchedChange.NONE, main.changeOf(path("repo", ".git", "COMMIT_EDITMSG")))
        assertEquals(WatchedChange.NONE, main.changeOf(path("repo", ".git", "MERGE_MSG")))
        assertEquals(WatchedChange.NONE, main.changeOf(path("repo", ".git", "SQUASH_MSG")))
        assertEquals(WatchedChange.NONE, linked.changeOf(path("repo", ".git", "worktrees", "agent", "leaf")))
        assertEquals(WatchedChange.NONE, linked.changeOf(path("repo", ".git", "worktrees", "agent", "COMMIT_EDITMSG")))
        // Another worktree's are part of its state
        assertEquals(WatchedChange.OTHER_WORKTREES, linked.changeOf(path("repo", ".git", "COMMIT_EDITMSG")))
        // A file of the working tree with the same name
        assertEquals(WatchedChange.WORKING_TREE, main.changeOf(path("repo", "leaf")))
    }

    @Test
    fun `a batch refreshes what its most important change needs`() {
        val tab = mainTab
        val gitDir = path("repo", ".git", "HEAD")
        val workingTree = path("repo", "a.txt")
        val otherWorktree = path("repo", ".git", "worktrees", "agent", "index")
        val leafFile = path("repo", ".git", "leaf")

        assertEquals(listOf(DataToRefresh.ALL), tab.dataToRefresh(listOf(otherWorktree, workingTree, gitDir)))
        assertEquals(
            listOf(DataToRefresh.STATUS, DataToRefresh.LOG, DataToRefresh.REPO_STATE),
            tab.dataToRefresh(listOf(otherWorktree, workingTree, leafFile)),
        )
        assertEquals(listOf(DataToRefresh.WORKTREES), tab.dataToRefresh(listOf(otherWorktree, leafFile)))
        assertEquals(emptyList<DataToRefresh>(), tab.dataToRefresh(listOf(leafFile)))
        assertEquals(emptyList<DataToRefresh>(), tab.dataToRefresh(emptyList()))
    }

    @Test
    fun `knows the worktrees folder, which the watcher adds once git creates it`() {
        assertEquals(true, mainTab.isWorktreesDir(path("repo", ".git", "worktrees")))
        assertEquals(true, linkedTab.isWorktreesDir(path("repo", ".git", "worktrees")))
        assertEquals(false, mainTab.isWorktreesDir(path("repo", ".git", "worktrees", "agent")))
        assertEquals(File(commonDir, "worktrees").path, mainTab.worktreesDir.path)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `reported real paths match a git dir given through a link`() {
        repo.mkdirs()
        commonDir.mkdirs()
        val link = File(tempDir, "link")
        Files.createSymbolicLink(link.toPath(), repo.toPath())

        val tab = WatchedRepository(File(link, ".git"), File(link, ".git"))
        val realRepo = repo.canonicalFile

        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(File(realRepo, ".git/HEAD").path))
        assertEquals(WatchedChange.OTHER_WORKTREES, tab.changeOf(File(realRepo, ".git/worktrees/agent/HEAD").path))
        assertEquals(WatchedChange.REPOSITORY, tab.changeOf(File(link, ".git/HEAD").path))
        assertEquals(WatchedChange.WORKING_TREE, tab.changeOf(File(realRepo, "a.txt").path))
    }

    @Test
    fun `finds the folders of the repository's linked worktrees from their git file`() {
        val tab = mainTab
        val absolute = folderWithGitFile("absolute", "gitdir: ${File(commonDir, "worktrees/absolute").path}")
        val relative = folderWithGitFile("nested/relative", "gitdir: ../../.git/worktrees/relative")

        assertEquals(true, tab.isLinkedWorktreeFolder(absolute))
        assertEquals(true, tab.isLinkedWorktreeFolder(relative))
    }

    @Test
    fun `submodules, other repositories and plain folders aren't linked worktrees`() {
        val tab = mainTab
        val submodule = folderWithGitFile("lib", "gitdir: ../.git/modules/lib")
        val otherRepository = folderWithGitFile("other", "gitdir: ${path("elsewhere", ".git", "worktrees", "x")}")
        val worktreesFolderItself = folderWithGitFile("odd", "gitdir: ${File(commonDir, "worktrees").path}")
        val notAGitFile = folderWithGitFile("text", "something else")
        // git reads only a line that starts with "gitdir:"
        val noPrefix = folderWithGitFile("no-prefix", File(commonDir, "worktrees/no-prefix").path)
        val nestedRepository = File(repo, "clone/.git").apply { mkdirs() }.parentFile
        val plain = File(repo, "src").apply { mkdirs() }

        val folders = listOf(
            submodule, otherRepository, worktreesFolderItself, notAGitFile, noPrefix, nestedRepository, plain,
        )

        for (folder in folders) {
            assertEquals(false, tab.isLinkedWorktreeFolder(folder), folder.path)
        }
    }

    private fun folderWithGitFile(relativePath: String, content: String): File {
        val folder = File(repo, relativePath).apply { mkdirs() }
        File(folder, ".git").writeText("$content\n")
        return folder
    }
}
