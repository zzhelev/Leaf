// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Errors of opening a file in an editor, or showing it in the file manager (fork-only). */
sealed interface EditorError : AppError {
    /** The editor [name] was chosen, and Leaf doesn't find it on this computer any more. */
    data class NotInstalled(val name: String) : EditorError

    /** The working tree has no file at [path], such as one that a commit changed and that was deleted since. */
    data class FileMissing(val path: String) : EditorError

    /** Settings › Editor says to run a command of the user's own, and it's empty. */
    data object NoCustomCommand : EditorError

    /**
     * [command] couldn't start, or ended at once with [exitCode] (null when it couldn't start), saying [output].
     */
    data class Failed(val command: String, val exitCode: Int?, val output: String) : EditorError
}
