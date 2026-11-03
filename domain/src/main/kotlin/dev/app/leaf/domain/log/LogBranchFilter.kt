// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.lib.Constants

/**
 * Which branches the log shows (fork-only, upstream issue 207). Branches are full names (`refs/heads/x`,
 * `refs/remotes/origin/x`). The branch that the tab's worktree has checked out always shows, a detached HEAD too,
 * whatever the filter says: the uncommitted changes' row hangs on it, and after a checkout the log shows where the
 * worktree is.
 *
 * It's kept per repository, so every worktree's tab shares it, and [Current] stands for each worktree's own branch.
 */
sealed interface LogBranchFilter {
    /** Every branch but [hidden]. Branches made later show. With none hidden, it's no filter at all. */
    data class All(val hidden: Set<String> = emptySet()) : LogBranchFilter

    /** The checked-out branch, and the remote branches of its name, such as its upstream. */
    data object Current : LogBranchFilter

    /** [shown] and the checked-out branch. Branches made later stay out. */
    data class Only(val shown: Set<String>) : LogBranchFilter
}

/** The log as it always was: every branch. */
val NO_LOG_BRANCH_FILTER: LogBranchFilter = LogBranchFilter.All()

/** Whether [branch] is the one checked out in the tab's worktree, or its detached HEAD (JGit lists it as `HEAD`). */
fun Branch.isCheckedOut(currentBranch: Branch?): Boolean = currentBranch != null && name == currentBranch.name

/** Whether the log shows [branch] under this filter. */
fun LogBranchFilter.shows(branch: Branch, currentBranch: Branch?): Boolean = when {
    branch.isCheckedOut(currentBranch) -> true
    this is LogBranchFilter.All -> branch.name !in hidden
    this is LogBranchFilter.Only -> branch.name in shown
    else -> currentBranch != null && currentBranch.name != Constants.HEAD && branch.isRemote &&
        branch.simpleName == currentBranch.simpleName
}

/**
 * The filter without the names that [branches] don't have any more (a branch deleted or renamed outside Leaf), so that
 * what's left says what the log leaves out. It keeps them in the saved filter: a branch of that name may come back.
 */
fun LogBranchFilter.withExisting(branches: List<Branch>): LogBranchFilter {
    val names = branches.mapTo(HashSet()) { it.name }

    return when (this) {
        is LogBranchFilter.All -> LogBranchFilter.All(hidden.filterTo(LinkedHashSet()) { it in names })
        is LogBranchFilter.Only -> LogBranchFilter.Only(shown.filterTo(LinkedHashSet()) { it in names })
        LogBranchFilter.Current -> this
    }
}

/**
 * The branches that a branch's menu acts on: a local branch with the remote branches of its name, which are usually
 * its upstream and would show its line on their own, or a remote branch alone.
 */
fun Branch.withRemotesOfItsName(remoteBranches: List<Branch>): Set<String> = if (isLocal) {
    setOf(name) + remoteBranches.filter { it.simpleName == simpleName }.map { it.name }
} else {
    setOf(name)
}

/**
 * The filter that leaves [names] out too. [shownNow] are the branches that the log shows under this filter, which a
 * [LogBranchFilter.Current] turns into, as it has no names of its own.
 */
fun LogBranchFilter.hiding(names: Set<String>, shownNow: Set<String>): LogBranchFilter = when (this) {
    is LogBranchFilter.All -> LogBranchFilter.All(hidden + names)
    is LogBranchFilter.Only -> LogBranchFilter.Only(shown - names)
    LogBranchFilter.Current -> LogBranchFilter.Only(shownNow - names)
}

/** The filter that shows [names] too. See [hiding] for [shownNow]. */
fun LogBranchFilter.showing(names: Set<String>, shownNow: Set<String>): LogBranchFilter = when (this) {
    is LogBranchFilter.All -> LogBranchFilter.All(hidden - names)
    is LogBranchFilter.Only -> LogBranchFilter.Only(shown + names)
    LogBranchFilter.Current -> LogBranchFilter.Only(shownNow + names)
}

/** The names of [branches] that the log shows under this filter. */
fun LogBranchFilter.shownNames(branches: List<Branch>, currentBranch: Branch?): Set<String> =
    branches.filter { shows(it, currentBranch) }.mapTo(LinkedHashSet()) { it.name }

/**
 * A branch's box in the list of branches, checked or not: it shows [branch] alone, or leaves it out alone, whatever
 * its remote branches do. The checked-out branch can't be left out, so it stays as it is.
 */
fun LogBranchFilter.toggled(branch: Branch, branches: List<Branch>, currentBranch: Branch?): LogBranchFilter {
    if (branch.isCheckedOut(currentBranch)) return this

    val shownNow = shownNames(branches, currentBranch)

    return if (shows(branch, currentBranch)) {
        hiding(setOf(branch.name), shownNow)
    } else {
        showing(setOf(branch.name), shownNow)
    }
}

/**
 * What another worktree's detached HEAD shows under the filter: while it rebases or bisects a shown branch ([branch],
 * a full name), its commit; without such a branch, only when every branch shows but some ([LogBranchFilter.All]).
 */
fun LogBranchFilter.showsWorktreeHead(branch: String?, shownBranches: Set<String>): Boolean =
    if (branch != null) branch in shownBranches else this is LogBranchFilter.All

/**
 * The branch (its short name) that a stash's message says it was made on: git's `WIP on main: …` and `On main: …`, and
 * Leaf's backup before a merge, `Snapshot WIP on main: …`. Null for a detached HEAD (`(no branch)`), and for a message
 * that names none, as JGit keeps a typed message as it is, and git's `autostash`. Branch names have no space or colon.
 */
fun stashBranchOf(message: String): String? = STASH_BRANCH.find(message.lineSequence().first())?.groupValues?.get(1)

private val STASH_BRANCH = Regex("^(?:Snapshot )?(?:WIP on|On) ([^ :]+): ")
