// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.lib.Constants
import java.io.File
import java.io.IOException

/**
 * Keeps a linked worktree's HEAD reflog in its own git dir, for Leaf's JGit file systems ([PosixFs], [WindowsFs]).
 *
 * git keeps the logs of HEAD and of the other refs outside `refs/` per worktree, in `<git dir>/logs`. JGit 7.7 writes
 * them to the common git dir's `logs`, the main worktree's, while it reads HEAD's log from the git dir: a commit or a
 * checkout in a linked worktree showed up in the main worktree's `git reflog`, `@{-1}` and `HEAD@{1}` instead of the
 * linked worktree's (`docs/fork/architecture-notes.md` §6). JGit's `RefDirectory` gets that folder from
 * `FS.resolve(<common git dir>, "logs")`, so a file system answers that call with the worktree's own folder
 * ([resolve]). Branch logs are in `logs/refs/`, which JGit resolves with another call, and stay shared, as in git.
 *
 * A file system with one belongs to that worktree's repository: another worktree of the same repository, opened with
 * it, would log its HEAD in this worktree's reflog.
 */
class LinkedWorktreeLogs private constructor(
    private val gitDir: File,
    private val commonDir: File,
) {
    /** The linked worktree's `logs` folder when JGit asks for `logs` in the common git dir, or else null. */
    fun resolve(dir: File?, name: String): File? {
        if (name != Constants.LOGS || dir == null) return null

        val canonicalDir = try {
            dir.canonicalFile
        } catch (_: IOException) {
            return null
        }

        return if (canonicalDir == commonDir) File(gitDir, Constants.LOGS) else null
    }

    companion object {
        /**
         * For the git dir of a linked worktree, which has a `commondir` file, or null for any other git dir. The common
         * git dir is read as JGit's `FS.getCommonDir` reads it, and compared as a canonical path.
         */
        fun of(gitDir: File): LinkedWorktreeLogs? {
            val commonDirFile = File(gitDir, Constants.COMMONDIR_FILE)

            if (!commonDirFile.isFile) return null

            val path = commonDirFile.readText().trim()
            val commonDir = File(path).takeIf { it.isAbsolute } ?: File(gitDir, path)

            return LinkedWorktreeLogs(gitDir.absoluteFile, commonDir.canonicalFile)
        }
    }
}
