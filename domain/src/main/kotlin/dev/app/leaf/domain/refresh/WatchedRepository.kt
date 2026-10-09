// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.domain.GitConstants
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.worktrees.gitDirFromDotGitFile
import java.io.File
import java.io.IOException

/** What a change that the file watcher reports means for a tab. */
enum class WatchedChange {
    /** Leaf's own files, JGit's probe files and commit messages being edited: nothing to refresh. */
    NONE,

    /** The tab's git dir, or the refs and config that every worktree shares. */
    REPOSITORY,

    /** Another worktree's git dir, or the main worktree's `HEAD` and `index` in a linked worktree's tab. */
    OTHER_WORKTREES,

    /** Anything else: the tab's working tree. */
    WORKING_TREE,
}

/**
 * The folders of a tab's repository that its file watcher reports changes in.
 *
 * Paths are compared both as given and as real paths, since macOS reports real paths (`/private/var/...` for
 * `/var/...`).
 *
 * @param gitDir the tab's git dir: `<common git dir>/worktrees/<name>` for a linked worktree.
 * @param commonDir the git dir that the worktrees share, the same as [gitDir] for the main worktree.
 */
class WatchedRepository(gitDir: File, commonDir: File) {
    private val gitDirs = pathForms(gitDir)
    private val commonDirs = pathForms(commonDir)
    private val worktreesDirs = pathForms(File(commonDir, WORKTREES_DIR))
    private val sharedRefsDirs = pathForms(File(commonDir, REFS_DIR))
    private val sharedFiles = SHARED_FILES.flatMap { pathForms(File(commonDir, it)) }.toSet()

    /** Whether the tab is on a linked worktree, whose git dir isn't the one that the worktrees share. */
    val isLinkedWorktree: Boolean = gitDirs.none { it in commonDirs }

    /** The folder that has a git dir for each linked worktree. git creates it with the first one. */
    val worktreesDir: File = File(commonDir, WORKTREES_DIR)

    fun changeOf(path: String): WatchedChange {
        val parent = File(path).parent
        val name = File(path).name
        val isInGitDir = parent in gitDirs
        val isInCommonDir = parent in commonDirs

        return when {
            (isInGitDir || isInCommonDir) && (name.startsWith(PROBE_FILE_PREFIX) || name in LEAF_FILES) ->
                WatchedChange.NONE

            isInGitDir && name in EDITED_MESSAGE_FILES -> WatchedChange.NONE
            isLinkedWorktree && path.isIn(gitDirs) -> WatchedChange.REPOSITORY
            path.isIn(worktreesDirs) -> WatchedChange.OTHER_WORKTREES
            path.isIn(gitDirs) -> WatchedChange.REPOSITORY
            isLinkedWorktree && (path.isIn(sharedRefsDirs) || path in sharedFiles) -> WatchedChange.REPOSITORY
            // The main worktree's HEAD, index and other files
            isLinkedWorktree && path.isIn(commonDirs) -> WatchedChange.OTHER_WORKTREES
            else -> WatchedChange.WORKING_TREE
        }
    }

    /** What to refresh for a batch of changed [paths]: nothing, everything, the working tree, or the worktrees. */
    fun dataToRefresh(paths: List<String>): List<DataToRefresh> {
        val changes = paths.mapTo(mutableSetOf(), ::changeOf)

        return when {
            WatchedChange.REPOSITORY in changes -> listOf(DataToRefresh.ALL)
            // Refreshes the worktrees too
            WatchedChange.WORKING_TREE in changes ->
                listOf(DataToRefresh.STATUS, DataToRefresh.LOG, DataToRefresh.REPO_STATE)

            WatchedChange.OTHER_WORKTREES in changes -> listOf(DataToRefresh.WORKTREES)
            else -> emptyList()
        }
    }

    /** Whether [path] is the folder of the linked worktrees' git dirs, which the watcher adds once it exists. */
    fun isWorktreesDir(path: String): Boolean = path in worktreesDirs

    /**
     * Whether [dir] is a working tree of one of this repository's linked worktrees: its `.git` file points into
     * [worktreesDir]. Agents often put them inside the main working tree (`.claude/worktrees/<name>`), where the tab
     * would watch every folder they have. Submodules' `.git` files point into `modules`, so they don't count.
     */
    fun isLinkedWorktreeFolder(dir: File): Boolean {
        val gitDir = gitDirFromDotGitFile(dir) ?: return false

        return pathForms(gitDir).any { it.isIn(worktreesDirs) && it !in worktreesDirs }
    }

    private fun String.isIn(dirs: Set<String>): Boolean = dirs.any { dir ->
        this == dir || startsWith(dir + File.separator)
    }

    private companion object {
        const val WORKTREES_DIR = "worktrees"
        const val REFS_DIR = "refs"

        /** JGit creates these to measure the file system's timestamps. */
        const val PROBE_FILE_PREFIX = ".probe-"

        /** What every worktree shares besides `refs`, which changes its refs or what it shows. */
        val SHARED_FILES = listOf("packed-refs", "config")

        /** Leaf's settings for the repository, and JGit's lock file while it saves them. */
        val LEAF_FILES = setOf(AppStorage.REPOSITORY_CONFIG_FILE_NAME, "${AppStorage.REPOSITORY_CONFIG_FILE_NAME}.lock")

        val EDITED_MESSAGE_FILES = setOf(GitConstants.COMMIT_MSG, GitConstants.MERGE_MSG, GitConstants.SQUASH_MSG)

        fun pathForms(file: File): Set<String> {
            val absolute = file.absoluteFile.toPath().normalize().toString()
            val real = try {
                file.canonicalPath
            } catch (e: IOException) {
                absolute
            }

            return setOf(absolute, real)
        }
    }
}
