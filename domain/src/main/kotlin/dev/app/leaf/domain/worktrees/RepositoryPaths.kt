// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.extensions.removeGitSuffix
import dev.app.leaf.domain.models.WorktreeList
import java.io.File
import java.io.IOException

private const val DOT_GIT = ".git"
private const val GIT_DIR_PREFIX = "gitdir:"

/** The file in a linked worktree's git dir that points to the git dir that the worktrees share. */
private const val COMMON_DIR_FILE = "commondir"

/** The file in a linked worktree's git dir that points to the worktree's `.git` file. */
private const val GIT_DIR_FILE = "gitdir"

/** The folder of the git dir that the worktrees share, which holds the linked worktrees' git dirs. */
private const val WORKTREES_DIR = "worktrees"

/**
 * The git dir that the `.git` file in [dir] points to (`gitdir: <path>`), as linked worktrees and submodules have, or
 * null when [dir] has no such file. git writes a path relative to [dir] with `worktree.useRelativePaths`.
 */
fun gitDirFromDotGitFile(dir: File): File? {
    val target = readPath(File(dir, DOT_GIT), prefix = GIT_DIR_PREFIX) ?: return null

    return if (target.isAbsolute) target else File(dir, target.path)
}

/**
 * The git dir of the repository at [path], which tells whether two paths show the same worktree: the `.git` folder of
 * a working tree, the git dir that its `.git` file points to, or else [path] itself, taken as a git dir. It's the real
 * path when there is one, since a folder can have several paths (macOS has `/var` in `/private/var`).
 */
fun gitDirOf(path: String): String {
    val dir = File(path)
    val dotGit = File(dir, DOT_GIT)

    val gitDir = when {
        dotGit.isDirectory -> dotGit
        else -> gitDirFromDotGitFile(dir) ?: dir
    }

    return realPath(gitDir)
}

/**
 * This list with the worktree in [folder] marked as the tab's (fork-only), as git lists it once the tab shows it. A
 * switch marks it right away, rather than when the next refresh has run git in every worktree.
 */
fun WorktreeList.withCurrentWorktree(folder: String): WorktreeList {
    val current = realPath(File(folder))

    return copy(
        worktrees = worktrees.map { info ->
            info.copy(worktree = info.worktree.copy(isCurrent = realPath(File(info.worktree.path)) == current))
        },
    )
}

/** The real path of [file], or its absolute normalized path when it can't be found. */
private fun realPath(file: File): String = try {
    file.canonicalPath
} catch (e: IOException) {
    file.absoluteFile.toPath().normalize().toString()
}

/**
 * The index of the first of [tabPaths] that has the same repository as [path] (see [gitDirOf]), or -1 when none has.
 * The paths can be git dirs or working trees, and a null path never matches.
 */
fun indexOfRepository(path: String, tabPaths: List<String?>): Int {
    val gitDir = gitDirOf(path)

    return tabPaths.indexOfFirst { it != null && gitDirOf(it) == gitDir }
}

/** Whether [gitDir] is a linked worktree's git dir (`<common git dir>/worktrees/<name>`): it has a `commondir` file. */
fun isLinkedWorktreeGitDir(gitDir: String): Boolean = File(gitDir, COMMON_DIR_FILE).isFile

/**
 * The working tree of the repository that a tab opened from [path], a git dir or a working tree: for a linked
 * worktree's git dir, the folder whose `.git` file its `gitdir` file names, and otherwise [path] without a final
 * `.git`. A linked worktree's git dir is kept as it is when its `gitdir` file can't be read.
 */
fun workTreeOf(path: String): String {
    if (!isLinkedWorktreeGitDir(path)) return path.removeGitSuffix()

    // git writes a path relative to the git dir with worktree.useRelativePaths
    val dotGit = readPath(File(path, GIT_DIR_FILE), prefix = "")
        ?.let { if (it.isAbsolute) it else File(path, it.path) }
        ?: return path

    return dotGit.toPath().normalize().parent?.toString() ?: path
}

/**
 * What a tab names itself after (fork-only): its [repository], and the [worktree] it shows when that's a linked one.
 */
data class TabNames(val repository: String, val worktree: String?)

/**
 * The names of a tab opened from [path], a git dir or a working tree. A linked worktree's tab is named after its
 * repository, the folder of the main worktree (a bare repository's folder, without `.git`), and after its own folder.
 * Any other tab is named after its working tree's folder, as before worktrees.
 */
fun tabNamesOf(path: String): TabNames {
    val gitDir = gitDirOf(path)
    val commonGitDir = if (isLinkedWorktreeGitDir(gitDir)) commonGitDirOf(gitDir) else null

    if (commonGitDir == null) {
        return TabNames(File(workTreeOf(path)).name, worktree = null)
    }

    val repository = if (commonGitDir.name == DOT_GIT) {
        commonGitDir.parentFile?.name ?: commonGitDir.name
    } else {
        commonGitDir.name.removeSuffix(DOT_GIT)
    }

    return TabNames(repository, File(workTreeOf(gitDir)).name)
}

/** The git dir that a linked worktree's git dir names in its `commondir` file, usually `../..`. */
private fun commonGitDirOf(gitDir: String): File? = readPath(File(gitDir, COMMON_DIR_FILE), prefix = "")
    ?.let { if (it.isAbsolute) it else File(gitDir, it.path) }
    ?.toPath()
    ?.normalize()
    ?.toFile()

/**
 * The git dir that the worktrees share, when [path] is the git dir of a linked worktree that was removed, or else
 * null. Removed means that git pruned its git dir, or that its folder is gone (git lists it as prunable). git keeps a
 * linked worktree's git dir in `<common git dir>/worktrees/<id>`, so it's found from [path] alone.
 */
fun repositoryOfRemovedWorktree(path: String): String? {
    val gitDir = File(path)
    val worktreesDir = gitDir.parentFile?.takeIf { it.name == WORKTREES_DIR } ?: return null
    val commonGitDir = worktreesDir.parentFile?.takeIf { it.isGitDir() } ?: return null

    val isRemoved = !gitDir.isDirectory || !File(workTreeOf(path)).isDirectory

    return commonGitDir.path.takeIf { isRemoved }
}

/** Whether this folder looks like a git dir: it has a `HEAD` file and an `objects` folder, as git checks. */
private fun File.isGitDir(): Boolean = File(this, "HEAD").isFile && File(this, "objects").isDirectory

/** The path on the first line of [file], after [prefix], or null when the file can't be read or has no such line. */
private fun readPath(file: File, prefix: String): File? {
    if (!file.isFile) return null

    val firstLine = try {
        file.useLines { it.firstOrNull() }
    } catch (e: IOException) {
        null
    } ?: return null

    if (!firstLine.startsWith(prefix)) return null

    val path = firstLine.removePrefix(prefix).trim()

    return if (path.isEmpty()) null else File(path)
}
