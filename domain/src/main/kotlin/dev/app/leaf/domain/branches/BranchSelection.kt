// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.sorting.refFolderOf

/**
 * The local branches that the side panel has selected for deleting several at once (fork-only, upstream issue 116),
 * as full names (`refs/heads/x`).
 *
 * An empty [selected] means no multi-selection: the branch that the log has selected is the only one highlighted, as
 * it always was. A click with a modifier starts from that branch, so "click a, Ctrl-click b" is two branches.
 *
 * @param anchor where a Shift-click's range starts: the branch last clicked or toggled.
 */
data class BranchSelection(
    val selected: Set<String> = emptySet(),
    val anchor: String? = null,
) {
    /** Whether the right-click menu on [branch] offers the batch actions: it's one of two or more selected. */
    fun isMultiSelectionOf(branch: String): Boolean = selected.size >= 2 && branch in selected

    /** Whether [branch]'s row is highlighted, given the branch that the log has selected ([current], null if none). */
    fun isHighlighted(branch: String, current: String?): Boolean =
        if (selected.isEmpty()) branch == current else branch in selected

    /** A plain click: just that branch, selected in the log as before, so nothing is kept here. */
    fun afterPlainClick(branch: String): BranchSelection = BranchSelection(emptySet(), anchor = branch)

    /** Ctrl-click, or Cmd-click on macOS: [branch] is added, or taken out when it was selected. */
    fun afterToggle(branch: String, current: String?): BranchSelection {
        val base = startingFrom(current)
        val toggled = if (branch in base) base - branch else base + branch

        return BranchSelection(toggled, anchor = branch)
    }

    /**
     * Shift-click: the branches between the anchor and [branch], as the list shows them in [shown] (a collapsed folder's
     * branches, and the ones a search hides, aren't in it), are added. Without an anchor in [shown], only [branch] is.
     * The anchor stays, so that the next Shift-click ranges from the same place.
     */
    fun afterRange(branch: String, shown: List<String>, current: String?): BranchSelection {
        val base = startingFrom(current)
        val start = (anchor ?: current)?.let(shown::indexOf) ?: -1
        val end = shown.indexOf(branch)

        if (start == -1 || end == -1) {
            return BranchSelection(base + branch, anchor = branch)
        }

        val range = shown.subList(minOf(start, end), maxOf(start, end) + 1)

        return BranchSelection(base + range, anchor = anchor ?: current)
    }

    /** "Select its branches" on a folder: [branches] are added. */
    fun afterAdding(branches: Collection<String>, current: String?): BranchSelection =
        BranchSelection(startingFrom(current) + branches, anchor = branches.lastOrNull() ?: anchor)

    /** What a refresh leaves: the selected branches that still exist, as git has them. A moved tip doesn't matter. */
    fun pruned(existing: Collection<String>): BranchSelection {
        val remaining = selected.intersect(existing.toSet())

        if (remaining.size == selected.size && (anchor == null || anchor in existing)) return this

        return BranchSelection(remaining, anchor = anchor?.takeIf { it in existing })
    }

    /** The selection a modifier click changes: this one, or just the log's branch while nothing is selected here. */
    private fun startingFrom(current: String?): Set<String> = selected.ifEmpty { setOfNotNull(current) }
}

/**
 * Whether a click with these keys toggles a branch in the selection: Cmd on macOS, Ctrl elsewhere. Ctrl-click on macOS is
 * a right-click, but the status lists accept it too, as a window can report it with the click.
 */
fun togglesSelection(isCtrlPressed: Boolean, isMetaPressed: Boolean, isMac: Boolean): Boolean =
    isCtrlPressed || (isMac && isMetaPressed)

/** What a click on a local branch does, by the keys held. */
enum class BranchClick {
    /** Selects the branch's commit in the log, as it always did. */
    Plain,

    /** Adds the branch to the selection, or takes it out. */
    Toggle,

    /** Adds the branches shown between the last one clicked and this one. */
    Range,
}

/** Shift wins over Ctrl and Cmd, as in the status lists. */
fun branchClickOf(isShiftPressed: Boolean, isCtrlPressed: Boolean, isMetaPressed: Boolean, isMac: Boolean): BranchClick =
    when {
        isShiftPressed -> BranchClick.Range
        togglesSelection(isCtrlPressed, isMetaPressed, isMac) -> BranchClick.Toggle
        else -> BranchClick.Plain
    }

/**
 * The full names of the local [branches] that the folder row [folder] holds, for "Select its N branches": the ones with its
 * prefix, in [branches]' order. [pinnedBranch] is the current branch while the list keeps it on top, which puts it outside
 * its folder, so the row's count doesn't have it and it isn't selected with the folder.
 */
fun branchesInFolder(branches: List<Branch>, folder: String, pinnedBranch: Branch?): List<String> =
    branches
        .filter { refFolderOf(it.simpleName) == folder && it.name != pinnedBranch?.name }
        .map { it.name }
