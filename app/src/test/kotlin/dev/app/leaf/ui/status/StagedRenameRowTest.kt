// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RenamedFrom
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.sorting.toFileItems
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.statusEntryContextMenuItems
import dev.app.leaf.ui.diff.LineAction
import dev.app.leaf.ui.diff.lineActionsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val DIR = "app/src/main/kotlin/dev/app/leaf/ui"
private val RENAME = StatusEntry(
    "$DIR/changes/StatusPane.kt",
    StatusType.RENAMED,
    EntryType.STAGED,
    renamedFrom = RenamedFrom("$DIR/status/StatusPane.kt", 92),
)
private val SAME_FOLDER = StatusEntry(
    "$DIR/FileList.kt",
    StatusType.RENAMED,
    EntryType.STAGED,
    renamedFrom = RenamedFrom("$DIR/ChangedFilesList.kt", 100),
)
private val STRINGS = StatusEntry("app/src/main/composeResources/values/strings.xml", StatusType.MODIFIED, EntryType.STAGED)

/**
 * A staged rename in the Staged list (fork-only, upstream issue 347, `docs/fork/status-renames.md`): its row says where
 * the file came from, as a commit's does, its menu undoes the move and opens the old path's history, and its diff's
 * lines can be unstaged. Set `LEAF_RENDER_DIR` and pass `--rerun` for PNGs of the list in two styles and both themes.
 */
class StagedRenameRowTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    @Test
    fun `a rename row shows the part of the old path that differs, after the name`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render()

            val texts = texts()

            assertTrue(texts.any { it.startsWith("←") && "…/status/" in it }, texts.toString())
            assertTrue(texts.any { it.startsWith("←") && "ChangedFilesList.kt" in it }, texts.toString())
            assertTrue("StatusPane.kt" in texts && "FileList.kt" in texts, texts.toString())
        }

    @Test
    fun `a rename row's menu undoes the move, and its history is the old path's`() {
        val entries = menu(RENAME)
        val labels = labelsOf(entries)

        assertEquals(
            listOf(
                "Undo the move…",
                "Blame file",
                "File history",
                "Open file in folder",
                "Copy path",
                "Copy relative path",
            ),
            labels,
        )
        assertEquals("before the move", hintOf(entries[labels.indexOf("File history")]))
    }

    @Test
    fun `other staged rows keep Discard file changes and no hint`() {
        val entries = menu(STRINGS)
        val labels = labelsOf(entries)

        assertEquals("Discard file changes", labels.first())
        assertEquals(null, hintOf(entries[labels.indexOf("File history")]))
    }

    @Test
    fun `a staged rename's lines can be unstaged, as a modified file's`() {
        assertEquals(listOf(LineAction.UNSTAGE), lineActionsOf(DiffType.UncommittedDiff(RENAME, EntryType.STAGED)))
    }

    @Test
    fun `renders the list in two styles and both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC)) {
            for (theme in listOf(Theme.Dark, Theme.Light)) {
                runBlocking(Dispatchers.Swing) {
                    render(theme, style)
                    val image = scene!!.render(16_000_000L * 21)

                    File(folder, "staged-renames-${style.id}-${theme.name.lowercase()}.png")
                        .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    closeScene()
                }
            }
        }
    }

    private fun render(theme: Theme = Theme.Dark, style: StyleDefinition = BuiltInStyles.DEFAULT) {
        val staged = listOf(SAME_FOLDER, RENAME, STRINGS)
        val viewState = FilesViewState()
        val state = StatusState(
            isLoading = false,
            staged = staged,
            stagedRows = buildFileRows(staged.toFileItems(), viewState) { false },
            viewState = viewState,
        )

        scene = ImageComposeScene(width = 330, height = 200) {
            AppTheme(theme, style = style) {
                StatusChangesList(
                    entryType = EntryType.STAGED,
                    statusState = state,
                    showSearch = false,
                    searchFilter = TextFieldValue(""),
                    listState = rememberLazyListState(),
                    selectedEntries = emptyList<DiffType.UncommittedDiff>(),
                    onSearchFilterToggled = {},
                    onSearchFocused = {},
                    onBlameFile = {},
                    onHistoryFile = {},
                    onDiscardFolderChanges = { _, _ -> },
                    onIgnore = {},
                    onConfirmAction = { _, onConfirm -> onConfirm() },
                    onAction = {},
                )
            }
        }

        var frameTime = 0L
        repeat(20) {
            scene!!.render(frameTime)
            frameTime += 16_000_000L
        }
    }

    private fun menu(entry: StatusEntry) = statusEntryContextMenuItems(
        statusEntry = entry,
        entryType = EntryType.STAGED,
        onReset = {},
        onBlame = {},
        onHistory = {},
        onCopyFilePath = {},
        onOpenFileInFolder = {},
    ).filterIsInstance<ContextMenuElement.ContextTextEntry>()

    /** The items' labels, read in a composition as the menu reads them. */
    private fun labelsOf(items: List<ContextMenuElement.ContextTextEntry>): List<String> =
        inComposition { items.map { it.composableLabel() } }

    private fun hintOf(item: ContextMenuElement.ContextTextEntry): String? = inComposition { item.hint?.invoke() }

    private fun <T> inComposition(read: @androidx.compose.runtime.Composable () -> T): T {
        var value: T? = null
        var isRead = false
        val scene = ImageComposeScene(width = 10, height = 10) {
            value = read()
            isRead = true
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        check(isRead)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }
}
