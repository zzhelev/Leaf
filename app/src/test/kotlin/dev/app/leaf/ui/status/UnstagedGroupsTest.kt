// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.CollapsedGroups
import dev.app.leaf.domain.sorting.DiscardedFiles
import dev.app.leaf.domain.sorting.FileGroup
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.sorting.buildGroupedFileRows
import dev.app.leaf.domain.sorting.fileGroup
import dev.app.leaf.domain.sorting.toFileItems
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val UI = "app/src/main/kotlin/dev/app/leaf/ui"
private const val SORTING = "domain/src/main/kotlin/dev/app/leaf/domain/sorting"
private const val SORT_BUTTON = "Sort and group"
private const val GROUP_ITEM = "Group untracked files"
private const val STAGE_FOLDER = "Stage changes in the directory"
private const val DISCARD_FOLDER = "Discard changes"
private const val IGNORE_FOLDER = "Ignore folder…"

private val CHANGELOG = StatusEntry("docs/fork/CHANGELOG.md", StatusType.CONFLICTING)
private val CHANGED_FILES_LIST = StatusEntry("$UI/ChangedFilesList.kt", StatusType.MODIFIED)
private val FILE_ROWS = StatusEntry("$SORTING/FileRows.kt", StatusType.MODIFIED)
private val OLD_GROUPS = StatusEntry("$UI/status/OldGroups.kt", StatusType.REMOVED)
private val STATUS_PANE = StatusEntry("$UI/status/StatusPane.kt", StatusType.MODIFIED)
private val STRINGS = StatusEntry("app/src/main/composeResources/values/strings.xml", StatusType.MODIFIED)
private val FILE_GROUPS = StatusEntry("$SORTING/FileGroups.kt", StatusType.ADDED)
private val FILE_GROUPS_TEST = StatusEntry("domain/src/test/kotlin/dev/app/leaf/domain/sorting/FileGroupsTest.kt", StatusType.ADDED)
private val PLAN = StatusEntry("notes/plan.md", StatusType.ADDED)
private val UNSTAGED_GROUPS = StatusEntry("$UI/status/UnstagedGroups.kt", StatusType.ADDED)

/** Unstaged with a mix: an agent's edits, a deleted file, a conflict, and the new files it wrote next to them. */
private val UNSTAGED = listOf(
    STATUS_PANE, UNSTAGED_GROUPS, CHANGED_FILES_LIST, FILE_GROUPS, FILE_ROWS, STRINGS, OLD_GROUPS, CHANGELOG,
    FILE_GROUPS_TEST, PLAN,
)

/** The files in the order the grouped list shows them, by file name in each group. */
private val SHOWN_IN_GROUPS = listOf(
    CHANGELOG,
    CHANGED_FILES_LIST, FILE_ROWS, OLD_GROUPS, STATUS_PANE, STRINGS,
    FILE_GROUPS, FILE_GROUPS_TEST, PLAN, UNSTAGED_GROUPS,
)

private val GROUPED = FilesViewState(groupUntracked = true)
private val TREE = FilesViewState(viewMode = FilesViewMode.FolderTree, groupUntracked = true)

/**
 * Unstaged's files in groups (fork-only, upstream issue 345), rendered offscreen at the pane's default width: the
 * headers, the menu item that turns them on, a header's click and Stage, selection, Stage all and the arrow keys across
 * them, and a folder in two groups. Set `LEAF_RENDER_DIR` to a folder to get PNGs in two styles and both themes, and pass
 * `--rerun`: Gradle doesn't see an environment variable change.
 */
class UnstagedGroupsTest {
    private val actions = mutableListOf<StatusAction>()
    private val folderDiscards = mutableListOf<Pair<String, DiscardedFiles>>()
    private val ignores = mutableListOf<IgnoreRequest>()
    private var scene: ImageComposeScene? = null
    private var frameTime = 0L
    // A snapshot state, as a window's is: the list reads the keys when it composes
    private var keys by mutableStateOf(PointerKeyboardModifiers())

