// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.sorting.FileChangeKind
import dev.app.leaf.domain.sorting.FileItem
import dev.app.leaf.domain.sorting.FileSortKey
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RenamedFrom
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where a renamed file came from, after its row, in each way that the list shows files. */
class ChangedFilesListRenamesTest {
    private val renamed = file("src/parse/Parser.kt", FileChangeKind.Renamed, RenamedFrom("src/parse/Lexer.kt", 92))
    private val moved = file("src/ui/components/Button.kt", FileChangeKind.Renamed, RenamedFrom("src/ui/old/Button.kt", 100))
    private val both = file("src/lexer/Tokens.kt", FileChangeKind.Renamed, RenamedFrom("src/parse/Lex.kt", 70))
    private val plain = file("src/Theme.kt", FileChangeKind.Modified, null)
    private val files = listOf(renamed, moved, both, plain)

    private val views = listOf(
        FilesViewState(FileSortKey.FileName, viewMode = FilesViewMode.FlatList),
        FilesViewState(FileSortKey.Path, viewMode = FilesViewMode.FlatList),
        FilesViewState(viewMode = FilesViewMode.SplitColumns),
        FilesViewState(viewMode = FilesViewMode.FolderTree),
    )

    @Test
    fun `every view shows the part of the old path that changed, for the renamed files only`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (view in views) {
                val hints = texts(files, view).filter { it.startsWith("←") }

                assertEquals(
                    setOf("← Lexer.kt", "← …/old/", "← …/parse/Lex.kt"),
                    hints.toSet(),
                    "${view.viewMode} sorted by ${view.sortKey}",
                )
                assertEquals(3, hints.size, "${view.viewMode} sorted by ${view.sortKey}")
            }
        }

    @Test
    fun `a list without renames shows no hints, as Staged and Unstaged don't`(): Unit = runBlocking(Dispatchers.Swing) {
        for (view in views) {
            assertEquals(emptyList<String>(), texts(listOf(plain), view).filter { it.startsWith("←") })
        }
    }

    @Test
    fun `a renamed file's name and directory are still shown`(): Unit = runBlocking(Dispatchers.Swing) {
        val split = texts(listOf(renamed), views[2])

        // After the column headings. The hint leads the directory, which gives way to it when the pane is narrow
        assertEquals(listOf("Parser.kt", "\u2190 Lexer.kt", "src/parse"), split.drop(2).map { it.trimEnd('/') })
    }

    @Test
    fun `the hint comes right after the name, ahead of the directory, which is what the pane cuts short`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Flat, by file name: name, hint, directory
            assertEquals(
                listOf("Parser.kt", "\u2190 Lexer.kt", "src/parse"),
                texts(listOf(renamed), views[0]).map { it.trimEnd('/') },
            )
            // Flat, by path: directory, then the name, then the hint
            assertEquals(
                listOf("src/parse", "", "Parser.kt", "\u2190 Lexer.kt"),
                texts(listOf(renamed), views[1]).map { it.trimEnd('/') },
            )
        }

    private fun texts(files: List<FileItem<String>>, view: FilesViewState): List<String> {
        val rows = buildFileRows(files, view, isCollapsed = { false })
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
                    onSplitRatioChange = {},
                    onGenerateContextMenu = { emptyList() },
                )
            }
        }

        try {
            scene.render()

            return scene.semanticsOwners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        } finally {
            scene.close()
        }
    }

    private fun file(path: String, kind: FileChangeKind, renamedFrom: RenamedFrom?) =
        FileItem(item = path, key = path, path = path, kind = kind, renamedFrom = renamedFrom)
}
