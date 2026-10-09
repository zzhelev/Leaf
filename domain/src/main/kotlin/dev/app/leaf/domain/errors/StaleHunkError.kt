// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** What a hunk or line action does with the lines of a diff. */
enum class HunkAction {
    /** Stages them: applies the unstaged diff's lines to the index. */
    Stage,

    /** Unstages them: reverts the staged diff's lines in the index. */
    Unstage,

    /** Discards them: reverts the unstaged diff's lines in the working tree. */
    Discard,
}

/**
 * git refused to [action] lines of the file at [path], as the file, or its staged version for [HunkAction.Stage] and
 * [HunkAction.Unstage], no longer has the lines that the diff showed: something changed it after the diff was loaded.
 * Nothing changed. [output] is what git printed.
 */
data class StaleHunkError(val action: HunkAction, val path: String, val output: String) : GitError