    private val window = object : WindowInfo {
        override val isWindowFocused = true
        override val keyboardModifiers: PointerKeyboardModifiers get() = keys
    }

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    private fun state(
        unstaged: List<StatusEntry> = UNSTAGED,
        viewState: FilesViewState = GROUPED,
        collapsed: CollapsedGroups = CollapsedGroups(),
    ) = StatusState(
        isLoading = false,
        unstaged = unstaged,
        unstagedRows = if (viewState.groupUntracked) {
            buildGroupedFileRows(
                unstaged.toFileItems(),
                groupOf = { it.item.fileGroup },
                state = viewState,
                isGroupCollapsed = { collapsed.isGroupCollapsed(it, isSearching = false) },
                isCollapsed = { group, path -> collapsed.isFolderCollapsed(group, path, isSearching = false) },
            )
        } else {
            buildFileRows(unstaged.toFileItems(), viewState) { false }
        },
        viewState = viewState,
    )

    // What shows

    @Test
    fun `with the setting off Unstaged is one list, as before`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(viewState = FilesViewState()))

        val texts = texts()

        for (title in listOf("Conflicts", "Changes", "Untracked")) {
            assertFalse(title in texts, "$title in $texts")
        }

        // A new file between two changed ones, by name
        assertInOrder("ChangedFilesList.kt", "FileGroups.kt", "FileRows.kt")
    }

    @Test
    fun `with it on the files are under Conflicts, Changes and Untracked, each with its count`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(state())

            assertInOrder(
                "Conflicts", "CHANGELOG.md",
                "Changes", "ChangedFilesList.kt", "FileRows.kt", "OldGroups.kt", "StatusPane.kt", "strings.xml",
                "Untracked", "FileGroups.kt", "FileGroupsTest.kt", "plan.md", "UnstagedGroups.kt",
            )
            assertEquals("1", textRightOf("Conflicts"))
            assertEquals("5", textRightOf("Changes"))
            assertEquals("4", textRightOf("Untracked"))
        }

    @Test
    fun `a group without files has no header`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(unstaged = UNSTAGED.filter { it.statusType != StatusType.CONFLICTING }))

        assertFalse("Conflicts" in texts(), texts().toString())
        assertInOrder("Changes", "Untracked")
    }

    @Test
    fun `a closed group is its header and the count, without its files`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(collapsed = CollapsedGroups().groupToggled(FileGroup.Untracked, isSearching = false)))

        val texts = texts()

        assertTrue("Untracked" in texts, texts.toString())
        assertEquals("4", textRightOf("Untracked"))
        assertFalse("plan.md" in texts, texts.toString())
        assertTrue("strings.xml" in texts, texts.toString())
    }

    // The menu

    @Test
    fun `Unstaged's sort menu has Group untracked files, which turns the groups on and off`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(state(viewState = FilesViewState()))
            clickDescription(SORT_BUTTON)
            click(GROUP_ITEM)

            assertEquals(StatusAction.ViewStateChanged(GROUPED), actions.last())
            closeScene()

            render(state())
            clickDescription(SORT_BUTTON)
            click(GROUP_ITEM)

            assertEquals(StatusAction.ViewStateChanged(FilesViewState()), actions.last())
        }

    @Test
    fun `Staged's sort menu has no such item`(): Unit = runBlocking(Dispatchers.Swing) {
        val staged = UNSTAGED.map { it.copy(entryType = EntryType.STAGED) }

        render(
            StatusState(
                isLoading = false,
                staged = staged,
                stagedRows = buildFileRows(staged.toFileItems(), GROUPED) { false },
                viewState = GROUPED,
            ),
            entryType = EntryType.STAGED,
        )
        clickDescription(SORT_BUTTON)

        assertTrue("Folder tree" in texts(), "the menu is open: ${texts()}")
        assertFalse(GROUP_ITEM in texts(), texts().toString())
        assertFalse("Untracked" in texts(), "Staged's rows have no groups: ${texts()}")
    }

    @Test
    fun `the sort button is lit in Unstaged while the groups are on, and not in Staged`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(state(viewState = FilesViewState()))
            val off = sortIconColor()
            closeScene()

            render(state())
            val on = sortIconColor()
            closeScene()

            val staged = UNSTAGED.map { it.copy(entryType = EntryType.STAGED) }

            render(
                StatusState(
                    isLoading = false,
                    staged = staged,
                    stagedRows = buildFileRows(staged.toFileItems(), GROUPED) { false },
                    viewState = GROUPED,
                ),
                entryType = EntryType.STAGED,
            )
            val stagedColor = sortIconColor()

            assertTrue(on != off, "lit: ${Integer.toHexString(on)} against ${Integer.toHexString(off)}")
            assertEquals(off, stagedColor, "Staged's button stays muted")
        }

    // A group's header

    @Test
    fun `a click on a group's header closes it or opens it, and selects nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())
        click("Untracked")

        assertEquals(listOf<StatusAction>(StatusAction.ToggleUnstagedGroup(FileGroup.Untracked)), actions)
    }

    @Test
    fun `pointing at a group's header shows Stage, which stages that group`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())

        assertFalse("Stage" in texts(), "no row is hovered yet")

        hover("Untracked")
        click("Stage")

        assertEquals(listOf<StatusAction>(StatusAction.StageGroup(FileGroup.Untracked)), actions)
    }

    // Selection, Stage all and the keys across the groups

    @Test
    fun `Shift-click selects across the groups, in the order they show`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(), selected = listOf(STATUS_PANE))

        keys = PointerKeyboardModifiers(isShiftPressed = true)
        settle()
        click("FileGroups.kt")

        val select = actions.single() as StatusAction.SelectEntry

        assertTrue(select.isShiftPressed)
        assertEquals(FILE_GROUPS, select.statusEntry)
        assertEquals(SHOWN_IN_GROUPS, select.diffEntries, "the range is taken in this order, headers left out")
    }

    @Test
    fun `a closed group's files are not in a Shift range`(): Unit = runBlocking(Dispatchers.Swing) {
        val collapsed = CollapsedGroups().groupToggled(FileGroup.Changes, isSearching = false)

        render(state(collapsed = collapsed), selected = listOf(CHANGELOG))

        keys = PointerKeyboardModifiers(isShiftPressed = true)
        settle()
        click("plan.md")

        val select = actions.single() as StatusAction.SelectEntry

        assertEquals(listOf(CHANGELOG, FILE_GROUPS, FILE_GROUPS_TEST, PLAN, UNSTAGED_GROUPS), select.diffEntries)
    }

    @Test
    fun `Stage all stages both groups, and a selection across them is Stage selected`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = state()

            render(state)
            click("Stage all")

            assertEquals(listOf<StatusAction>(StatusAction.AllEntriesAction(EntryType.UNSTAGED)), actions)
            assertEquals(BulkTarget.All, state.bulkTarget(EntryType.UNSTAGED, emptyList()))
            closeScene()

            render(state, selected = listOf(STRINGS, FILE_GROUPS))

            assertTrue("Stage selected" in texts(), texts().toString())
            assertEquals(
                BulkTarget.Selected(listOf(STRINGS, FILE_GROUPS)),
                state.bulkTarget(EntryType.UNSTAGED, listOf(STRINGS, FILE_GROUPS)),
            )
        }

    @Test
    fun `Down and Up pass over a group's header`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(), selected = listOf(STRINGS))
        click("strings.xml")
        actions.clear()

        press(Key.DirectionDown)

        assertEquals(FILE_GROUPS, (actions.single() as StatusAction.SelectEntry).statusEntry)
        closeScene()
        actions.clear()

        render(state(), selected = listOf(CHANGED_FILES_LIST))
        click("ChangedFilesList.kt")
        actions.clear()

        press(Key.DirectionUp)

        assertEquals(CHANGELOG, (actions.single() as StatusAction.SelectEntry).statusEntry)
    }

    @Test
    fun `at the last file Down stays, and at the first Up stays, as before`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(), selected = listOf(UNSTAGED_GROUPS))
        click("UnstagedGroups.kt")
        actions.clear()

        press(Key.DirectionDown)

        assertEquals(UNSTAGED_GROUPS, (actions.single() as StatusAction.SelectEntry).statusEntry)
        closeScene()
        actions.clear()

        render(state(), selected = listOf(CHANGELOG))
        click("CHANGELOG.md")
        actions.clear()

        press(Key.DirectionUp)

        assertEquals(CHANGELOG, (actions.single() as StatusAction.SelectEntry).statusEntry)
    }

    // The tree

    @Test
    fun `in the tree a folder in both groups has a row in each, and its Stage and fold are its group's`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val entries = listOf(
                StatusEntry("src/Old.kt", StatusType.MODIFIED),
                StatusEntry("src/New.kt", StatusType.ADDED),
            )

            render(state(entries, TREE))

            val folders = nodes().filter { "src" in it.texts() }

            assertEquals(2, folders.size, texts().toString())
            assertInOrder("Changes", "Old.kt", "Untracked", "New.kt")

            val untrackedFolder = folders.maxBy { it.boundsInWindow.top }

            clickAt(untrackedFolder.boundsInWindow.center)

            assertEquals(
                listOf<StatusAction>(StatusAction.TreeDirectoryToggle("src", EntryType.UNSTAGED, FileGroup.Untracked)),
                actions,
            )
            actions.clear()

            scene!!.sendPointerEvent(PointerEventType.Move, untrackedFolder.boundsInWindow.center)
            repeat(3) { settle() }
            click("Stage")

            assertEquals(
                listOf<StatusAction>(StatusAction.FolderRowAction("src", EntryType.UNSTAGED, FileGroup.Untracked)),
                actions,
            )
        }

    @Test
    fun `a folder's menu in a group stages, discards and ignores that group's files only`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val old = StatusEntry("src/Old.kt", StatusType.MODIFIED)
            val gone = StatusEntry("src/Gone.kt", StatusType.REMOVED)
            val new = StatusEntry("src/New.kt", StatusType.ADDED)

            render(state(listOf(old, gone, new), TREE))

            val (changesFolder, untrackedFolder) = nodes()
                .filter { "src" in it.texts() }
                .sortedBy { it.boundsInWindow.top }
                .map { it.boundsInWindow.center }

            rightClickAt(changesFolder)

            assertFalse(IGNORE_FOLDER in texts(), "Changes' folder has no new file to ignore: ${texts()}")
            click(DISCARD_FOLDER)

            assertEquals(listOf("src" to DiscardedFiles(listOf(old, gone), keptNewFiles = 0, keptConflicted = 0)), folderDiscards)

            rightClickAt(changesFolder)
            click(STAGE_FOLDER)

            assertEquals(
                listOf<StatusAction>(StatusAction.DirectoryAction("src", EntryType.UNSTAGED, FileGroup.Changes)),
                actions,
            )
            actions.clear()

            rightClickAt(untrackedFolder)

            assertFalse(DISCARD_FOLDER in texts(), "Untracked's folder has nothing to restore: ${texts()}")
            click(IGNORE_FOLDER)

            assertEquals(listOf<IgnoreRequest>(IgnoreRequest.Folder("src")), ignores)

            rightClickAt(untrackedFolder)
            click(STAGE_FOLDER)

            assertEquals(
                listOf<StatusAction>(StatusAction.DirectoryAction("src", EntryType.UNSTAGED, FileGroup.Untracked)),
                actions,
            )
        }

    @Test
    fun `without the groups a folder row stands for all its files, as before`(): Unit = runBlocking(Dispatchers.Swing) {
        val entries = listOf(
            StatusEntry("src/Old.kt", StatusType.MODIFIED),
            StatusEntry("src/New.kt", StatusType.ADDED),
        )

        render(state(entries, FilesViewState(viewMode = FilesViewMode.FolderTree)))
        click("src")

        assertEquals(listOf<StatusAction>(StatusAction.TreeDirectoryToggle("src", EntryType.UNSTAGED)), actions)
    }

    // Look

    @Test
    fun `renders the groups in two styles and both themes, next to a selected row, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        val cases = linkedMapOf(
            "columns" to (state() to listOf(STATUS_PANE, STRINGS, FILE_GROUPS)),
            "flat" to (state(viewState = GROUPED.copy(viewMode = FilesViewMode.FlatList)) to listOf(FILE_ROWS)),
            "tree" to (state(viewState = TREE) to listOf(FILE_GROUPS)),
            "closed" to (
                state(collapsed = CollapsedGroups().groupToggled(FileGroup.Untracked, isSearching = false)) to
                    listOf(STRINGS)
                ),
            "off" to (state(viewState = FilesViewState()) to listOf(FILE_ROWS)),
        )

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC)) {
            for (theme in listOf(Theme.Dark, Theme.Light)) {
                for ((name, case) in cases) {
                    runBlocking(Dispatchers.Swing) {
                        val (value, selected) = case

                        render(value, selected = selected, theme = theme, style = style, height = 600)

                        if (name == "columns") hover("Untracked")

                        val image = scene!!.render(frameTime)

                        File(folder, "unstaged-groups-$name-${style.id}-${theme.name.lowercase()}.png")
                            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        closeScene()
                    }
                }
            }
        }
    }

    private fun render(
        state: StatusState,
        selected: List<StatusEntry> = emptyList(),
        entryType: EntryType = EntryType.UNSTAGED,
        theme: Theme = Theme.Dark,
        style: StyleDefinition = BuiltInStyles.DEFAULT,
        height: Int = 640,
    ) {
        frameTime = 0L
        keys = PointerKeyboardModifiers()
        scene = ImageComposeScene(width = 330, height = height) {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                AppTheme(theme, style = style) {
                    StatusChangesList(
                        entryType = entryType,
                        statusState = state,
                        showSearch = false,
                        searchFilter = TextFieldValue(""),
                        listState = rememberLazyListState(),
                        selectedEntries = selected.map { DiffType.UncommittedDiff(it, entryType) },
                        onSearchFilterToggled = {},
                        onSearchFocused = {},
                        onBlameFile = {},
                        onHistoryFile = {},
                        onDiscardFolderChanges = { path, discard -> folderDiscards += path to discard },
                        onIgnore = { ignores += it },
                        onConfirmAction = { _, onConfirm -> onConfirm() },
                        onAction = { actions += it },
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        repeat(20) {
            scene!!.render(frameTime)
            frameTime += 16_000_000L
        }
    }

    private fun assertInOrder(vararg names: String) {
        val tops = names.map { name -> nodes().first { name in it.texts() }.boundsInWindow.top }

        assertEquals(tops.sorted(), tops, "${names.toList()} at $tops")
    }

    /** The text on the same line as [text], right of it: a header's count. */
    private fun textRightOf(text: String): String? {
        val bounds = nodes().first { text in it.texts() }.boundsInWindow

        return nodes()
            .filter { it.boundsInWindow.center.y in bounds.top..bounds.bottom && it.boundsInWindow.left > bounds.right }
            .flatMap { it.texts() }
            .lastOrNull()
    }

    private fun hover(text: String) {
        scene!!.sendPointerEvent(PointerEventType.Move, center(text))
        // The button that the row before showed fades out first
        repeat(3) { settle() }
    }

    private fun center(text: String): Offset {
        val node = nodes().first { text in it.texts() }

        return node.boundsInWindow.center
    }

    private fun click(text: String) = clickAt(center(text))

    private fun rightClickAt(position: Offset) {
        val scene = scene!!

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isSecondaryPressed = true),
            button = PointerButton.Secondary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
        settle()
    }

    private fun clickDescription(description: String) = clickAt(
        nodes().first { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .boundsInWindow.center
    )

    private fun clickAt(position: Offset) {
        val scene = scene!!

        scene.sendPointerEvent(PointerEventType.Move, position, keyboardModifiers = keys)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
            keyboardModifiers = keys,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary, keyboardModifiers = keys)
        settle()
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        settle()
    }

    /** The brightest pixel of the sort button's icon. */
    private fun sortIconColor(): Int {
        val bounds = nodes()
            .first { SORT_BUTTON in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .boundsInWindow
        val bitmap = Bitmap.makeFromImage(scene!!.render(frameTime))
        var brightest = 0

        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val color = bitmap.getColor(x, y)

                if (luminance(color) > luminance(brightest)) brightest = color
            }
        }

        return brightest and 0xFFFFFF
    }

    private fun luminance(color: Int) = (color shr 16 and 0xFF) + (color shr 8 and 0xFF) + (color and 0xFF)

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
