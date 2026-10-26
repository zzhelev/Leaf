// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

/**
 * What Leaf's check found for a [RebasePlan] (fork-only, step B2 of `docs/fork/commit-editor.md`), before anything runs:
 * each group's tree, oldest first, up to where the plan stops. Nothing is committed: the trees are in the object
 * database, unreferenced, as `git merge-tree` leaves them.
 *
 * @param groups the groups before [stop], or all of the plan's when it doesn't stop.
 * @param stop where the plan stops first, or null when it runs through.
 * @param inTheWay for a plan that runs through on a branch that a worktree has checked out, the files there that moving
 * the branch's files would meet (B5): the run refuses them, as part A's squash onto the base does.
 */
data class PlanCheck(
    val groups: List<CheckedGroup>,
    val stop: PlanStop?,
    val inTheWay: FilesInTheWay? = null,
) {
    val stops: Boolean
        get() = stop != null

    /** The commits that come out empty, as their changes are already there, which the plan leaves out. */
    val emptyCommits: Set<String>
        get() = groups.flatMapTo(mutableSetOf()) { it.emptyCommits }
}

/**
 * A group of the plan, as the check found it: its [commits] (oldest first), the [tree] of the commit it becomes, and
 * the commits whose changes were already there ([emptyCommits]), which git's `--empty=drop` leaves out. A group whose
 * commits are all empty gives no commit. One whose first commit is empty still gives one, with the others' changes and
 * its message: `git rebase -i` would put them in the commit before it.
 */
data class CheckedGroup(
    val target: String,
    val commits: List<String>,
    val tree: String,
    val emptyCommits: Set<String> = emptySet(),
) {
    val isLeftOut: Boolean
        get() = commits.all { it in emptyCommits }
}

/** Uncommitted [files] in the worktree at [worktreePath] that a plan's new files would overwrite. */
data class FilesInTheWay(
    val worktreePath: String,
    val files: List<String>,
)

/** Where a plan stops, so that it has to run in a worktree, with `git rebase -i`. */
sealed interface PlanStop {
    /** Picking [commit] onto the commits before it in the plan conflicts in [files]. */
    data class Conflict(val commit: String, val files: List<String>) : PlanStop

    /** The group that [commit] leads stops after it, for its files to be changed. */
    data class Edit(val commit: String) : PlanStop

    /** Leaf can't tell whether the plan stops, for [reason]. It then runs with git, which may stop. */
    data class Unknown(val reason: UnknownStopReason) : PlanStop
}

enum class UnknownStopReason {
    /** git can't merge without a working tree with a merge base given (git 2.40 and later can). */
    OLD_GIT,

    /** A partial clone lacks objects that the merges read, and the check downloads nothing. */
    MISSING_OBJECTS,

    /** git couldn't run, or failed otherwise. */
    FAILED,
}
