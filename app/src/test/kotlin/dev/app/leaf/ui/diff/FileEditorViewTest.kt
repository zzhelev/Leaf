// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editing.ChangeKind
import dev.app.leaf.domain.editing.ChangeMarker
import dev.app.leaf.domain.editing.changeMarkers
import dev.app.leaf.domain.editing.fileEditOf
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.repositoryopen.FileEditState
import dev.app.leaf.repositoryopen.HunkEditLeave
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val PATH = "src/demo/Parser.kt"

/** The whole file's editor, rendered (fork-only, `docs/fork/inline-editing.md`, B2). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FileEditorViewTest {
    private var scene: ImageComposeScene? = null
    private val calls = mutableListOf<String>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private val index = """
        package demo

        class Parser(private val text: String) {
            fun numbers(): List<Int> = text.lines().map { it.trim().toInt() }

            fun sum(): Int = numbers().sum()

            fun average(): Double {
                val all = numbers()
                return all.sum().toDouble() / all.size
            }

            fun largest(): Int = numbers().max()
        }
    """.trimIndent()
    private val working = index
        .replace("    fun sum(): Int = numbers().sum()\n\n", "    fun sum(): Int = numbers().sum()\n\n    fun count(): Int = numbers().size\n\n")
        .replace("        val all = numbers()\n", "        val all = numbers()\n        if (all.isEmpty()) return 0.0\n")
        .replace("    fun largest(): Int = numbers().max()\n", "")
        .replace("text.lines()", "text.lines().filter { it.isNotBlank() }")

    private var state by mutableStateOf<FileEditState?>(null)

    private fun stateOf(text: String = working, leave: HunkEditLeave? = null, openMarker: ChangeMarker? = null): FileEditState {
        val edit = fileEditOf(PATH, "$working\n".toByteArray())!!

        return FileEditState(
            diffType = DiffType.UncommittedDiff(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED),
            edit = edit,
            indexText = index,
            value = TextFieldValue(text, TextRange(text.indexOf("numbers()") + 2)),
            markers = changeMarkers(index, text),
            syntax = null,
            leave = leave,
            openMarker = openMarker,
        )
    }

    private fun editing() = FileEditing(
        state = state!!,
        onType = { value ->
            calls += "type"
            state = state!!.copy(value = value, markers = changeMarkers(index, value.text))
        },
        onSave = { calls += "save" },
        onSaveAndClose = { calls += "save and close" },
        onBack = { calls += "back" },
        onShowMarker = { marker ->
            calls += "show ${marker?.kind}"
            state = state!!.copy(openMarker = marker)
        },
        onPutBack = { calls += "put back ${it.kind}" },
        onLeaveSave = { calls += "leave save" },
        onLeaveDiscard = { calls += "leave discard" },
        onKeepEditing = { calls += "keep" },
    )

    private fun show(theme: Theme = Theme.Light, width: Int = 900, height: Int = 420, wrap: Boolean = true) {
        scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = theme) {
                state?.let { FileEditorPane(editing(), wrapLines = wrap) }
            }
        }
    }

    private suspend fun settle() {
        repeat(6) {
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

    private fun clickable(label: String) = nodes().first { node ->
        node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }

    private fun field() = nodes().single { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Text of Parser.kt")
    }

    @Test
    fun `the editor has the file, its header, and Edited once something isn't saved`(): Unit = runBlocking(Dispatchers.Swing) {
        state = stateOf()
        show()
        settle()

        assertEquals(working, field().config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertTrue("src/demo/" in texts() && "Parser.kt" in texts(), texts().toString())
        assertFalse("Edited" in texts(), "nothing typed yet")

        clickable("Save").config[SemanticsActions.OnClick].action!!.invoke()
        clickable("Back to diff").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("save", "back"), calls)

        field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("x"))
        settle()
        assertTrue("Edited" in texts())
    }

    @Test
    fun `the shortcut saves and goes back`(): Unit = runBlocking(Dispatchers.Swing) {
        state = stateOf()
        show()
        settle()

        val mac = currentOs == OS.MAC
        scene!!.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown, isMetaPressed = mac, isCtrlPressed = !mac))
        settle()

        assertEquals("save and close", calls.last())
    }

    @Test
    fun `a changed or removed line's marker shows the index's lines, with Put it back`(): Unit =
        runBlocking(Dispatchers.Swing) {
            state = stateOf()
            show()
            settle()

            val removed = state!!.markers.single { it.kind == ChangeKind.REMOVED }
            state = state!!.copy(openMarker = removed)
            settle()

            assertTrue("Removed here:" in texts(), texts().toString())
            assertTrue("    fun largest(): Int = numbers().max()" in texts())
            clickable("Put it back").config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals("put back REMOVED", calls.last())
        }

    @Test
    fun `a click on a changed line's marker opens it`(): Unit = runBlocking(Dispatchers.Swing) {
        state = stateOf()
        show()
        settle()

        // The changed line is the fourth (from 1): the field's lines are about 18 px, under 4 px of padding
        val field = field().boundsInRoot
        val changed = state!!.markers.first { it.kind == ChangeKind.CHANGED }
        val y = field.top + 18f * changed.start + 9f
        val x = field.left - 6f
        scene!!.sendPointerEvent(PointerEventType.Move, Offset(x, y))
        scene!!.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        scene!!.sendPointerEvent(PointerEventType.Release, Offset(x, y))
        settle()

        assertEquals("show CHANGED", calls.last(), calls.toString())
        assertTrue("Was:" in texts())
    }

    @Test
    fun `the question about lines not saved shows under the header`(): Unit = runBlocking(Dispatchers.Swing) {
        state = stateOf(text = "$working\n// more", leave = HunkEditLeave(then = null, isEscape = true))
        show()
        settle()

        assertTrue("Parser.kt has edits that aren't saved." in texts(), texts().toString())
        // The strip's, under the header's own Save
        listOf("Save", "Discard", "Keep editing").forEach { label ->
            nodes().filter { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            }.maxBy { it.boundsInRoot.top }.config[SemanticsActions.OnClick].action!!.invoke()
        }
        assertEquals(listOf("leave save", "leave discard", "keep"), calls)
    }

    @Test
    fun `the name under the caret is marked wherever it's used`() {
        val text = "val numbers = 1\nnumbers()\nnumbersAll()"
        val marks = OccurrenceMarks(VisualTransformation.None, listOf(4..10, 16..22)).filter(AnnotatedString(text))

        assertEquals(listOf(4 to 11, 16 to 23), marks.text.spanStyles.map { it.start to it.end })
    }

    @Test
    fun `renders the editor when asked`(): Unit = runBlocking(Dispatchers.Swing) {
        val renderDir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            state = stateOf(text = working.replace("count()", "count(  )"))
            show(theme, width = 1100, height = 520)
            settle()
            File(renderDir, "file-edit-${theme.name.lowercase()}.png").writeBytes(scene!!.render().encodeToData()!!.bytes)
            scene!!.close()

            state = stateOf(openMarker = stateOf().markers.single { it.kind == ChangeKind.REMOVED })
            show(theme, width = 1100, height = 520)
            settle()
            File(renderDir, "file-edit-popover-${theme.name.lowercase()}.png").writeBytes(scene!!.render().encodeToData()!!.bytes)
            scene!!.close()
            scene = null
        }
    }

    @Test
    fun `measures typing in long files when asked`(): Unit = runBlocking(Dispatchers.Swing) {
        System.getenv("LEAF_MEASURE") ?: return@runBlocking

        for (lines in listOf(1_000, 2_500, 5_000, 10_000)) {
            val text = (1..lines).joinToString("\n") { "    val value$it = compute($it, \"text of line $it\") + other(${it * 7})" }
            val edit = fileEditOf("Long.kt", "$text\n".toByteArray())
                ?: dev.app.leaf.domain.editing.FileEdit("Long.kt", "$text\n".toByteArray(), Charsets.UTF_8)
            state = FileEditState(
                diffType = DiffType.UncommittedDiff(StatusEntry("Long.kt", StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED),
                edit = edit,
                indexText = text,
                value = TextFieldValue(text, TextRange(text.length / 2)),
                markers = emptyList(),
                syntax = null,
            )
            show(width = 1100, height = 700)
            settle()

            val longField = nodes().single { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Text of Long.kt")
            }
            val times = (1..10).map {
                val start = System.nanoTime()
                longField.config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("x"))
                scene!!.render()
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()

            println("MEASURE $lines lines: median ${times[5]} ms, max ${times.last()} ms")
            scene!!.close()
            scene = null
        }
    }
}
