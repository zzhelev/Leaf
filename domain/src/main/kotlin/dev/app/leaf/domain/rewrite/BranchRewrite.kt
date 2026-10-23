// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

/**
 * A rewrite of a local branch's own commits that keeps every commit's tree (fork-only): commits joined into one
 * (squash, fixup) and messages changed (reword). The commits after them are made again with their own trees, so the
 * branch's last tree doesn't change, nothing can conflict, and the working tree and the index stay as they are. It's
 * what `git rebase -i` gives for the same todo, without replaying anything.
 *
 * @param branch the branch's full name (`refs/heads/x`).
 * @param expectedTip the commit the branch was at when the rewrite was planned. A branch that moved since is refused.
 * @param groups oldest first, each a run of commits one after the other on the branch's first-parent line.
 * @param kind what the reflog says the rewrite did.
 */
data class BranchRewrite(
    val branch: String,
    val expectedTip: String,
    val groups: List<RewriteGroup>,
    val kind: RewriteKind,
)

/**
 * Commits of a branch's line that become one commit, oldest first, with [message], or the oldest commit's own when it's
 * null. One commit with a message is a reword. The new commit has the tree of the newest and the author of the oldest,
 * as git's squash and fixup keep.
 */
data class RewriteGroup(
    val commits: List<String>,
    val message: String?,
)

/** What a rewrite did, as the reflog names it (`squash: <subject>`). */
enum class RewriteKind(val reflogName: String) {
    SQUASH("squash"),
    FIXUP("fixup"),
    REWORD("reword"),
}

/**
 * What a rewrite changed: the branch's tip before ([oldTip]) and after ([newTip]), and the new commit of each old one
 * from the first that changed, oldest first, as `post-rewrite` gets them. A squash's commits all map to the commit they
 * became.
 */
data class BranchRewriteResult(
    val branch: String,
    val kind: RewriteKind,
    val oldTip: String,
    val newTip: String,
    val rewritten: List<Pair<String, String>>,
)
