// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

/**
 * A [tab], by the path that it has open or was opened with (none for a Welcome page), and its title as it shows it.
 */
data class OpenTab<T>(val path: String?, val title: String, val tab: T)

/**
 * The tabs that show each of [worktreePaths], in the order of [tabs] (fork-only), for a worktree's "Go to tab". Tabs and
 * worktrees are compared by git dir ([gitDirOf]), which reads the disk. A worktree that no tab shows is left out.
 */
fun <T> tabsOfWorktrees(worktreePaths: List<String>, tabs: List<OpenTab<T>>): Map<String, List<OpenTab<T>>> {
    val tabGitDirs = tabs.map { tab -> tab.path?.let { gitDirOf(it) } }

    return worktreePaths.mapNotNull { path ->
        val gitDir = gitDirOf(path)
        val showing = tabs.filterIndexed { index, _ -> tabGitDirs[index] == gitDir }

        if (showing.isEmpty()) null else path to showing
    }.toMap()
}
