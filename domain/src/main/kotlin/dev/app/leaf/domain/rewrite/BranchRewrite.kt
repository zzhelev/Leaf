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
 * @param onto for a squash of the whole branch into one commit, the base's commit to put it on, in place of where the
 * branch left the base (step A4 of `docs/fork/squash-and-rebase.md`). The commit's tree is then the merge of the base and
 * the branch, as `git merge-tree` makes it, so the branch's tree changes, and a checked-out branch's working tree takes
 * the base's changes.
 */
data class BranchRewrite(
    val branch: String,
    val expectedTip: String,
    val groups: List<RewriteGroup>,
    val kind: RewriteKind,
    val onto: OntoBase? = null,
)

/** The commit of the base branch [base] (`refs/heads/main`, or a remote one) that a squashed branch goes on. */
data class OntoBase(
    val base: String,
    val commit: String,
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

    /** A commit editor's plan, run without a working tree (step B3 of `docs/fork/commit-editor.md`). */
    REBASE("rebase"),
}

/**
 * What a rewrite changed: the branch's tip before ([oldTip]) and after ([newTip]), and the new commit of each old one
 * from the first that changed, oldest first, as `post-rewrite` gets them. A squash's commits all map to the commit they
 * became. [onto] is the base the commit went on, for a squash onto the latest base.
 */
data class BranchRewriteResult(
    val branch: String,
    val kind: RewriteKind,
    val oldTip: String,
    val newTip: String,
    val rewritten: List<Pair<String, String>>,
    val onto: OntoBase? = null,
)
