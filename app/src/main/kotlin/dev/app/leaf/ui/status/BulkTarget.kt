// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry

/**
 * What the header's button of a Staged or Unstaged pane stages or unstages (fork-only). It acts on what the pane
 * shows, as a folder row does, so a search that hides files keeps them out of it.
 */
sealed interface BulkTarget {
    /** Every file of the pane: no search, or one that matches all of them. */
    data object All : BulkTarget

    /** Two or more selected files, whatever the search shows: the user picked them. */
    data class Selected(val entries: List<StatusEntry>) : BulkTarget

    /** The files that a search matches, fewer than the pane has. */
    data class Matches(val entries: List<StatusEntry>) : BulkTarget

    /** A search that matches nothing. The button does nothing: `null` entries would mean every file. */
    data object NoMatches : BulkTarget

    /** The entries to give the use case, null for every file. Not for [NoMatches], which [isNoOp] stops first. */
    val entriesToApply: List<StatusEntry>?
        get() = when (this) {
            All, NoMatches -> null
            is Selected -> entries
            is Matches -> entries
        }

    val isNoOp: Boolean get() = this == NoMatches
}

/** The target of [entryType]'s header button, given the files [selected] in that pane. */
fun StatusState.bulkTarget(entryType: EntryType, selected: List<StatusEntry>): BulkTarget {
    if (selected.size > 1) return BulkTarget.Selected(selected)

    if (activeSearchOf(entryType) == null) return BulkTarget.All

    val shown = entriesShown(entryType)

    return when {
        shown.isEmpty() -> BulkTarget.NoMatches
        shown.size == getEntriesByEntryType(entryType).size -> BulkTarget.All
        else -> BulkTarget.Matches(shown)
    }
}
