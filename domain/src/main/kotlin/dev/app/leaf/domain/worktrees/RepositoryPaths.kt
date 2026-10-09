// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.extensions.removeGitSuffix
import java.io.File
import java.io.IOException

private const val DOT_GIT = ".git"
private const val GIT_DIR_PREFIX = "gitdir:"

/** The file in a linked worktree's git dir that points to the git dir that the worktrees share. */
private const val COMMON_DIR_FILE = "commondir"

/** The file in a linked worktree's git dir that points to the worktree's `.git` file. */
private const val GIT_DIR_FILE = "gitdir"

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

    return try {
        gitDir.canonicalPath
    } catch (e: IOException) {
        gitDir.absoluteFile.toPath().normalize().toString()
    }
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
