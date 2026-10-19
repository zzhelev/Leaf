// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.hasConflicts

/**
 * What "Discard all" does with the unstaged files that the pane shows (see [allDiscard]).
 *
 * It restores [restored], the tracked files that were changed or deleted. The [newFiles] aren't in git, so discarding
 * doesn't touch them: they are deleted only when the user ticks the box that says so, and the dialog counts them. The
 * [keptConflicted] files with conflicts are always left as they are, as in a folder's or a selection's discard, and so
 * are the [keptSubmodules] that moved on: git leaves a submodule alone when it checks the files out, and JGit fails.
 */
data class DiscardAllPlan(
    val restored: List<StatusEntry>,
    val newFiles: List<StatusEntry>,
    val keptConflicted: List<StatusEntry>,
    val keptSubmodules: List<StatusEntry> = emptyList(),
) {
    /** Whether there is nothing to restore, so that the dialog's only use is deleting the new files. */
    val restoresNothing: Boolean get() = restored.isEmpty()

    /** Whether the action has anything to do: something to restore, or new files that it may delete. */
    val hasWork: Boolean get() = restored.isNotEmpty() || newFiles.isNotEmpty()
}

/**
 * What "Discard all" does with these entries, the unstaged files that the pane shows (all of them, or only the matches
 * of its search): [discardable] ones are restored, new files are offered for deletion, and conflicted files are left.
 */
fun List<StatusEntry>.allDiscard() = DiscardAllPlan(
    restored = discardable().filterNot { it.isSubmodule },
    newFiles = filter { it.statusType == StatusType.ADDED },
    keptConflicted = filter { it.hasConflicts },
    keptSubmodules = filter { it.isSubmodule },
)
