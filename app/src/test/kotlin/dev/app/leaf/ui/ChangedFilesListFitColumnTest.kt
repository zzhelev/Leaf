// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.sorting.FileChangeKind
import dev.app.leaf.domain.sorting.FileItem
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.SortSettingsCodec
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A double-click on the separator between the file and directory columns fits the file column to its longest name. */
class ChangedFilesListFitColumnTest {
    private val view = FilesViewState(viewMode = FilesViewMode.SplitColumns)

    @Test
    fun `the ratio holds the name and its slack, within the bounds of a drag`() {
        assertEquals(0.25f, fittedSplitRatio(longestNamePx = 196, slackPx = 4f, availablePx = 800f))
        assertEquals(SortSettingsCodec.MIN_SPLIT_RATIO, fittedSplitRatio(10, 4f, 800f))
        assertEquals(SortSettingsCodec.MAX_SPLIT_RATIO, fittedSplitRatio(5000, 4f, 800f))
    }

    @Test
    fun `without a name or room nothing changes`() {
        assertNull(fittedSplitRatio(null, 4f, 800f))
        assertNull(fittedSplitRatio(100, 4f, 0f))
    }

    @Test
    fun `double-clicking the separator saves a ratio for the longest name, and a drag away from it does not`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val short = ratioAfterDoubleClick(listOf("A.kt", "B.kt"), at = separatorX())
            val long = ratioAfterDoubleClick(listOf("A.kt", "AVeryLongFileNameForTheFitTest.kt"), at = separatorX())
            val elsewhere = ratioAfterDoubleClick(listOf("AVeryLongFileNameForTheFitTest.kt"), at = 100f)

            assertEquals(SortSettingsCodec.MIN_SPLIT_RATIO, short)
            assertTrue(long != null && long > SortSettingsCodec.MIN_SPLIT_RATIO && long < SortSettingsCodec.MAX_SPLIT_RATIO, "$long")
            assertNull(elsewhere)
        }

    /** Where the separator is at the default ratio: the icon area, the name column and half the gap. */
    private fun separatorX() = 32f + (900f - 32f - 16f - 16f) * view.splitRatio + 8f

    private suspend fun ratioAfterDoubleClick(names: List<String>, at: Float): Float? {
        val files = names.map { FileItem(item = it, key = it, path = "src/$it", kind = FileChangeKind.Modified) }
        val rows = buildFileRows(files, view, isCollapsed = { false })
        var saved: Float? = null
        val scene = ImageComposeScene(width = 900, height = 400, density = Density(1f)) {
            AppTheme {
                ChangedFilesList(
                    rows = rows,
                    viewState = view,
                    selectedEntries = emptyList(),
                    listState = LazyListState(),
                    resetKey = null,
                    fileIcon = { Icons.Default.ArrowForward },
                    fileIconColor = { Color.Gray },
                    onFileClick = {},
                    onFolderToggle = {},
                    onSplitRatioChange = { saved = it },
                    onGenerateContextMenu = { emptyList() },
                )
            }
        }

        try {
            var time = 0L
            scene.render()

            repeat(2) {
                for (type in listOf(PointerEventType.Press, PointerEventType.Release)) {
                    time += 10
                    scene.sendPointerEvent(
                        type,
                        Offset(at, 12f),
                        timeMillis = time,
                        buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press),
                        button = PointerButton.Primary,
                    )
                }
                time += 100
                delay(20)
                scene.render(time * 1_000_000)
            }
            repeat(10) {
                delay(20)
                scene.render(System.nanoTime())
            }
        } finally {
            scene.close()
        }

        return saved
    }
}
