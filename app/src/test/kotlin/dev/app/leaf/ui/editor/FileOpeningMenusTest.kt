// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editor.EditorChoice
import dev.app.leaf.domain.editor.EditorKind
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.committedChangesEntriesContextMenuItems
import dev.app.leaf.ui.context_menu.statusEntryContextMenuItems
import dev.app.leaf.ui.diff.EditorLine
import dev.app.leaf.ui.diff.editorLineOf
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SEPARATOR = "---"
private const val PATH = "app/src/main/kotlin/dev/app/leaf/ui/diff/DiffLineActions.kt"

/** "Open in …", "Open in another editor…" and "Show in Finder" in the file menus and a diff line's menu (fork-only). */
class FileOpeningMenusTest {
    private val intellij = InstalledEditor("intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS, tool = "/bin/idea")
    private val calls = mutableListOf<String>()

    private fun opening(choice: EditorChoice) = FileOpening(
        choice = choice,
        onOpen = { path, line -> calls += "open $path $line" },
        onChooseEditor = { path, line -> calls += "choose $path $line" },
        onShow = { path -> calls += "show $path" },
    )

    private val showLabel = when (currentOs) {
        OS.MAC -> "Show in Finder"
        OS.WINDOWS -> "Show in Explorer"
        else -> "Show in file manager"
    }

    private fun statusMenu(entry: StatusEntry, choice: EditorChoice?) = statusEntryContextMenuItems(
        statusEntry = entry,
        entryType = entry.entryType,
        onReset = {},
        onDelete = {},
        onBlame = {},
        onHistory = {},
        onCopyFilePath = {},
        onOpenFileInFolder = { calls += "folder" },
        fileOpening = choice?.let(::opening),
    )

    @Test
    fun `a changed file's menu starts with its editor, another editor and Show in Finder`() {
        val items = statusMenu(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), EditorChoice.Installed(intellij))

        assertEquals(
            listOf("Open in IntelliJ IDEA", "Open in another editor…", showLabel, SEPARATOR, "Discard file changes"),
            labelsOf(items).take(5),
        )
        assertEquals(null, labelsOf(items).firstOrNull { it == "Open file in folder" }, "replaced")

        items.textEntries().take(3).forEach { it.onClick() }
        assertEquals(listOf("open $PATH null", "choose $PATH null", "show $PATH"), calls)
    }

    @Test
    fun `with no editor chosen, one item asks which`() {
        val items = statusMenu(StatusEntry(PATH, StatusType.ADDED, EntryType.UNSTAGED), EditorChoice.None)

        assertEquals(listOf("Open in editor…", showLabel, SEPARATOR), labelsOf(items).take(3))
    }

    @Test
    fun `the default app and a command of one's own say so`() {
        val entry = StatusEntry(PATH, StatusType.MODIFIED, EntryType.STAGED)

        assertEquals("Open with default app", labelsOf(statusMenu(entry, EditorChoice.DefaultApp)).first())
        assertEquals("Open in editor", labelsOf(statusMenu(entry, EditorChoice.Custom("ed"))).first())
        assertEquals(
            "Open in Zed",
            labelsOf(statusMenu(entry, EditorChoice.NotInstalled("zed", "Zed"))).first(),
            "a chosen editor keeps its name, and opening it says it's missing",
        )
    }

    @Test
    fun `a deleted file has nothing to open, and Show in Finder shows its folder`() {
        val items = statusMenu(StatusEntry(PATH, StatusType.REMOVED, EntryType.UNSTAGED), EditorChoice.Installed(intellij))

        assertEquals(listOf(showLabel, SEPARATOR), labelsOf(items).take(2))
    }

    @Test
    fun `outside a tab, Show in Finder still opens the folder as before`() {
        val items = statusMenu(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), choice = null)

