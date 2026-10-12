// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun unstaged(path: String, type: StatusType) = StatusEntry(path, type, EntryType.UNSTAGED)

class StatusFileItemsTest {
    @Test
    fun `maps status types to change kinds`() {
        val items = listOf(
            unstaged("a", StatusType.ADDED),
            unstaged("b", StatusType.MODIFIED),
            unstaged("c", StatusType.REMOVED),
            unstaged("d", StatusType.CONFLICTING),
        ).toFileItems()

        assertEquals(
            listOf(FileChangeKind.Added, FileChangeKind.Modified, FileChangeKind.Deleted, FileChangeKind.Conflicting),
            items.map { it.kind },
        )
    }

    @Test
    fun `keys a repeated path apart`() {
        val items = listOf(
            unstaged("src/a.kt", StatusType.MODIFIED),
            unstaged("src/a.kt", StatusType.CONFLICTING),
            unstaged("src/b.kt", StatusType.MODIFIED),
        ).toFileItems()

        assertEquals(listOf("src/a.kt", "src/a.kt#2", "src/b.kt"), items.map { it.key })
        assertEquals(listOf("src/a.kt", "src/a.kt", "src/b.kt"), items.map { it.path })
    }

    @Test
    fun `lists conflicts first in a folder sorted by change type`() {
        val entries = listOf(
            unstaged("src/added.kt", StatusType.ADDED),
            unstaged("src/conflict.kt", StatusType.CONFLICTING),
            unstaged("src/modified.kt", StatusType.MODIFIED),
            unstaged("README.md", StatusType.REMOVED),
        )
        val state = FilesViewState(FileSortKey.ChangeType, ascending = false, FilesViewMode.FolderTree)

        val rows = buildFileRows(entries.toFileItems(), state, isCollapsed = { false })

        assertEquals(
            listOf("dir:src", "file:src/conflict.kt", "file:src/modified.kt", "file:src/added.kt", "file:README.md"),
            rows.map { it.key },
        )
    }

    @Test
    fun `keeps folders closed during a search apart`() {
        val folders = CollapsedFolders().toggled("src", isSearching = false)

        assertTrue(folders.isCollapsed("src", isSearching = false))
        assertFalse(folders.isCollapsed("src", isSearching = true))

        val searching = folders.toggled("docs", isSearching = true)

        assertTrue(searching.isCollapsed("docs", isSearching = true))
        assertFalse(searching.isCollapsed("docs", isSearching = false))
        assertEquals(folders, searching.withoutSearch())
        assertEquals(CollapsedFolders(), folders.toggled("src", isSearching = false))
    }

    @Test
    fun `finds the entries inside a folder at any depth`() {
        val entries = listOf(
            unstaged("src/a.kt", StatusType.MODIFIED),
            unstaged("src/deep/b.kt", StatusType.MODIFIED),
            unstaged("srcs/c.kt", StatusType.MODIFIED),
            unstaged("README.md", StatusType.MODIFIED),
        )

        assertEquals(listOf("src/a.kt", "src/deep/b.kt"), entries.inFolder("src").map { it.filePath })
        assertEquals(listOf("src/deep/b.kt"), entries.inFolder("src/deep").map { it.filePath })
    }

    @Test
    fun `discarding leaves new files and files with conflicts alone`() {
        val entries = listOf(
            unstaged("src/added.kt", StatusType.ADDED),
            unstaged("src/modified.kt", StatusType.MODIFIED),
            unstaged("src/removed.kt", StatusType.REMOVED),
            unstaged("src/conflict.kt", StatusType.CONFLICTING),
        )

        assertEquals(listOf("src/modified.kt", "src/removed.kt"), entries.discardable().map { it.filePath })
    }

    @Test
    fun `a folder's discard counts the new files and the files with conflicts it keeps`() {
        val entries = listOf(
            unstaged("src/added.kt", StatusType.ADDED),
            unstaged("src/new.kt", StatusType.ADDED),
            unstaged("src/modified.kt", StatusType.MODIFIED),
            unstaged("src/conflict.kt", StatusType.CONFLICTING),
        )

        val discard = entries.folderDiscard()

        assertEquals(listOf("src/modified.kt"), discard.entries.map { it.filePath })
        assertEquals(2, discard.keptNewFiles)
        assertEquals(1, discard.keptConflicted)
    }

    @Test
    fun `a selection's discard keeps only the files with conflicts`() {
        val entries = listOf(
            unstaged("a.kt", StatusType.CONFLICTING),
            unstaged("b.kt", StatusType.ADDED),
            unstaged("c.kt", StatusType.MODIFIED),
            unstaged("d.kt", StatusType.CONFLICTING),
        )

        val discard = entries.selectionDiscard()

        assertEquals(listOf("b.kt", "c.kt"), discard.entries.map { it.filePath })
        assertEquals(0, discard.keptNewFiles)
        assertEquals(2, discard.keptConflicted)

        val onlyConflicts = entries.filter { it.statusType == StatusType.CONFLICTING }.selectionDiscard()

        assertTrue(onlyConflicts.entries.isEmpty(), "Nothing to discard, so the menu leaves Discard out")
        assertEquals(2, onlyConflicts.keptConflicted)
    }
}
