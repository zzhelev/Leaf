// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Writing a hunk edited in place into its file (fork-only, `docs/fork/inline-editing.md`) failed. Nothing was written. */
sealed interface HunkEditError : GitError {
    /** The file, relative to the working tree. */
    val path: String

    /** The file no longer has the lines that the diff showed: something changed it after the diff was loaded. */
    data class FileChanged(override val path: String) : HunkEditError

    /** Nothing that Leaf writes lines into is there: the file is gone, or a folder or a symbolic link is in its place. */
    data class NotAFile(override val path: String) : HunkEditError

    /** The file has more lines than the whole file's editor takes ([lineCount] of them, B2). */
    data class TooLong(override val path: String, val lineCount: Int, val maxLines: Int) : HunkEditError

    /** The file couldn't be written, with the system's [message]. */
    data class WriteFailed(override val path: String, val message: String) : HunkEditError
}
