// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.sorting.toFileItems
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")
private val HEAD = Commit("a1".padEnd(40, '0'), "Show the amended commit's files", someone, someone, 1_760_000_000, emptyList())

private class CommitFile(type: ChangeType, oldPath: String, newPath: String, score: Int = 0) : DiffEntry() {
    init {
        changeType = type
        this.oldPath = oldPath
        this.newPath = newPath
        this.score = score
    }
}

private val DIR = "app/src/main/kotlin/dev/app/leaf/ui/status"
private val STATE = CommitFile(ChangeType.MODIFY, "$DIR/StatusState.kt", "$DIR/StatusState.kt")
private val PANE = CommitFile(ChangeType.MODIFY, "$DIR/StatusPane.kt", "$DIR/StatusPane.kt")
private val AMENDED = CommitFile(
    ChangeType.ADD, DiffEntry.DEV_NULL, "domain/src/main/kotlin/dev/app/leaf/domain/amend/AmendedFiles.kt",
)
private val TEST = CommitFile(
    ChangeType.ADD, DiffEntry.DEV_NULL, "domain/src/test/kotlin/dev/app/leaf/domain/amend/AmendedFilesTest.kt",
)
private val RENAMED = CommitFile(ChangeType.RENAME, "$DIR/Lexer.kt", "$DIR/parse/Lexer.kt", score = 92)
private val LAST_COMMIT = listOf(STATE, PANE, AMENDED, TEST)

private val STAGED = listOf(
    StatusEntry("$DIR/StatusPane.kt", StatusType.MODIFIED, EntryType.STAGED),
    StatusEntry("app/src/main/composeResources/values/strings.xml", StatusType.MODIFIED, EntryType.STAGED),
)

/**
 * The Staged pane with Amend on (fork-only, upstream issue 260), rendered offscreen at the pane's default width: the
 * amended commit's files after Staged's own rows, what a click does, and that nothing there can be staged. Set
 * `LEAF_RENDER_DIR` to a folder to get a PNG of every state in two styles and both themes, and pass `--rerun`: Gradle
 * doesn't see an environment variable change.
 */
class AmendedFilesSectionTest {
    private val actions = mutableListOf<StatusAction>()
    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    private fun group(
        changes: List<DiffEntry> = LAST_COMMIT,
        viewState: FilesViewState = FilesViewState(),
        isCollapsed: Boolean = false,
        error: dev.app.leaf.domain.errors.AppError? = null,
    ) = amendedGroup(
        AmendedFiles(HEAD, changes, error = error),
        viewState,
        searchFilter = null,
        isCollapsed = isCollapsed,
        folders = CollapsedFolders(),
    )

    private fun state(
        staged: List<StatusEntry> = STAGED,
        amended: AmendedGroup? = group(),
        selected: DiffEntry? = null,
        viewState: FilesViewState = FilesViewState(),
    ) = StatusState(
        isLoading = false,
        staged = staged,
        stagedRows = buildFileRows(staged.toFileItems(), viewState) { false },
        viewState = viewState,
        amended = amended,
        selectedAmendedEntry = selected,
        isAmend = amended != null,
    )

    // What shows

