// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.ignore.ignoreRequestFor
import dev.app.leaf.domain.sorting.selectionDiscard
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Unstaged menu of a file added with `git add -N`: a new file, as Leaf treats untracked ones, so Delete and no
 * Discard (git's restore would empty it), and no Ignore, as an ignore rule doesn't apply to a file the index has.
 */
class IntentToAddMenuTest {
    private val intentToAdd = StatusEntry("new.txt", StatusType.ADDED, EntryType.UNSTAGED, isIntentToAdd = true)
    private val untracked = StatusEntry("build.log", StatusType.ADDED, EntryType.UNSTAGED)

    @Test
    fun `a file added with git add -N gets Delete, and neither Discard nor Ignore`() {
        val labels = labels(intentToAdd)

        assertTrue("Delete file" in labels, labels.toString())
        assertFalse("Discard file changes" in labels, labels.toString())
        assertFalse("Ignore…" in labels, labels.toString())
    }

    @Test
    fun `an untracked file still gets Ignore`() {
        assertTrue("Ignore…" in labels(untracked))
    }

    @Test
    fun `a selection with one discards the others only, and ignores the untracked ones only`() {
        // As StatusPane counts them
        val selected = listOf(intentToAdd, untracked, StatusEntry("a.txt", StatusType.MODIFIED))
        val discard = selected.selectionDiscard()
        val ignore = ignoreRequestFor(selected, EntryType.UNSTAGED)

        val labels = labelsOf(
            statusEntriesContextMenuItems(
                selectedEntriesCount = selected.size,
                entryType = EntryType.UNSTAGED,
                onDiscard = {},
                discardCount = discard.entries.size,
                onStageSelected = {},
                onUnstageSelected = {},
                onCopyFilesPath = {},
                ignoreCount = ignore?.paths?.size ?: 0,
                onIgnore = {},
            ).filterIsInstance<ContextMenuElement.ContextTextEntry>()
        )

        assertTrue("Discard 2 files" in labels, labels.toString())
        assertTrue("Ignore 1 files…" in labels, labels.toString())
    }

    private fun labels(entry: StatusEntry) = labelsOf(
        statusEntryContextMenuItems(
            statusEntry = entry,
            entryType = EntryType.UNSTAGED,
            onReset = {},
            onDelete = {},
            onBlame = {},
            onHistory = {},
            onCopyFilePath = {},
            onOpenFileInFolder = {},
            onIgnore = {},
        ).filterIsInstance<ContextMenuElement.ContextTextEntry>()
    )

    /** The items' labels, read in a composition as the menu reads them. */
    private fun labelsOf(items: List<ContextMenuElement.ContextTextEntry>): List<String> {
        var labels = emptyList<String>()
        val scene = ImageComposeScene(width = 10, height = 10) {
            labels = items.map { it.composableLabel() }
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        return labels
    }
}
