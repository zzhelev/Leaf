// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.domain.GitConstants
import dev.app.leaf.domain.RebaseConstants
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.worktrees.gitDirFromDotGitFile
import java.io.File
import java.io.IOException

/** What a change that the file watcher reports means for a tab. */
enum class WatchedChange {
    /**
     * Leaf's own files and commit drafts, JGit's probe files, git's shared index (split index) and commit messages being
     * edited: nothing to refresh.
     */
    NONE,

    /** The tab's git dir, or the refs and config that every worktree shares. */
    REPOSITORY,

    /** Another worktree's git dir, or the main worktree's `index` and other files in a linked worktree's tab. */
    OTHER_WORKTREES,

    /**
     * Another worktree's `HEAD`, or a worktree added or removed: its commit moved. With a detached HEAD, which no ref
     * points to, the log starts from that commit. A commit on a branch changes the branch's ref, not `HEAD`, and git
     * rewrites `index` all the time, so neither is this.
     */
    OTHER_WORKTREE_HEAD,

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
        // A linked worktree's git dir, <common git dir>/worktrees/<name>
        val isInWorktreeGitDir = File(path).parentFile?.parent in worktreesDirs
        // The rebase's folder in any worktree's git dir
        val rebaseDir = File(path).parentFile?.takeIf { it.name == RebaseConstants.REBASE_MERGE }
        val isInRebaseDir = rebaseDir?.parent.let { it != null && (it in gitDirs || it in commonDirs) } ||
            rebaseDir?.parentFile?.parent in worktreesDirs

        return when {
            (isInGitDir || isInCommonDir) && (name.startsWith(PROBE_FILE_PREFIX) || name in LEAF_FILES) ->
                WatchedChange.NONE

            // In any worktree's git dir: each git status touches it, as the worktree list runs one in each
            (isInGitDir || isInCommonDir || isInWorktreeGitDir) && name.startsWith(SHARED_INDEX_PREFIX) ->
                WatchedChange.NONE

            // Any worktree's commit draft, saved a second after each edit in a tab on it. It isn't git's state, and
            // the worktree list doesn't show it.
            (isInGitDir || isInCommonDir || isInWorktreeGitDir) && name == AppStorage.COMMIT_DRAFT_FILE_NAME ->
                WatchedChange.NONE

            // Any worktree's message for a rebase's edit stop, saved like the draft
            isInRebaseDir && name.startsWith(AppStorage.REBASE_EDIT_MESSAGE_FILE_PREFIX) -> WatchedChange.NONE

            isInGitDir && name in EDITED_MESSAGE_FILES -> WatchedChange.NONE
            isLinkedWorktree && path.isIn(gitDirs) -> WatchedChange.REPOSITORY
            // Another worktree's HEAD. Its git dir being created or removed counts too: on Linux the files of a new
            // folder can go unreported before it's watched, and the folder's own change is what's left.
            isInWorktreeGitDir && name == HEAD_FILE -> WatchedChange.OTHER_WORKTREE_HEAD
            parent in worktreesDirs -> WatchedChange.OTHER_WORKTREE_HEAD
            path.isIn(worktreesDirs) -> WatchedChange.OTHER_WORKTREES
            path.isIn(gitDirs) -> WatchedChange.REPOSITORY
            isLinkedWorktree && (path.isIn(sharedRefsDirs) || path in sharedFiles) -> WatchedChange.REPOSITORY
            // The main worktree's HEAD, index and other files
            isLinkedWorktree && isInCommonDir && name == HEAD_FILE -> WatchedChange.OTHER_WORKTREE_HEAD
            isLinkedWorktree && path.isIn(commonDirs) -> WatchedChange.OTHER_WORKTREES
            else -> WatchedChange.WORKING_TREE
        }
    }

    /**
     * What to refresh for a batch of changed [paths]: nothing, everything, the working tree, or the worktrees. Another
     * worktree's HEAD moving also refreshes the log. A result with [DataToRefresh.WORKTREES] comes from the other
     * worktrees alone, and is the caller's to throttle ([WorktreeChangesRefresher]): the log's refresh lists the
     * worktrees again after it.
     */
    fun dataToRefresh(paths: List<String>): List<DataToRefresh> {
        val changes = paths.mapTo(mutableSetOf(), ::changeOf)

        return when {
            WatchedChange.REPOSITORY in changes -> listOf(DataToRefresh.ALL)
            // Refreshes the worktrees too
            WatchedChange.WORKING_TREE in changes ->
                listOf(DataToRefresh.STATUS, DataToRefresh.LOG, DataToRefresh.REPO_STATE)

            WatchedChange.OTHER_WORKTREE_HEAD in changes -> listOf(DataToRefresh.LOG, DataToRefresh.WORKTREES)
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
        const val HEAD_FILE = "HEAD"

        /** JGit creates these to measure the file system's timestamps. */
        const val PROBE_FILE_PREFIX = ".probe-"

        /**
         * A split index's shared index, `sharedindex.<id>`, and git's temporary files for it. git touches it whenever it
         * reads the index, to keep it from expiring, and writes the `index` file when the index changes.
         */
        const val SHARED_INDEX_PREFIX = "sharedindex"

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
