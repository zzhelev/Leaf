// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SortSettingsCodecTest {
    @Test
    fun `round-trips the side panel settings`() {
        val settings = RefPanelSettings(
            local = RefSortState(RefSortKey.LastCheckout, ascending = false),
            remote = RefSortState(RefSortKey.LastCommit, ascending = true),
            tags = RefSortState(RefSortKey.TagDate, ascending = false),
            keepHeadOnTop = false,
            groupByPrefix = true,
        )

        val text = SortSettingsCodec.encodeRefPanelSettings(settings)

        assertEquals(settings, SortSettingsCodec.decodeRefPanelSettings(text))
    }

    @Test
    fun `round-trips the files view state`() {
        val state = FilesViewState(FileSortKey.ChangeType, ascending = false, FilesViewMode.FolderTree, splitRatio = 0.3f)

        val text = SortSettingsCodec.encodeFilesViewState(state)

        assertEquals(state, SortSettingsCodec.decodeFilesViewState(text))
    }

    @Test
    fun `missing or unreadable text gives the defaults`() {
        assertEquals(RefPanelSettings(), SortSettingsCodec.decodeRefPanelSettings(null))
        assertEquals(RefPanelSettings(), SortSettingsCodec.decodeRefPanelSettings("not json"))
        assertEquals(FilesViewState(), SortSettingsCodec.decodeFilesViewState(""))
        assertEquals(FilesViewState(), SortSettingsCodec.decodeFilesViewState("[1, 2]"))
    }

    @Test
    fun `defaults are keep on top, no grouping, name A to Z, and split columns by file name`() {
        val panel = RefPanelSettings()

        assertTrue(panel.keepHeadOnTop)
        assertFalse(panel.groupByPrefix)
        RefSection.entries.forEach { assertTrue(panel.sortOf(it).isDefault) }

        val files = FilesViewState()

        assertEquals(FileSortKey.FileName, files.sortKey)
        assertTrue(files.ascending)
        assertEquals(FilesViewMode.SplitColumns, files.viewMode)
        assertTrue(files.isDefault)
    }

    @Test
    fun `unknown values fall back to the field defaults`() {
        val panel = SortSettingsCodec.decodeRefPanelSettings(
            """{"local":{"key":"Bogus","ascending":false},"groupByPrefix":true,"extra":1}"""
        )

        assertEquals(RefSortState(RefSortKey.Name, ascending = false), panel.local)
        assertTrue(panel.groupByPrefix)

        val files = SortSettingsCodec.decodeFilesViewState("""{"viewMode":"Gallery","splitRatio":5.0}""")

        assertEquals(FilesViewMode.SplitColumns, files.viewMode)
        assertEquals(DEFAULT_FILES_SPLIT_RATIO, files.splitRatio)
    }

    @Test
    fun `a sort key that does not apply to a section reads as name`() {
        val panel = RefPanelSettings(tags = RefSortState(RefSortKey.LastCheckout, ascending = false))

        assertEquals(RefSortState(), panel.sortOf(RefSection.Tags))
        assertEquals(
            RefSortState(RefSortKey.TagDate),
            panel.withSort(RefSection.Tags, RefSortState(RefSortKey.TagDate)).sortOf(RefSection.Tags),
        )
    }

    @Test
    fun `carries the old tree toggle over until a files view is saved`() {
        val saved = FilesViewState(FileSortKey.Path, ascending = true, FilesViewMode.FlatList)

        assertEquals(FilesViewState(), SortSettingsCodec.filesViewStateOrLegacy(null, null))
        assertEquals(FilesViewState(), SortSettingsCodec.filesViewStateOrLegacy(null, false))
        assertEquals(
            FilesViewState(viewMode = FilesViewMode.FolderTree),
            SortSettingsCodec.filesViewStateOrLegacy(null, true),
        )
        assertEquals(saved, SortSettingsCodec.filesViewStateOrLegacy(saved, true))
    }
}
