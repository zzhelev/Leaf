// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreesUntouchedDays

private const val DAY_IN_MS = 24 * 60 * 60 * 1000L

/**
 * The branch whose work Leaf checks for being in [baseBranch] (Phase 4.3a): the one a linked worktree has checked out,
 * when it's neither [baseBranch] nor the base's local branch (`main` for `origin/main`). Null otherwise: a detached
 * worktree isn't marked, nor one that rebases or bisects.
 */
fun Worktree.branchToMarkMerged(baseBranch: String?): String? {
    val branch = branch

    return when {
        branch == null || isMain || isBare || baseBranch == null || branch == baseBranch -> null
        mergeTarget(baseBranch, listOf(branch)) == branch -> null
        else -> branch
    }
}

/** An entry of a branch's reflog: the commit that it moved the branch to, and its message. */
data class BranchReflogEntry(val newId: String, val comment: String)

/**
 * Messages of the commands that make a commit on the branch, git's and JGit's: `commit` (also `commit (amend)`,
 * `(merge)` and `(initial)`), `cherry-pick`, `revert` and `am`.
 */
private val COMMIT_MESSAGE_PREFIXES = listOf("commit:", "commit (", "cherry-pick:", "revert:", "am:")

/**
 * The end of a rebase: git's `rebase (finish): refs/heads/x onto <id>` (`rebase -i (finish)` for an interactive one)
 * and JGit's `rebase finished: refs/heads/x onto <id>`.
 */
private val REBASE_FINISHED = Regex("""^rebase(?: -i)? (?:\(finish\)|finished): \S+ onto ([0-9a-f]{40,64})$""")

/**
 * The commits that a branch got as work of its own, as its reflog's [entries] tell, in their order: a commit made on
 * it, or a rebase that kept commits on top of the commit it rebased onto. A merge, a pull or a reset brings no work of
 * the branch's own, nor does a rebase that only moved the branch to the commit it rebased onto, as "Rebase onto main"
 * does with a fresh worktree.
 */
fun commitsOfItsOwn(entries: List<BranchReflogEntry>): List<String> = entries
    .filter { entry ->
        val comment = entry.comment
        val onto = REBASE_FINISHED.find(comment)?.groupValues?.get(1)

        when {
            onto != null -> !entry.newId.equals(onto, ignoreCase = true)
            else -> COMMIT_MESSAGE_PREFIXES.any { comment.startsWith(it) }
        }
    }
    .map { it.newId }

/**
 * The list with each worktree's `idleSince` kept only when nothing changed in it for [untouchedDays] (see
 * [WorktreesUntouchedDays.idleAfterDays]), and dropped from all when that's 0.
 */
fun WorktreeList.withIdle(untouchedDays: Int, nowMillis: Long): WorktreeList {
    val days = WorktreesUntouchedDays.idleAfterDays(untouchedDays)
    val before = days?.let { nowMillis - it * DAY_IN_MS }

    if (worktrees.none { it.idleSince != null }) {
        return this
    }

    return copy(
        worktrees = worktrees.map { info ->
            val idleSince = info.idleSince

            if (idleSince != null && (before == null || idleSince > before)) info.copy(idleSince = null) else info
        }
    )
}