        assertEquals(showLabel, labelsOf(items).first())
        items.textEntries().first().onClick()
        assertEquals(listOf("folder"), calls)
    }

    @Test
    fun `a commit's file opens as it is now, and a deleted one only shows its folder`() {
        fun menu(changeType: DiffEntry.ChangeType) = committedChangesEntriesContextMenuItems(
            diffEntry = diffEntry(changeType),
            onBlame = {},
            onHistory = {},
            onOpenFileInFolder = {},
            fileOpening = opening(EditorChoice.Installed(intellij)),
        )

        assertEquals(
            listOf("Open in IntelliJ IDEA", "Open in another editor…", showLabel, SEPARATOR, "Blame file", "File history"),
            labelsOf(menu(DiffEntry.ChangeType.MODIFY)),
        )
        assertEquals(listOf(showLabel, SEPARATOR, "File history"), labelsOf(menu(DiffEntry.ChangeType.DELETE)))
    }

    @Test
    fun `a diff line's item opens at its line`() {
        val items = mutableListOf<ContextMenuElement>().apply {
            addOpenAtLineItem(opening(EditorChoice.Installed(intellij)), PATH, 47)
        }

        assertEquals(listOf("Open in IntelliJ IDEA at line 47"), labelsOf(items))
        assertEquals("Open in editor at line 47", labelsOf(mutableListOf<ContextMenuElement>().apply {
            addOpenAtLineItem(opening(EditorChoice.Custom("ed")), PATH, 47)
        }).single())
        assertEquals("Open with default app", labelsOf(mutableListOf<ContextMenuElement>().apply {
            addOpenAtLineItem(opening(EditorChoice.DefaultApp), PATH, 47)
        }).single())

        items.textEntries().single().onClick()
        assertEquals(listOf("open $PATH 47"), calls)
    }

    @Test
    fun `a header's items open at the line in view and show the shortcut, which a file list's don't`() {
        val choice = EditorChoice.Installed(intellij)
        val header = headerFileItems(opening(choice), PATH, line = 12, canOpen = true)
        val list = statusMenu(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), choice)

        assertEquals(listOf("Open in IntelliJ IDEA at line 12", "Open in another editor…", showLabel), labelsOf(header))
        assertEquals(listOf(openInEditorShortcut(), null, null), composed { header.textEntries().map { it.hint?.invoke() } })
        assertNull(list.textEntries().first().hint, "in a list, the shortcut may open another file")

        header.textEntries().forEach { it.onClick() }
        assertEquals(listOf("open $PATH 12", "choose $PATH 12", "show $PATH"), calls)

        assertEquals(listOf("Open in editor…", showLabel), labelsOf(headerFileItems(opening(EditorChoice.None), PATH, 3, true)))
        assertEquals(listOf(showLabel), labelsOf(headerFileItems(opening(choice), PATH, 3, canOpen = false)))
    }

    @Test
    fun `the item that opens a file has the editor's icon, another editor the box with an arrow`() {
        val items = headerFileItems(opening(EditorChoice.Installed(intellij)), PATH, line = null, canOpen = true).textEntries()

        assertNotNull(items[0].customIcon, "drawn by the editor's icon")
        assertNull(items[1].customIcon)
        assertNotNull(items[1].icon)
    }

    @Test
    fun `an Unstaged file's menu starts with Edit the whole file, a Staged one's doesn't`() {
        fun menu(entry: StatusEntry) = statusEntryContextMenuItems(
            statusEntry = entry,
            entryType = entry.entryType,
            onReset = {},
            onDelete = {},
            onBlame = {},
            onHistory = {},
            onCopyFilePath = {},
            onOpenFileInFolder = {},
            fileOpening = FileOpening(
                choice = EditorChoice.Installed(intellij),
                onOpen = { _, _ -> },
                onChooseEditor = { _, _ -> },
                onShow = {},
                onEditWholeFile = { path -> calls += "edit whole $path" },
            ),
        )

        val unstaged = menu(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED))
        assertEquals(listOf("Edit the whole file", "Open in IntelliJ IDEA"), labelsOf(unstaged).take(2))
        unstaged.textEntries().first().onClick()
        assertEquals(listOf("edit whole $PATH"), calls)

        assertEquals("Open in IntelliJ IDEA", labelsOf(menu(StatusEntry(PATH, StatusType.MODIFIED, EntryType.STAGED))).first())
        assertEquals("Edit the whole file", labelsOf(menu(StatusEntry(PATH, StatusType.ADDED, EntryType.UNSTAGED))).first(), "a new file")
        assertTrue("Edit the whole file" !in labelsOf(menu(StatusEntry(PATH, StatusType.REMOVED, EntryType.UNSTAGED))))
    }

    @Test
    fun `the header's file menu starts with Edit the whole file, greyed out for a file too long`() {
        val opening = opening(EditorChoice.Installed(intellij))
        val items = headerFileItems(opening, PATH, line = 3, canOpen = true, onEditWholeFile = { calls += "edit whole" })

        assertEquals(listOf("Edit the whole file", SEPARATOR, "Open in IntelliJ IDEA at line 3"), labelsOf(items).take(3))
        items.textEntries().first().onClick()
        assertEquals(listOf("edit whole"), calls)

        val tooLong = headerFileItems(opening, PATH, 3, true, onEditWholeFile = {}, tooLong = true).textEntries().first()
        assertFalse(tooLong.isEnabled)
        assertEquals("Too long", composed { tooLong.hint?.invoke() })
    }

    @Test
    fun `an uncommitted diff's lines open at their line in the new version, a commit's don't`() {
        val unstaged = DiffType.UncommittedDiff(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED)
        val deleted = DiffType.UncommittedDiff(StatusEntry(PATH, StatusType.REMOVED, EntryType.STAGED), EntryType.STAGED)
        val committed = DiffType.CommitDiff(diffEntry(DiffEntry.ChangeType.MODIFY))
        val added = Line("x", oldLineNumber = 40, newLineNumber = 46, LineType.ADDED)
        val removed = Line("y", oldLineNumber = 41, newLineNumber = 47, LineType.REMOVED)

        assertEquals(EditorLine(PATH, 47), editorLineOf(unstaged, added))
        assertEquals(EditorLine(PATH, 48), editorLineOf(unstaged, removed), "where the removed line was")
        assertNull(editorLineOf(deleted, added))
        assertNull(editorLineOf(committed, added))
    }

    private fun diffEntry(changeType: DiffEntry.ChangeType): DiffEntry = object : DiffEntry() {
        init {
            this.changeType = changeType
            oldPath = PATH
            newPath = if (changeType == DiffEntry.ChangeType.DELETE) DEV_NULL else PATH
            oldId = org.eclipse.jgit.lib.AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
            newId = org.eclipse.jgit.lib.AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
        }
    }

    private fun List<ContextMenuElement>.textEntries() = filterIsInstance<ContextMenuElement.ContextTextEntry>()

    private fun labelsOf(items: List<ContextMenuElement>): List<String> = composed {
        items.map { item ->
            when (item) {
                is ContextMenuElement.ContextTextEntry -> item.composableLabel()
                else -> SEPARATOR
            }
        }
    }

    private fun <T> composed(read: @Composable () -> T): T {
        var value: T? = null
        val scene = ImageComposeScene(width = 10, height = 10) {
            value = read()
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