    @Test
    fun `the group follows Staged's rows, under its header with the count`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())

        val texts = texts()

        assertTrue("In the last commit" in texts, texts.toString())
        assertTrue("4" in texts, texts.toString())

        val order = listOf("strings.xml", "In the last commit", "AmendedFiles.kt", "AmendedFilesTest.kt", "StatusState.kt")
        val positions = order.map { name -> nodes().first { name in it.texts() }.boundsInWindow.top }

        assertEquals(positions.sorted(), positions, "Staged's strings.xml, then the group's header, then its files")
    }

    @Test
    fun `without Amend there is no group`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(amended = null))

        assertFalse("In the last commit" in texts())
        assertFalse("AmendedFiles.kt" in texts())
    }

    @Test
    fun `Unstaged never shows the group`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(), entryType = EntryType.UNSTAGED)

        assertFalse("In the last commit" in texts(), texts().toString())
        assertFalse("AmendedFiles.kt" in texts(), texts().toString())
    }

    @Test
    fun `nothing staged still shows the commit's files`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(staged = emptyList()))

        val texts = texts()

        assertTrue("In the last commit" in texts, texts.toString())
        assertTrue("AmendedFiles.kt" in texts && "StatusState.kt" in texts, texts.toString())
        assertFalse("strings.xml" in texts)
    }

    @Test
    fun `a renamed file shows where it came from`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(amended = group(changes = listOf(RENAMED))))

        assertTrue(texts().any { it.startsWith("←") }, texts().toString())
    }

    // Clicks

    @Test
    fun `a click on a file of the group opens that commit's diff of it, and stages nothing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(state())

            click("AmendedFiles.kt")

            assertEquals(listOf<StatusAction>(StatusAction.OpenAmendedFile(AMENDED)), actions)
        }

    @Test
    fun `a click on the header closes the group, and on it again opens it`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())

        click("In the last commit")

        assertEquals(listOf<StatusAction>(StatusAction.ToggleAmendedGroup), actions)
    }

    @Test
    fun `a closed group is its header and the count`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state(amended = group(isCollapsed = true)))

        val texts = texts()

        assertTrue("In the last commit" in texts && "4" in texts, texts.toString())
        assertFalse("AmendedFiles.kt" in texts)
        // Staged's own rows stay
        assertTrue("strings.xml" in texts)
    }

    @Test
    fun `the hover button is Staged's own, not the group's`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())

        hover("strings.xml")
        assertTrue("Unstage" in texts(), texts().toString())

        hover("AmendedFiles.kt")
        assertFalse("Unstage" in texts(), texts().toString())
    }

    @Test
    fun `a double click on a file of the group does not unstage anything`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())

        click("AmendedFiles.kt")
        click("AmendedFiles.kt")

        assertTrue(actions.none { it is StatusAction.EntryAction }, actions.toString())
    }

    // The folder tree

    @Test
    fun `in the tree a folder that Staged and the group share shows in both, and a click closes the group's`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val tree = FilesViewState(viewMode = FilesViewMode.FolderTree)

            // The same folder, in both lists, has the same row key: the list would refuse to lay them out
            render(state(viewState = tree, amended = group(viewState = tree)))

            // Staged's tree shows it as kotlin/dev/app/leaf/ui/status, the group's as the whole path
            val folders = nodes().filter { node -> node.texts().any { it.endsWith("dev/app/leaf/ui/status") } }

            assertEquals(2, folders.size, texts().toString())

            click(folders.last().texts().first { it.endsWith("dev/app/leaf/ui/status") })

            assertEquals(1, actions.size)
            assertTrue(actions.single() is StatusAction.AmendedDirectoryToggle, actions.toString())
        }

    // A failure

    @Test
    fun `a failure says so under the header, and Try again reads the files again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(state(amended = group(changes = emptyList(), error = GenericError("no objects"))))

            val texts = texts()

            assertTrue("Couldn't list the files" in texts, texts.toString())
            // The count is for files that are known
            assertFalse("0" in texts)

            click("Try again")

            assertEquals(listOf<StatusAction>(StatusAction.RetryAmendedFiles), actions)
        }

    // The selected file

    @Test
    fun `the file whose diff is open is highlighted, and only that one`(): Unit = runBlocking(Dispatchers.Swing) {
        render(state())
        val plain = rowBackground("AmendedFiles.kt")
        val otherPlain = rowBackground("StatusState.kt")
        closeScene()

        render(state(selected = AMENDED))

        assertNotEquals(plain, rowBackground("AmendedFiles.kt"), "the open file's row has the selected fill")
        assertEquals(otherPlain, rowBackground("StatusState.kt"), "another file's row is as it was")
    }

    @Test
    fun `the group's file names are a little quieter than Staged's, in both themes`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (theme in listOf(Theme.Dark, Theme.Light)) {
                render(state(), theme)

                // The strongest ink in the name's box: its stems, at the colour that the name is drawn in
                val staged = ink("strings.xml", theme)
                val group = ink("AmendedFiles.kt", theme)

                assertTrue(group < staged, "$theme: the group's name ($group) is quieter than Staged's ($staged)")
                // Only a little: the name stays easy to read
                assertTrue(staged - group < 0.35f, "$theme: $staged against $group")
                closeScene()
            }
        }

    // Look

    @Test
    fun `renders every state in two styles and both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()
        val tree = FilesViewState(viewMode = FilesViewMode.FolderTree)
        val flat = FilesViewState(viewMode = FilesViewMode.FlatList)

        val cases = linkedMapOf(
            "several" to state(),
            "selected" to state(selected = AMENDED),
            "nothing-staged" to state(staged = emptyList()),
            "closed" to state(amended = group(isCollapsed = true)),
            "tree" to state(viewState = tree, amended = group(viewState = tree)),
            "flat" to state(viewState = flat, amended = group(viewState = flat)),
            "renamed" to state(amended = group(changes = LAST_COMMIT + RENAMED)),
            "failed" to state(amended = group(changes = emptyList(), error = GenericError("no objects"))),
            "off" to state(amended = null),
        )

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC)) {
            for (theme in listOf(Theme.Dark, Theme.Light)) {
                for ((name, value) in cases) {
                    runBlocking(Dispatchers.Swing) {
                        render(value, theme, style)
                        val image = scene!!.render(frameTime)

                        File(folder, "amend-$name-${style.id}-${theme.name.lowercase()}.png")
                            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        closeScene()
                    }
                }
            }
        }
    }

    private fun render(
        state: StatusState,
        theme: Theme = Theme.Dark,
        style: StyleDefinition = BuiltInStyles.DEFAULT,
        entryType: EntryType = EntryType.STAGED,
    ) {
        frameTime = 0L
        scene = ImageComposeScene(width = 330, height = 330) {
            AppTheme(theme, style = style) {
                StatusChangesList(
                    entryType = entryType,
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
                    onAction = { actions += it },
                )
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

    private fun center(text: String): Offset {
        val node = nodes().first { text in it.texts() }

        return Offset(node.boundsInWindow.center.x, node.boundsInWindow.center.y)
    }

    private fun hover(text: String) {
        scene!!.sendPointerEvent(PointerEventType.Move, center(text))
        // The button that the row before showed fades out first
        repeat(3) { settle() }
    }

    private fun click(text: String) {
        val scene = scene!!
        val position = center(text)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        settle()
    }

    /**
     * How far the strongest pixel in [text]'s box is from the pane's background, as a share of the way to the text colour
     * (white on the dark theme, black on the light one).
     */
    private fun ink(text: String, theme: Theme): Float {
        val bounds = nodes().first { text in it.texts() }.boundsInWindow
        val bitmap = Bitmap.makeFromImage(scene!!.render(frameTime))
        val background = bitmap.getColor(2, bounds.center.y.toInt()) and 0xFF
        var strongest = 0

        for (y in bounds.top.toInt()..bounds.bottom.toInt()) {
            for (x in bounds.left.toInt()..bounds.right.toInt()) {
                val channel = bitmap.getColor(x, y) and 0xFF
                strongest = maxOf(strongest, if (theme == Theme.Dark) channel - background else background - channel)
            }
        }

        val far = if (theme == Theme.Dark) 255 - background else background

        return strongest.toFloat() / far
    }

    /** The colour at the left edge of [text]'s row, where only the row's own fill shows. */
    private fun rowBackground(text: String): Int {
        val y = center(text).y.toInt()
        val bitmap = Bitmap.makeFromImage(scene!!.render(frameTime))

        return bitmap.getColor(2, y)
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
