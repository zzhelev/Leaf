// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which open tab shows each worktree, for "Go to tab" in a worktree's menu, from folders laid out as git does. */
class WorktreeTabsTest {
    @TempDir
    lateinit var tempDir: File

    private val main by lazy { File(tempDir, "app").apply { File(this, ".git/worktrees/app-fix-login").mkdirs() } }
    private val linkedGitDir by lazy { File(main, ".git/worktrees/app-fix-login") }
    private val linked by lazy {
        File(tempDir, "app-fix-login").apply {
            mkdirs()
            File(this, ".git").writeText("gitdir: ${linkedGitDir.path}\n")
            File(linkedGitDir, "commondir").writeText("../..\n")
            File(linkedGitDir, "gitdir").writeText("${File(this, ".git").path}\n")
        }
    }

    @Test
    fun `a worktree gets every tab that shows it, in their order, by git dir or folder`() {
        val tabs = listOf(
            OpenTab(path = null, title = "New tab", tab = 0),
            OpenTab(path = linkedGitDir.path, title = "review", tab = 1),
            OpenTab(path = File(main, ".git").path, title = "app", tab = 2),
            OpenTab(path = linked.path, title = "app · app-fix-login", tab = 3),
        )

        val shown = tabsOfWorktrees(listOf(main.path, linked.path), tabs)

        assertEquals(listOf(2), shown[main.path]?.map { it.tab })
        assertEquals(listOf(1, 3), shown[linked.path]?.map { it.tab })
    }

    @Test
    fun `a worktree that no tab shows is left out`() {
        val other = File(tempDir, "other").apply { File(this, ".git").mkdirs() }
        val tabs = listOf(OpenTab(File(main, ".git").path, "app", tab = 0), OpenTab(other.path, "other", tab = 1))

        assertEquals(setOf(main.path), tabsOfWorktrees(listOf(main.path, linked.path), tabs).keys)
        assertEquals(emptyMap<String, List<OpenTab<Int>>>(), tabsOfWorktrees(listOf(main.path), emptyList<OpenTab<Int>>()))
    }
}
