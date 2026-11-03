// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.sorting.refFolderOf
import org.eclipse.jgit.lib.Constants

/**
 * Which branches the log shows (fork-only, upstream issue 207). Branches are full names (`refs/heads/x`,
 * `refs/remotes/origin/x`). The branch that the tab's worktree has checked out always shows, a detached HEAD too,
 * whatever the filter says: the uncommitted changes' row hangs on it, and after a checkout the log shows where the
 * worktree is.
 *
 * It's kept per repository, so every worktree's tab shares it, and [Current] stands for each worktree's own branch.
 *
 * A folder is the side panel's: the part of a branch's name before its first `/` (`claude` for `claude/parser-cache`
 * and `origin/claude/parser-cache` alike, [logFolder]). A folder in a filter is a pattern: the branches made in it later
 * follow it. One branch of such a folder can be the exception.
 */
sealed interface LogBranchFilter {
    /**
     * Every branch but [hidden] and those in [hiddenFolders], where [shownInHiddenFolders] show all the same. Branches
     * made later show, unless their folder is hidden. With none hidden, it's no filter at all.
     */
    data class All(
        val hidden: Set<String> = emptySet(),
        val hiddenFolders: Set<String> = emptySet(),
        val shownInHiddenFolders: Set<String> = emptySet(),
    ) : LogBranchFilter

    /** The checked-out branch, and the remote branches of its name, such as its upstream. */
    data object Current : LogBranchFilter

    /**
     * [shown], those in [shownFolders] but [hiddenInShownFolders], and the checked-out branch. Branches made later stay
     * out, unless their folder shows.
     */
    data class Only(
        val shown: Set<String>,
        val shownFolders: Set<String> = emptySet(),
        val hiddenInShownFolders: Set<String> = emptySet(),
    ) : LogBranchFilter
}

/** The log as it always was: every branch. */
val NO_LOG_BRANCH_FILTER: LogBranchFilter = LogBranchFilter.All()

/** Whether [branch] is the one checked out in the tab's worktree, or its detached HEAD (JGit lists it as `HEAD`). */
fun Branch.isCheckedOut(currentBranch: Branch?): Boolean = currentBranch != null && name == currentBranch.name

/** The branch's folder, the side panel's: its short name's part before the first `/`, without a remote's name. */
val Branch.logFolder: String? get() = refFolderOf(simpleName)

/** The folder of the branch whose full name is [name] (`refs/heads/x/y` and `refs/remotes/origin/x/y` give `x`). */
fun logFolderOf(name: String): String? = when {
    name.startsWith(Constants.R_HEADS) -> refFolderOf(name.removePrefix(Constants.R_HEADS))
    name.startsWith(Constants.R_REMOTES) -> refFolderOf(name.removePrefix(Constants.R_REMOTES).substringAfter('/', ""))
    else -> null
}

/** Whether the log shows [branch] under this filter. */
fun LogBranchFilter.shows(branch: Branch, currentBranch: Branch?): Boolean {
    val folder = branch.logFolder

    return when {
        branch.isCheckedOut(currentBranch) -> true
        this is LogBranchFilter.All -> branch.name !in hidden &&
            (folder == null || folder !in hiddenFolders || branch.name in shownInHiddenFolders)

        this is LogBranchFilter.Only -> branch.name in shown ||
            (folder != null && folder in shownFolders && branch.name !in hiddenInShownFolders)

        else -> currentBranch != null && currentBranch.name != Constants.HEAD && branch.isRemote &&
            branch.simpleName == currentBranch.simpleName
    }
}

/**
 * The filter without the names that [branches] don't have any more (a branch deleted or renamed outside Leaf), so that
 * what's left says what the log leaves out. It keeps them in the saved filter: a branch of that name may come back.
 */
