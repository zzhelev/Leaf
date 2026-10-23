// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.LineRange
import dev.app.leaf.domain.rewrite.RewriteKind
import dev.app.leaf.domain.rewrite.shortName

/**
 * What the strip under the toolbar says after a branch's commits were rewritten (fork-only, step A3 of
 * `docs/fork/squash-and-rebase.md`), until it's closed or the next rewrite: what was done and where, that the remote
 * still has the old commits, and Undo.
 *
 * @param commit the short hash that a reword or a fixup names: the commit reworded, or the one fixed up into its parent.
 * @param joined how many commits a squash or a fixup joined.
 * @param pushedTo the upstream, by its short name, that still has commits that changed. Null when none changed.
 * @param canPush whether the branch is the tab's own, which "Push with force…" pushes.
 * @param isUndone after Undo, which put the branch back.
 */
data class RewriteNotice(
    val result: BranchRewriteResult,
    val commit: String,
    val joined: Int,
    val pushedTo: String?,
    val canPush: Boolean,
    val isUndone: Boolean = false,
) {
    val branchName: String get() = shortName(result.branch)

    companion object {
        /** The notice of [result], the rewrite of [range]; [currentBranch] is the tab's. */
        fun of(result: BranchRewriteResult, range: LineRange, currentBranch: String?): RewriteNotice {
            val named = if (result.kind == RewriteKind.FIXUP) range.commits.last() else range.commits.first()
            val upstream = range.line.upstream?.takeIf { range.rewrittenPushed > 0 }

            return RewriteNotice(
                result = result,
                commit = named.shortHash,
                joined = range.commits.size,
                pushedTo = upstream?.let(::shortName),
                canPush = upstream != null && result.branch == currentBranch,
            )
        }
    }
}
