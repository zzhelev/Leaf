// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * The remote branch [branch] (`origin/feature`, the remote-tracking branch [trackingBranch]) wasn't deleted: the server
 * has another commit on it, [now] (null when Leaf couldn't tell), than the one Leaf showed, [was], as someone pushed to
 * it since the last fetch. The error dialog then asks whether to delete it anyway, that commit being the lease.
 */
data class RemoteBranchChangedError(
    val branch: String,
    val trackingBranch: String = "refs/remotes/$branch",
    val now: String? = null,
    val was: String? = null,
) : GitError
