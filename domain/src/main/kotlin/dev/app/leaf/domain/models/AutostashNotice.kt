// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * git set the user's uncommitted changes aside before a merge or a rebase (`merge.autoStash`, `rebase.autoStash`), and
 * putting them back didn't work, so they're in the stash list as "autostash" (fork-only). The commit or abort that put
 * them back succeeded, so this isn't an error, and the tab shows it in a dialog rather than a toast.
 *
 * @param conflicts the files where putting them back left conflicts. Empty when git refused to put them back, as
 * other changes were in the way, and changed nothing.
 */
data class AutostashNotice(
    val setAsideFor: AutostashOperation,
    val putBackAfter: AutostashPutBackAfter,
    val conflicts: List<String>,
    val date: Long = System.currentTimeMillis(),
)

/** What git set the changes aside for. */
enum class AutostashOperation {
    MERGE,
    REBASE,
}

/** When the changes were put back. */
enum class AutostashPutBackAfter {
    /** The merge or rebase itself ended. */
    OPERATION,
    COMMIT,
    ABORT,
}

/** What putting back a merge's autostash did (fork-only). */
sealed interface AutostashPutBack {
    /** There was none. */
    data object None : AutostashPutBack

    data object Applied : AutostashPutBack

    /** It didn't apply, and is in the stash list as "autostash" ([AutostashNotice.conflicts]). */
    data class Stored(val conflicts: List<String>) : AutostashPutBack
}
