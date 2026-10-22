// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.diff.followedEntry
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.ui.SelectedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The file whose diff was open, when the selected [commit] doesn't show it: the diff area says so, and keeps the [path]. */
data class MissingFollowedFile(
    val path: String,
    val commit: Commit,
    /** The commit's files couldn't be read, so nothing is known about the file in it. */
    val unreadable: Boolean,
)

/**
 * Keeps an open diff with the selection (fork-only, upstream issues 343, 136 and 128). The log stays visible beside the
 * diff, so the user steps from commit to commit with the diff open, and the diff moves with them:
 * - A commit's diff follows by path (`followedEntry`): the same file in the next commit, or the file as that commit
 *   moved it. A commit that doesn't touch it leaves [missing], which the diff area shows in place of a diff and which
 *   keeps the path, so the next commit that does change the file shows its diff again.
 * - The diff belongs to the list it was opened from. The uncommitted changes' diff closes when a commit is selected,
 *   and a commit's diff when the uncommitted changes are, as the lists that held the file are gone.
 * - The review of a worktree has no selected item. A diff opened from it doesn't follow, and closes with the review.
 *
 * It follows only the first time a commit is selected after the user opened a file, and not again when the same
 * commit's files reload (a refresh gives new entries), which would load the same diff twice.
 */
class DiffFollowController(
    scope: CoroutineScope,
    private val selectedItem: StateFlow<SelectedItem>,
    commitChanges: Flow<CommitChangesState?>,
    private val diffSelected: StateFlow<DiffSelected?>,
    private val keepsLog: StateFlow<Boolean>,
    /** Opens the diff of an entry, or closes the diff for null. Opening one is a single step, never through null. */
    private val select: (DiffSelected?) -> Unit,
) {
    /** Set while the diff area stands in for a diff: the file that was open, which the selected commit doesn't show. */
    val missing: StateFlow<MissingFollowedFile?>
        field = MutableStateFlow<MissingFollowedFile?>(null)

    private val lock = Any()

    /** The path of the file the user opened from a commit's list, followed from commit to commit. */
    private var pinnedPath: String? = null

    /** The commit that the diff shows, or that was found not to show the file: it needs no follow of its own. */
    private var handledCommit: String? = null

    private var wasNothingSelected = false

    init {
        scope.launch {
            combine(selectedItem, commitChanges) { item, changes -> item to changes }
                .collect { (item, changes) -> update(item, changes) }
        }

        // With the log hidden, a diff area with no diff would leave nothing to step with
        scope.launch {
            keepsLog.collect { keeps ->
                if (!keeps) synchronized(lock) { forgetLocked() }
            }
        }
    }

    /** The user opened a file from a commit's list. Whatever else selects a diff doesn't follow. */
    fun pin(selection: DiffSelected) {
        val diff = (selection as? DiffSelected.CommitedChanges)?.items?.singleOrNull() ?: return
        val item = selectedItem.value as? SelectedItem.CommitBasedItem ?: return

        synchronized(lock) {
            pinnedPath = diff.filePath
            handledCommit = item.commit.hash
            missing.value = null
        }
    }

    /** The user closed the diff: its file isn't followed any more. */
    fun forget() = synchronized(lock) { forgetLocked() }

    private fun forgetLocked() {
        pinnedPath = null
        handledCommit = null
        missing.value = null
    }

    private fun update(item: SelectedItem, changes: CommitChangesState?) = synchronized(lock) {
        when (item) {
            is SelectedItem.CommitBasedItem -> follow(item, changes)

            SelectedItem.UncommittedChanges -> {
                forgetLocked()
                closeDiffOf<DiffSelected.CommitedChanges>()
                wasNothingSelected = false
            }

            SelectedItem.None -> {
                forgetLocked()
                wasNothingSelected = true
            }
        }
    }

    private fun follow(item: SelectedItem.CommitBasedItem, changes: CommitChangesState?) {
        // An uncommitted file's diff belongs to the Staged and Unstaged lists, which the commit's files replace
        closeDiffOf<DiffSelected.UncommittedChanges>()

        // The review's file, once the review is closed and a commit is selected again
        if (wasNothingSelected && pinnedPath == null) closeDiffOf<DiffSelected.CommitedChanges>()
        wasNothingSelected = false

        val path = pinnedPath ?: return
        val commit = item.commit

        if (commit.hash == handledCommit) return

        // The files of this commit, not those of the one before it, which are shown until they load
        if (changes == null || changes.commit.hash != commit.hash || changes.isLoading || changes.showsPreviousChanges) return

        handledCommit = commit.hash

        val entry = if (changes.error == null) followedEntry(path, changes.changes) else null

        when {
            entry != null -> {
                missing.value = null
                select(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(entry))))
            }

            keepsLog.value -> {
                missing.value = MissingFollowedFile(path, commit, unreadable = changes.error != null)
                select(null)
            }

            else -> {
                forgetLocked()
                select(null)
            }
        }
    }

    private inline fun <reified T : DiffSelected> closeDiffOf() {
        if (diffSelected.value is T) select(null)
    }
}
