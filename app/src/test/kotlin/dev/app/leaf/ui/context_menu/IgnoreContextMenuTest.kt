// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val untracked = StatusEntry("build/out.log", StatusType.ADDED)

/** "Ignore…" in the Unstaged menus: offered for untracked files and folders only (fork-only). */
class IgnoreContextMenuTest {
    @Test
    fun `an untracked file in Unstaged gets Ignore after Delete file, and it opens the dialog`() {
        val calls = mutableListOf<String>()
        val items = fileMenu(untracked, EntryType.UNSTAGED, onIgnore = { calls += "ignore" })
        val labels = labelsOf(items)

        assertEquals(listOf("Delete file", "Ignore…", "Open file in folder"), labels.take(3))

        items[labels.indexOf("Ignore…")].onClick()

        assertEquals(listOf("ignore"), calls)
    }

    @Test
    fun `a tracked file gets no Ignore, as ignoring it changes nothing until it is removed from the index`() {
        for (type in listOf(StatusType.MODIFIED, StatusType.REMOVED, StatusType.CONFLICTING)) {
            val entry = StatusEntry("README.md", type)

            assertFalse("Ignore…" in labelsOf(fileMenu(entry, EntryType.UNSTAGED, onIgnore = {})), type.name)
        }
    }

    @Test
    fun `a staged file gets no Ignore, nor does a menu that isn't given the action`() {
        assertFalse("Ignore…" in labelsOf(fileMenu(untracked, EntryType.STAGED, onIgnore = {})))
        assertFalse("Ignore…" in labelsOf(fileMenu(untracked, EntryType.UNSTAGED, onIgnore = null)))
    }

    @Test
    fun `a selection gets Ignore with the number of files it takes`() {
        val calls = mutableListOf<String>()
        val items = selectionMenu(ignoreCount = 3, onIgnore = { calls += "ignore" })
        val labels = labelsOf(items)

        assertEquals(listOf("Stage 5 files", "Discard 5 files", "Ignore 3 files…"), labels.take(3))

        items[2].onClick()

        assertEquals(listOf("ignore"), calls)
    }

    @Test
    fun `a selection without untracked files gets no Ignore`() {
        assertFalse(labelsOf(selectionMenu(ignoreCount = 0, onIgnore = null)).any { it.startsWith("Ignore") })
    }

    @Test
    fun `a folder in Unstaged gets Ignore folder after Discard, and it opens the dialog`() {
        val calls = mutableListOf<String>()
        val items = folderMenu(EntryType.UNSTAGED, onDiscard = {}, onIgnore = { calls += "ignore" })

        assertEquals(
            listOf("Stage changes in the directory", "Discard changes", "Ignore folder…"),
            labelsOf(items),
        )

        items.last().onClick()

        assertEquals(listOf("ignore"), calls)
    }

    @Test
    fun `a folder that can't be discarded still gets Ignore folder, below a separator`() {
        val menu = statusDirEntriesContextMenuItems(
            entryType = EntryType.UNSTAGED,
            onStageChanges = {},
            onDiscardDirectoryChanges = null,
            onIgnoreFolder = {},
        )

        assertEquals(
            listOf("ContextTextEntry", "ContextSeparator", "ContextTextEntry"),
            menu.map { it::class.simpleName },
        )
        assertEquals(listOf("Stage changes in the directory", "Ignore folder…"), labelsOf(menu.textEntries()))
    }

    @Test
    fun `a staged folder, or one without the action, gets no Ignore folder`() {
        assertFalse(labelsOf(folderMenu(EntryType.STAGED, onDiscard = {}, onIgnore = {})).any { it.startsWith("Ignore") })
        assertFalse(labelsOf(folderMenu(EntryType.UNSTAGED, onDiscard = {}, onIgnore = null)).any { it.startsWith("Ignore") })
        assertTrue(
            statusDirEntriesContextMenuItems(EntryType.UNSTAGED, onStageChanges = {}, onDiscardDirectoryChanges = null)
                .none { it is ContextMenuElement.ContextSeparator },
        )
    }

    private fun fileMenu(entry: StatusEntry, entryType: EntryType, onIgnore: (() -> Unit)?) =
        statusEntryContextMenuItems(
            statusEntry = entry,
            entryType = entryType,
            onReset = {},
            onBlame = {},
            onHistory = {},
            onCopyFilePath = {},
            onOpenFileInFolder = {},
            onIgnore = onIgnore,
        ).textEntries()

    private fun selectionMenu(ignoreCount: Int, onIgnore: (() -> Unit)?) =
        statusEntriesContextMenuItems(
            selectedEntriesCount = 5,
            entryType = EntryType.UNSTAGED,
            onDiscard = {},
            onStageSelected = {},
            onUnstageSelected = {},
            onCopyFilesPath = {},
            ignoreCount = ignoreCount,
            onIgnore = onIgnore,
        ).textEntries()

    private fun folderMenu(entryType: EntryType, onDiscard: (() -> Unit)?, onIgnore: (() -> Unit)?) =
        statusDirEntriesContextMenuItems(
            entryType = entryType,
            onStageChanges = {},
            onDiscardDirectoryChanges = onDiscard,
            onIgnoreFolder = onIgnore,
        ).textEntries()

    private fun List<ContextMenuElement>.textEntries() = filterIsInstance<ContextMenuElement.ContextTextEntry>()

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
