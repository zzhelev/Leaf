// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * Why a file's version from a commit can't be restored into the working tree ("Restore this version of the file" in
 * file history). Everything but [GitFailed] is known before git runs, and changes nothing.
 */
sealed interface RestoreFileError : GitError {
    /** A bare repository has no working tree to write into. */
    data object NoWorkTree : RestoreFileError

    /**
     * The commit has no file at [path]: it deleted it, or moved it elsewhere (file history doesn't follow renames, so
     * the commit that moved a file is the newest row of the old name's history).
     */
    data class NotInCommit(val path: String, val commit: String) : RestoreFileError

    /** [path] has conflicts. `git restore --source` would write over the file with the markers in it, and say nothing. */
    data class Conflicted(val path: String) : RestoreFileError

    /** A folder is where the file goes. git deletes it, with the untracked files in it. */
    data class FolderInPlace(val path: String) : RestoreFileError

    /** [parent], a file or a link, is where a folder of [path] goes. git replaces it, with its unsaved edits. */
    data class ParentNotFolder(val path: String, val parent: String) : RestoreFileError

    /**
     * git failed with [output]. Leaf puts the file back as it was, as git leaves it deleted or empty when it can't
     * download the version (a partial clone offline) or its filter fails (Git LFS). When that fails too, the original
     * is at [keptAt].
     */
    data class GitFailed(val path: String, val output: String, val keptAt: String? = null) : RestoreFileError
}
