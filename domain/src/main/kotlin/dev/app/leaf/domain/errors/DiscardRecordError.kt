// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Keeping what a discard throws away, or bringing it back (fork-only, upstream issue 39). */
sealed interface DiscardRecordError : GitError {
    /** Leaf couldn't keep a copy of [path] ([reason]), so the discard didn't run, and nothing changed. */
    data class NotKept(val path: String, val reason: String) : DiscardRecordError

    /** The discard was undone already, or is older than Leaf keeps them, or its folder was removed. */
    data object NotFound : DiscardRecordError

    /** Every file of the discard changed since, in ways that Leaf can't merge, so Undo changed nothing. */
    data class ChangedSince(val paths: List<String>) : DiscardRecordError
}
