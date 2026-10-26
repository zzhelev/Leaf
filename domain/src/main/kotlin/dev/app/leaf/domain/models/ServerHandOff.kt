// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * What git has handed a server that goes on with it whatever git does next (fork-only): a push once git has sent all of
 * it, or a branch's delete, which is only a command. Stopping git then doesn't stop the server (probed with git 2.54 and
 * `git daemon`: a push killed after git's `Total` line was taken, one killed while it wrote its objects wasn't), so Leaf
 * asks the server afterwards what it did ([dev.app.leaf.domain.remote.StoppedWaitingCheck]).
 *
 * @param remote the remote that git pushed to.
 * @param serverRef the branch on the server, `refs/heads/x`.
 * @param trackingRef the remote-tracking branch that git sets after the push, `refs/remotes/origin/x`, or null when the
 * remote's fetch refspec maps none.
 * @param pushed the commit pushed, or null for a delete.
 * @param before what [trackingRef] had before git ran: a delete's lease, or for a push, the commit that it moves from.
 * @param upstreamOf the local branch, `refs/heads/x`, whose upstream the push sets (`--set-upstream`), if it does.
 */
data class ServerHandOff(
    val remote: String,
    val serverRef: String,
    val trackingRef: String?,
    val pushed: String?,
    val before: String?,
    val upstreamOf: String? = null,
) {
    val isDelete: Boolean get() = pushed == null

    /** The branch's name on the server, `x`. */
    val branchName: String get() = serverRef.removePrefix("refs/heads/")
}
