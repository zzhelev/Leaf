// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editing.changedTypedLines
import dev.app.leaf.domain.editing.hunkEditOf
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.repositoryopen.HunkEditLeave
import dev.app.leaf.repositoryopen.HunkEditState
import dev.app.leaf.repositoryopen.canEditHunk
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val PATH = "src/RestoreWorkingFile.kt"

/** A hunk edited in place, as the diff pane shows it (fork-only, `docs/fork/inline-editing.md`). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class HunkEditorViewTest {
    private var scene: ImageComposeScene? = null
    private val calls = mutableListOf<String>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private val old = listOf("val restore = true", "    // git deletes the file first", "    val backup = moveAside(file)", "    run(git)", "}")
    private val new = listOf("val restore = true", "    // git deletes or empties the file first", "    val backup = moveAside(file, gitDir)", "        ?: return", "    run(git)", "}")
    private val hunk = Hunk(
        header = "@@ -1,5 +1,6 @@ fun restore()",
        lines = listOf(
            Line(old[0], 0, 0, LineType.CONTEXT),
            Line(old[1], 1, 1, LineType.REMOVED),
            Line(old[2], 2, 1, LineType.REMOVED),
            Line(new[1], 3, 1, LineType.ADDED),
            Line(new[2], 3, 2, LineType.ADDED),
            Line(new[3], 3, 3, LineType.ADDED),
            Line(old[3], 3, 4, LineType.CONTEXT),
            Line(old[4], 4, 5, LineType.CONTEXT),
        ),
        oldText = RawText(old.joinToString("") { "$it\n" }.toByteArray()),
        newText = RawText(new.joinToString("") { "$it\n" }.toByteArray()),
    )
    private val diffType = DiffType.UncommittedDiff(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED)
    private val diff = ViewDiffResult.Loaded(diffType, DiffResult.Text(diffEntry(), listOf(hunk)))

    private fun diffEntry(): DiffEntry = object : DiffEntry() {
        init {
            changeType = ChangeType.MODIFY
            oldPath = PATH
            newPath = PATH
            oldId = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
            newId = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
        }
    }

    private fun stateOf(text: String? = null, leave: HunkEditLeave? = null): HunkEditState {
        val edit = hunkEditOf(PATH, hunk)!!
        val shown = text ?: edit.text

        return HunkEditState(
            diff = diff,
            hunk = hunk,
            edit = edit,
            value = TextFieldValue(shown, TextRange(shown.length)),
            changedLines = changedTypedLines(edit, shown),
            syntax = null,
            leave = leave,
        )
    }

    /** The diff's hunks with an edit that the test holds, as the view model would. */
    private var edit by mutableStateOf<HunkEditState?>(null)

    private fun editing() = HunkEditing(
        state = edit,
        canEdit = { canEditHunk(diffType, it) },
        onStart = { hunk, line ->
            calls += "start $line"
            edit = stateOf()
        },
        onType = { value ->
            calls += "type"
            edit = edit?.copy(value = value)
        },
        onDone = { calls += "done" },
        onCancel = { calls += "cancel" },
    )

    private fun show(theme: Theme = Theme.Light, split: Boolean = false, width: Int = 900, content: (@Composable () -> Unit)? = null) {
        scene = ImageComposeScene(width = width, height = 420, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = theme) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    content?.invoke()

                    CompositionLocalProvider(LocalHunkEditing provides editing()) {
                        if (split) {
                            HunkSplitTextDiff(
                                diffType = diffType,
                                scrollState = LazyListState(),
                                diffResult = DiffResult.TextSplit(diffEntry(), listOf(SplitHunk(hunk, hunk.lines.map { it to it }))),
                                canUseHunkActions = true,
                                onUnstageHunk = { _, _ -> },
                                onStageHunk = { _, _ -> },
                                onResetHunk = { _, _ -> },
                                onUnStageLine = { _, _, _ -> },
                                onDiscardLine = { _, _, _ -> },
                            )
                        } else {
                            HunkUnifiedTextDiff(
                                diffType = diffType,
                                scrollState = LazyListState(),
                                diffResult = diff.diffResult as DiffResult.Text,
                                canUseHunkActions = true,
                                onUnstageHunk = { _, _ -> },
                                onStageHunk = { _, _ -> },
                                onUnStageLine = { _, _, _ -> },
                                onResetHunk = { _, _ -> },
                                onDiscardLine = { _, _, _ -> },
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun settle() {
        repeat(5) {
            scene!!.render()
            delay(20)
        }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    private fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    private fun clickable(label: String) = nodes().single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }

    private fun field() = nodes().single { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Lines of RestoreWorkingFile.kt")
    }

    private fun press(key: Key, command: Boolean = false) {
        val mac = currentOs == OS.MAC
        scene!!.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = command && mac, isCtrlPressed = command && !mac))
        scene!!.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isMetaPressed = command && mac, isCtrlPressed = command && !mac))
    }

    @Test
    fun `an Unstaged hunk offers Edit, which starts editing`(): Unit = runBlocking(Dispatchers.Swing) {
        show()
        settle()

        assertTrue("Stage hunk" in texts() && "Discard hunk" in texts())
        clickable("Edit").config[SemanticsActions.OnClick].action!!.invoke()
        settle()

        assertEquals(listOf("start null"), calls)
    }

    @Test
    fun `Edit, Discard hunk and Stage hunk are 8 dp apart, and end where the editing controls do`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show()
            settle()

            val edit = clickable("Edit").boundsInRoot
            val discard = clickable("Discard hunk").boundsInRoot
            val stage = clickable("Stage hunk").boundsInRoot
            assertEquals(8f, discard.left - edit.right, 0.5f)
            assertEquals(8f, stage.left - discard.right, 0.5f)

            clickable("Edit").config[SemanticsActions.OnClick].action!!.invoke()
            settle()
            assertEquals(stage.right, clickable("Done").boundsInRoot.right, 0.5f)
        }

    @Test
    fun `the diff's header puts an asterisk after the name while typed lines aren't saved`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var notSaved by mutableStateOf(false)
            scene = ImageComposeScene(width = 900, height = 60, density = Density(1f), coroutineContext = Dispatchers.Swing) {
                AppTheme(selectedTheme = Theme.Light) {
                    DiffHeader(
                        diffType = diffType,
                        diffEntry = diffEntry(),
                        textDiffViewType = DiffTextViewType.Unified,
                        isDisplayFullFile = false,
                        isTextDiff = true,
                        onCloseDiffView = null,
                        headerActions = null,
                        onStageFile = {},
                        onUnstageFile = {},
                        onChangeDiffType = {},
                        onDisplayFullFile = {},
                        viewMenu = {},
                        logToggle = null,
                        isNotSaved = notSaved,
                    )
                }
            }
            settle()
            assertTrue("RestoreWorkingFile.kt" in texts() && "RestoreWorkingFile.kt*" !in texts(), texts().toString())
            assertTrue(notSavedNodes().isEmpty())

            notSaved = true
            settle()
            assertTrue("RestoreWorkingFile.kt*" in texts() && "RestoreWorkingFile.kt" !in texts(), texts().toString())
            assertEquals(1, notSavedNodes().size, "read out as not saved")
        }

    private fun notSavedNodes() = nodes().filter { it.config.getOrNull(SemanticsProperties.StateDescription) == "Not saved" }

    @Test
    fun `while editing, the hunk has the field in place of its new side, and its removed lines above`(): Unit =
        runBlocking(Dispatchers.Swing) {
            edit = stateOf()
            show()
            settle()

            assertTrue("Editing" in texts() && "Cancel" in texts() && "Done" in texts(), texts().toString())
            assertFalse("Stage hunk" in texts() || "Discard hunk" in texts() || "Edit" in texts(), "the hunk's buttons go")
            assertTrue(old[1] in texts() && old[2] in texts(), "the removed lines stay")
            assertFalse(new[3] in texts(), "the added lines are in the field, not as rows")

            val field = field()
            assertEquals(stateOf().value.text, field.config.getOrNull(SemanticsProperties.EditableText)?.text)

            clickable("Cancel").config[SemanticsActions.OnClick].action!!.invoke()
            clickable("Done").config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf("cancel", "done"), calls)
        }

    @Test
    fun `typing goes to the edit, and the shortcut is Done`(): Unit = runBlocking(Dispatchers.Swing) {
        edit = stateOf()
        show()
        settle()

        field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(androidx.compose.ui.text.AnnotatedString("\n    log(backup)"))
        settle()
        assertTrue(edit!!.value.text.endsWith("}\n    log(backup)"), edit!!.value.text)
        assertTrue("type" in calls)

        press(Key.Enter, command = true)
        settle()
        assertEquals("done", calls.last())
    }

    @Test
    fun `a click in the field puts the caret there, though the diff's lines are selectable text`(): Unit =
        runBlocking(Dispatchers.Swing) {
            edit = stateOf()
            show()
            settle()

            val bounds = field().boundsInRoot
            // The first line, near its start: the caret goes before the text's end, where it started
            val point = androidx.compose.ui.geometry.Offset(bounds.left + 12f, bounds.top + 8f)
            scene!!.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, point)
            scene!!.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point)
            scene!!.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point)
            settle()

            assertTrue(edit!!.value.selection.end < 5, "the caret moved to the first line: ${edit!!.value.selection}")

            // And what's typed goes there
            field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(androidx.compose.ui.text.AnnotatedString("x"))
            settle()
            assertTrue('x' in edit!!.value.text.substring(0, 5), edit!!.value.text)
        }

    @Test
    fun `while one hunk is edited, the others have no buttons`(): Unit = runBlocking(Dispatchers.Swing) {
        val second = hunk.copy(header = "@@ -9,1 +10,1 @@ fun later()")
        val twoHunks = DiffResult.Text(diffEntry(), listOf(hunk, second))
        edit = stateOf()
        scene = ImageComposeScene(width = 900, height = 600, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = Theme.Light) {
                CompositionLocalProvider(LocalHunkEditing provides editing()) {
                    HunkUnifiedTextDiff(
                        diffType = diffType,
                        scrollState = LazyListState(),
                        diffResult = twoHunks,
                        canUseHunkActions = true,
                        onUnstageHunk = { _, _ -> },
                        onStageHunk = { _, _ -> },
                        onUnStageLine = { _, _, _ -> },
                        onResetHunk = { _, _ -> },
                        onDiscardLine = { _, _, _ -> },
                    )
                }
            }
        }
        settle()

        assertTrue("@@ -9,1 +10,1 @@ fun later()" in texts())
        assertFalse("Stage hunk" in texts() || "Discard hunk" in texts() || "Edit" in texts(), texts().toString())
    }

    @Test
    fun `the split view shows the edited hunk as unified does`(): Unit = runBlocking(Dispatchers.Swing) {
        edit = stateOf()
        show(split = true)
        settle()

        assertTrue("Editing" in texts())
        assertEquals(stateOf().value.text, field().config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    @Test
    fun `a line's Edit these lines starts at that line, a removed one where it was`() {
        val editing = editing()

        editLinesAction(editing, hunk, hunk.lines[4])!!.invoke()
        assertEquals("start 3", calls.single())

        edit = null
        editLinesAction(editing(), hunk, hunk.lines[1])!!.invoke()
        assertEquals("start 2", calls.last())

        edit = stateOf()
        assertEquals(null, editLinesAction(editing(), hunk, hunk.lines[0]), "one edit at a time")
    }

    @Test
    fun `leaving with lines typed asks Save, Discard or Keep editing`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = stateOf(text = "typed", leave = HunkEditLeave(then = null))
        scene = ImageComposeScene(width = 900, height = 80, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = Theme.Light) {
                HunkEditLeaveStrip(
                    state = state,
                    onSave = { calls += "save" },
                    onDiscard = { calls += "discard" },
                    onKeepEditing = { calls += "keep" },
                )
            }
        }
        settle()

        assertTrue("You moved on from RestoreWorkingFile.kt with edits that aren't saved." in texts(), texts().toString())
        listOf("Save", "Discard", "Keep editing").forEach { clickable(it).config[SemanticsActions.OnClick].action!!.invoke() }
        assertEquals(listOf("save", "discard", "keep"), calls)
    }

    @Test
    fun `renders the hunk being edited when asked`(): Unit = runBlocking(Dispatchers.Swing) {
        val renderDir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            for (split in listOf(false, true)) {
                edit = null
                show(theme, split, width = 1100)
                settle()
                File(renderDir, "hunk-edit-offered-${theme.name.lowercase()}${if (split) "-split" else ""}.png")
                    .writeBytes(scene!!.render().encodeToData()!!.bytes)
                scene!!.close()

                edit = stateOf(text = stateOf().value.text.replace("    run(git)", "    run(git)\n    forget(path)"))
                show(theme, split, width = 1100) {
                    edit?.let { HunkEditLeaveStrip(it.copy(leave = HunkEditLeave(then = null)), {}, {}, {}) }
                }
                settle()
                File(renderDir, "hunk-edit-editing-${theme.name.lowercase()}${if (split) "-split" else ""}.png")
                    .writeBytes(scene!!.render().encodeToData()!!.bytes)
                scene!!.close()
                scene = null
            }
        }
    }
}
