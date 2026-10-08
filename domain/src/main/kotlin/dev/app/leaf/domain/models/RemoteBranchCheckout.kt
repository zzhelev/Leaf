// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** What checking out a remote branch does, which depends on the local branch with the same name. */
sealed interface RemoteBranchCheckout {
    /** No local branch has the remote branch's name, so checking it out creates one that tracks it. */
    data object CreatesLocalBranch : RemoteBranchCheckout

    /**
     * The local branch [localBranch] exists, so checking out the remote branch checks it out instead, as
     * `git switch <name>` does. [commitsAhead] of its commits aren't on the remote branch, and [commitsBehind] of the
     * remote branch's commits aren't on it.
     */
    data class ChecksOutLocalBranch(
        val localBranch: String,
        val isCurrentBranch: Boolean,
        val commitsAhead: Int,
        val commitsBehind: Int,
    ) : RemoteBranchCheckout {
        /** The local branch is behind the remote one and has no commits of its own, so it can move to it. */
        val canFastForward: Boolean
            get() = commitsBehind > 0 && commitsAhead == 0
    }
}
