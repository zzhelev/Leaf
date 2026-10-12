// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictVersion.FILE
import dev.app.leaf.domain.models.ConflictVersion.SYMLINK
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

private val bothChanged = StatusEntry("notes.txt", StatusType.CONFLICTING, conflict = FileConflict(FILE, FILE))
private val deletedByThem = StatusEntry("kept.txt", StatusType.CONFLICTING, conflict = FileConflict(FILE, null))
private val linkAgainstFile = StatusEntry("link", StatusType.CONFLICTING, conflict = FileConflict(FILE, SYMLINK))
private val modified = StatusEntry("README.md", StatusType.MODIFIED)

/** The Unstaged menus of conflicted files: the external merge tool, "Use ours" and "Use theirs" (fork-only). */
class ConflictContextMenuTest {
    @Test
    fun `a file that both sides changed gets the merge tool, ours and theirs, first`() {
        assertEquals(
            listOf("Open in external merge tool", "Use ours", "Use theirs", "Discard file changes"),
            labels(bothChanged).take(4),
        )
    }

    @Test
    fun `a file that git mergetool can't merge gets ours and theirs only`() {
        for (entry in listOf(deletedByThem, linkAgainstFile, bothChanged.copy(filePath = "[ab].txt"))) {
            assertEquals(listOf("Use ours", "Use theirs", "Discard file changes"), labels(entry).take(3), entry.filePath)
        }
    }

    @Test
    fun `a file without conflicts gets none of them`() {
        assertFalse(labels(modified).any { it.startsWith("Use ") || it.contains("merge tool") })
    }

    @Test
    fun `the items run the merge tool and take the side picked`() {
        val calls = mutableListOf<String>()
        val items = singleMenu(bothChanged, calls)

        items[0].onClick()
        items[1].onClick()
        items[2].onClick()

        assertEquals(listOf("merge tool", "OURS", "THEIRS"), calls)
    }

    @Test
    fun `several conflicted files get ours and theirs for all of them`() {
        val calls = mutableListOf<String>()
        val items = statusEntriesContextMenuItems(
            selectedEntriesCount = 3,
            entryType = EntryType.UNSTAGED,
            onDiscard = {},
            onStageSelected = {},
            onUnstageSelected = {},
            onCopyFilesPath = {},
            onUseConflictSide = { calls += it.name },
        ).filterIsInstance<ContextMenuElement.ContextTextEntry>()

        assertEquals(listOf("Use ours for 3 files", "Use theirs for 3 files", "Stage 3 files"), labelsOf(items).take(3))

        items[1].onClick()
        assertEquals(listOf(ConflictSide.THEIRS.name), calls)
    }

    @Test
    fun `a selection with a file that has no conflicts doesn't get them`() {
        val items = statusEntriesContextMenuItems(
            selectedEntriesCount = 2,
            entryType = EntryType.UNSTAGED,
            onDiscard = {},
            onStageSelected = {},
            onUnstageSelected = {},
            onCopyFilesPath = {},
        ).filterIsInstance<ContextMenuElement.ContextTextEntry>()

        assertFalse(labelsOf(items).any { it.startsWith("Use ") })
    }

    private fun singleMenu(entry: StatusEntry, calls: MutableList<String> = mutableListOf()) =
        statusEntryContextMenuItems(
            statusEntry = entry,
            entryType = EntryType.UNSTAGED,
            onReset = {},
            onBlame = {},
            onHistory = {},
            onCopyFilePath = {},
            onOpenFileInFolder = {},
            conflictActions = ConflictMenuActions(
                onOpenInExternalMergeTool = { calls += "merge tool" },
                onUseSide = { calls += it.name },
            ),
        ).filterIsInstance<ContextMenuElement.ContextTextEntry>()

    private fun labels(entry: StatusEntry) = labelsOf(singleMenu(entry))

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