fun LogBranchFilter.withExisting(branches: List<Branch>): LogBranchFilter {
    val names = branches.mapTo(HashSet()) { it.name }

    fun Set<String>.existing() = filterTo(LinkedHashSet()) { it in names }

    // Folders stay: a pattern is about the branches made later too
    return when (this) {
        is LogBranchFilter.All -> LogBranchFilter.All(hidden.existing(), hiddenFolders, shownInHiddenFolders.existing())
        is LogBranchFilter.Only -> LogBranchFilter.Only(shown.existing(), shownFolders, hiddenInShownFolders.existing())
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
 * The filter that leaves [names] out too: a branch of a shown folder becomes its exception, and one that was the
 * exception of a hidden folder isn't any more. [shownNow] are the branches that the log shows under this filter, which a
 * [LogBranchFilter.Current] turns into, as it has no names of its own.
 */
fun LogBranchFilter.hiding(names: Set<String>, shownNow: Set<String>): LogBranchFilter = when (this) {
    is LogBranchFilter.All -> {
        val inHiddenFolders = names.filterTo(HashSet()) { logFolderOf(it) in hiddenFolders }

        copy(hidden = hidden + (names - inHiddenFolders), shownInHiddenFolders = shownInHiddenFolders - names)
    }

    is LogBranchFilter.Only -> {
        val inShownFolders = names.filterTo(HashSet()) { logFolderOf(it) in shownFolders }

        copy(shown = shown - names, hiddenInShownFolders = hiddenInShownFolders + inShownFolders)
    }

    LogBranchFilter.Current -> LogBranchFilter.Only(shownNow).hiding(names, shownNow)
}

/** The filter that shows [names] too: a branch of a hidden folder becomes its exception. See [hiding] for [shownNow]. */
fun LogBranchFilter.showing(names: Set<String>, shownNow: Set<String>): LogBranchFilter = when (this) {
    is LogBranchFilter.All -> {
        val inHiddenFolders = names.filterTo(HashSet()) { logFolderOf(it) in hiddenFolders }

        copy(hidden = hidden - names, shownInHiddenFolders = shownInHiddenFolders + inHiddenFolders)
    }

    is LogBranchFilter.Only -> {
        val inShownFolders = names.filterTo(HashSet()) { logFolderOf(it) in shownFolders }

        copy(shown = shown + (names - inShownFolders), hiddenInShownFolders = hiddenInShownFolders - names)
    }

    LogBranchFilter.Current -> LogBranchFilter.Only(shownNow).showing(names, shownNow)
}

/**
 * The filter that leaves [folder] out, the branches made in it later too, for local and remote branches alike. What
 * was said of its branches one by one goes: they all follow the folder now.
 */
fun LogBranchFilter.hidingFolder(folder: String, shownNow: Set<String>): LogBranchFilter = when (this) {
    is LogBranchFilter.All -> LogBranchFilter.All(
        hidden = hidden.notIn(folder),
        hiddenFolders = hiddenFolders + folder,
        shownInHiddenFolders = shownInHiddenFolders.notIn(folder),
    )

    is LogBranchFilter.Only -> LogBranchFilter.Only(
        shown = shown.notIn(folder),
        shownFolders = shownFolders - folder,
        hiddenInShownFolders = hiddenInShownFolders.notIn(folder),
    )

    LogBranchFilter.Current -> LogBranchFilter.Only(shownNow).hidingFolder(folder, shownNow)
}

/** The filter that shows [folder] whole, the branches made in it later too. See [hidingFolder]. */
fun LogBranchFilter.showingFolder(folder: String, shownNow: Set<String>): LogBranchFilter = when (this) {
    is LogBranchFilter.All -> LogBranchFilter.All(
        hidden = hidden.notIn(folder),
        hiddenFolders = hiddenFolders - folder,
        shownInHiddenFolders = shownInHiddenFolders.notIn(folder),
    )

    is LogBranchFilter.Only -> LogBranchFilter.Only(
        shown = shown.notIn(folder),
        shownFolders = shownFolders + folder,
        hiddenInShownFolders = hiddenInShownFolders.notIn(folder),
    )

    LogBranchFilter.Current -> LogBranchFilter.Only(shownNow).showingFolder(folder, shownNow)
}

/** The names that aren't in [folder]. */
private fun Set<String>.notIn(folder: String): Set<String> = filterTo(LinkedHashSet()) { logFolderOf(it) != folder }

/**
 * A folder's box in the list of branches: it leaves the folder out while the log shows any of its branches (or all of
 * them), and shows it whole while it shows none. The checked-out branch shows whatever the filter says, so it doesn't
 * count.
 */
fun LogBranchFilter.toggledFolder(folder: String, branches: List<Branch>, currentBranch: Branch?): LogBranchFilter {
    val shownNow = shownNames(branches, currentBranch)
    val showsAny = branches.any { it.logFolder == folder && !it.isCheckedOut(currentBranch) && shows(it, currentBranch) }

    return if (showsAny) hidingFolder(folder, shownNow) else showingFolder(folder, shownNow)
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
 * The branches of each commit's row that the filter shows, for the row's chips: a branch that it hides keeps no chip,
 * also where its commit is on a shown branch's line, as a merged branch's is. Rows without one are left out.
 */
fun LogBranchFilter.chips(branchesByCommit: Map<String, List<Branch>>, currentBranch: Branch?): Map<String, List<Branch>> {
    if (this == NO_LOG_BRANCH_FILTER) return branchesByCommit

    return branchesByCommit
        .mapValues { (_, branches) -> branches.filter { shows(it, currentBranch) } }
        .filterValues { it.isNotEmpty() }
}

/**
 * The branch (its short name) that a stash's message says it was made on: git's `WIP on main: …` and `On main: …`, and
 * Leaf's backup before a merge, `Snapshot WIP on main: …`. Null for a detached HEAD (`(no branch)`), and for a message
 * that names none, as JGit keeps a typed message as it is, and git's `autostash`. Branch names have no space or colon.
 */
fun stashBranchOf(message: String): String? = STASH_BRANCH.find(message.lineSequence().first())?.groupValues?.get(1)

private val STASH_BRANCH = Regex("^(?:Snapshot )?(?:WIP on|On) ([^ :]+): ")
