// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.models.NestedRepository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * What the untracked folder at [path] holds, when it is another repository: it has a `.git` folder or file. A linked
 * worktree of this repository has a `.git` file that points into `<commonDir>/worktrees/`, as git writes it. Null for
 * a file, a link, a missing path, or a folder without `.git`.
 */
internal fun nestedRepositoryOf(workTree: File, commonDir: File, path: String): NestedRepository? {
    val folder = File(workTree, path).toPath()

    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
        return null
    }

    val dotGit = folder.resolve(".git")

    return when {
        Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS) -> NestedRepository.REPOSITORY
        Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS) ->
            if (pointsIntoWorktrees(dotGit.toFile(), commonDir)) NestedRepository.LINKED_WORKTREE
            else NestedRepository.REPOSITORY

        else -> null
    }
}

private fun pointsIntoWorktrees(dotGitFile: File, commonDir: File): Boolean = try {
    val line = dotGitFile.useLines { lines -> lines.firstOrNull() }.orEmpty()
    val target = line.removePrefix("gitdir:").takeIf { line.startsWith("gitdir:") }?.trim()

    if (target == null) {
        false
    } else {
        val gitDir = File(target).let { if (it.isAbsolute) it else File(dotGitFile.parentFile, target) }
        val worktreesDir = File(commonDir, "worktrees").canonicalFile

        gitDir.canonicalFile.parentFile == worktreesDir
    }
} catch (_: IOException) {
    false
}
