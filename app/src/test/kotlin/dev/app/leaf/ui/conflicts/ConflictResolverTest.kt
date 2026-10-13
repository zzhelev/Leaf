// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.conflicts.ColumnShares
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.ConflictResolverState
import dev.app.leaf.viewmodels.ResolverFile
import dev.app.leaf.viewmodels.ResolverFileEntry
import dev.app.leaf.viewmodels.ResolverHandOff
import dev.app.leaf.viewmodels.ResolverLeave
import dev.app.leaf.viewmodels.ResolverPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/** The resolver's view, rendered offscreen from a state, without its view model. */
class ConflictResolverTest {
    private val sides = ConflictSides(
        ConflictOperation.MERGE,
        ours = ConflictCommit("main", "1111111", "Main"),
        theirs = ConflictCommit("feature/retry", "2222222", "Retry"),
    )

    // ours added an import; both changed the read, differently; theirs added a function
    private val merge = TextMerge(
        base = text("package demo", "", "fun load(): Config {", "    val text = read(path)", "    return parse(text)", "}"),
        ours = text(
            "package demo", "", "import java.nio.file.Path", "", "fun load(): Config {",
            "    val text = Path.of(path).readText()", "    return parse(text)", "}",
        ),
        theirs = text(
            "package demo", "", "fun load(): Config {", "    val text = retry(3) { read(path) }",
            "    return parse(text)", "}", "", "fun <T> retry(times: Int, block: () -> T): T = block()",
        ),
        chunks = listOf(
            Chunk.Unchanged(lines(0, 2), lines(0, 2), lines(0, 2)),
            Chunk.Change(lines(2, 2), lines(2, 4), lines(2, 2), ChangedBy.OURS),
            Chunk.Unchanged(lines(2, 3), lines(4, 5), lines(2, 3)),
            Chunk.Conflict(lines(3, 4), lines(5, 6), lines(3, 4)),
            Chunk.Unchanged(lines(4, 6), lines(6, 8), lines(4, 6)),
            Chunk.Change(lines(6, 6), lines(8, 8), lines(6, 8), ChangedBy.THEIRS),
        ),
    )

    private val entry = StatusEntry(
        "src/ConfigLoader.kt",
        StatusType.CONFLICTING,
        conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
    )

    @Test
    fun `the columns name each side, and the toolbar counts the conflicts`(): Unit = runBlocking(Dispatchers.Swing) {
        val texts = render(state()).texts

        assertTrue("Ours: main" in texts, "$texts")
        assertTrue("Theirs: feature/retry" in texts, "$texts")
        assertTrue("Conflict 1 of 1" in texts, "$texts")
        assertTrue("0 resolved · 2 merged cleanly" in texts, "$texts")
        assertTrue("Conflict 1 · open: ▶ takes ours, ◀ takes theirs" in texts, "$texts")
    }

    @Test
    fun `the arrows take a side, and a change can be undone`(): Unit = runBlocking(Dispatchers.Swing) {
        val taken = mutableListOf<Pair<Int, ConflictSide>>()
        val applied = mutableListOf<Pair<Int, Boolean>>()
        val columns = PickActions(
            onTake = { chunk, side -> taken += chunk to side },
            onResolve = { _, _ -> },
            onSetApplied = { chunk, value -> applied += chunk to value },
        )

        render(state(), actions(columns), clickDescription = "Take ours: main")
        render(state(), actions(columns), clickDescription = "Take theirs: feature/retry")
        render(state(), actions(columns), clickDescription = "Undo this change: the result gets the base's lines")

        assertEquals(listOf(3 to ConflictSide.OURS, 3 to ConflictSide.THEIRS), taken)
        // The first change on the left is ours'
        assertEquals(1 to false, applied.first())
    }

    @Test
    fun `once ours is taken, theirs' arrow adds after it and the conflict can be opened again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val resolved = state(merge.copy(chunks = merge.chunks.mapIndexed { index, chunk ->
                if (index == 3) (chunk as Chunk.Conflict).copy(resolution = ConflictResolution.Ours) else chunk
            }))
            val rendered = render(resolved)

            assertTrue("Add theirs after ours: feature/retry" in rendered.descriptions, "${rendered.descriptions}")
            assertTrue("Open this conflict again" in rendered.descriptions, "${rendered.descriptions}")
            assertTrue("Take ours: main" !in rendered.descriptions)
            assertTrue("1 resolved · 2 merged cleanly" in rendered.texts)
        }

    @Test
    fun `Mark resolved does nothing while a conflict is open`(): Unit = runBlocking(Dispatchers.Swing) {
        var marked = 0
        val actions = actions(onMarkResolved = { marked++ })

        assertTrue("Mark resolved" !in render(state(), actions).clickable)

        val resolved = state(merge.copy(chunks = merge.chunks.mapIndexed { index, chunk ->
            if (index == 3) (chunk as Chunk.Conflict).copy(resolution = ConflictResolution.Theirs) else chunk
        }))
        render(resolved, actions, clickText = "Mark resolved")
        assertEquals(1, marked)
    }

    @Test
    fun `a file without markers is offered to be marked resolved as it is, or resolved again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var asItIs = 0
            var again = 0
            val state = state(workingFile = WorkingFileState.NO_MARKERS)

            render(state, actions(onMarkResolvedAsItIs = { asItIs++ }), clickText = "Mark resolved as it is")
            render(state, actions(onResolveAgain = { again++ }), clickText = "Resolve again")

            assertEquals(1 to 1, asItIs to again)
            assertTrue("No conflict markers left" in render(state).texts)
        }

    @Test
    fun `a file whose markers don't match the merge says so above the columns`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val texts = render(state(workingFile = WorkingFileState.DIFFERENT, conflictsInFile = 2)).texts

            assertTrue(texts.any { it.startsWith("The file's conflict markers don't match the merge: 2 conflicts") }, "$texts")
        }

    @Test
    fun `a binary conflict offers each side, named`(): Unit = runBlocking(Dispatchers.Swing) {
        val used = mutableListOf<ConflictSide>()
        val state = state().let { state ->
            val file = (state.file as ResolverFile.Loaded).file.copy(content = ConflictContent.Binary)
            state.copy(file = ResolverFile.Loaded(file, merge = null, saved = null))
        }

        render(state, actions(onUseSide = { _, side, _ -> used += side }), clickText = "Use ours (main)")
        render(state, actions(onUseSide = { _, side, _ -> used += side }), clickText = "Use theirs (feature/retry)")

        assertEquals(listOf(ConflictSide.OURS, ConflictSide.THEIRS), used)
    }

    @Test
    fun `a file that theirs deleted offers ours' version, or deleting it`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = state().let { state ->
            val file = (state.file as ResolverFile.Loaded).file.copy(
                theirs = null,
                conflict = FileConflict(ConflictVersion.FILE, null),
                content = ConflictContent.Deleted,
            )
            state.copy(file = ResolverFile.Loaded(file, merge = null, saved = null))
        }
        val texts = render(state).texts

        assertTrue("Use ours (main)" in texts, "$texts")
        assertTrue("Delete the file, as theirs (feature/retry) did" in texts, "$texts")
        assertTrue(texts.any { it.startsWith("theirs (feature/retry) deleted this file, and ours (main) changed it") }, "$texts")
    }

    @Test
    fun `a change's undo is in the gutter next to the side that made it`(): Unit = runBlocking(Dispatchers.Swing) {
        val undo = "Undo this change: the result gets the base's lines"
        // Ours' import comes first, theirs' function last
        val (ours, theirs) = render(state()).bounds.getValue(undo).sortedBy { it.top }

        assertTrue(ours.left < 700f && theirs.left > 700f, "ours' at ${ours.left}, theirs' at ${theirs.left}")
    }

    @Test
    fun `leaving a file with unsaved picks asks to save, discard or cancel`(): Unit = runBlocking(Dispatchers.Swing) {
        val answers = mutableListOf<Boolean?>()
        val state = state().copy(prompt = ResolverPrompt.UnsavedChanges(ResolverLeave.Close))

        for (button in listOf("Save", "Discard", "Cancel")) {
            render(state, actions(onAnswerUnsaved = { answers += it }), clickText = button, inPrompt = true)
        }

        assertEquals(listOf(true, false, null), answers)
    }

    @Test
    fun `the headers' tooltip says what ours and theirs are in the operation`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var text = ""
            val scene = ImageComposeScene(width = 600, height = 100, density = Density(1f)) {
                AppTheme {
                    text = sides.copy(operation = ConflictOperation.REBASE).explanation()
                    Text(text)
                }
            }

            try {
                scene.render()
            } finally {
                scene.close()
            }

            assertEquals(
                "In a rebase, ours is what the rebase builds on, main (onto), with the commits replayed so far, and " +
                    "theirs is the commit being replayed, 2222222 \"Retry\" from feature/retry.",
                text,
            )
        }

    @Test
    fun `the stacked layout has the sides on top, named, and the result below`(): Unit = runBlocking(Dispatchers.Swing) {
        val rendered = render(state().copy(layout = ConflictResolverLayout.STACKED))
        val texts = rendered.texts

        assertTrue("Ours: main" in texts, "$texts")
        assertTrue("Theirs: feature/retry" in texts, "$texts")
        assertTrue("Conflict 1 · open: take ours or theirs above, or right-click for more" in texts, "$texts")
        assertEquals(2, texts.count { it == "Take ↓" }, "$texts")
        // Ours' import and theirs' function
        assertEquals(2, texts.count { it == "Applied" }, "$texts")
        assertTrue("Conflict 1 of 1" in texts, "$texts")
        assertTrue(texts.any { it.startsWith("Take ↓ · Add ↓ after the other side") }, "$texts")

        val ours = rendered.textBounds.getValue("Ours: main").single()
        val theirs = rendered.textBounds.getValue("Theirs: feature/retry").single()
        val result = rendered.textBounds.getValue("Result").single()

        assertTrue(ours.top == theirs.top && ours.left < theirs.left, "ours at $ours, theirs at $theirs")
        // The result below the top pair, across the full width
        assertTrue(result.top > ours.bottom + 100 && result.left <= ours.left + 1, "result at $result, ours at $ours")
    }

    @Test
    fun `the stacked chips take a side, add the other after it, and take it out again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val calls = mutableListOf<String>()
            val picks = PickActions(
                onTake = { chunk, side -> calls += "take $chunk $side" },
                onResolve = { _, _ -> },
                onSetApplied = { chunk, applied -> calls += "applied $chunk $applied" },
                onDrop = { chunk, side -> calls += "drop $chunk $side" },
            )
            val open = state().copy(layout = ConflictResolverLayout.STACKED)
            val oursTaken = state(merge.copy(chunks = merge.chunks.mapIndexed { index, chunk ->
                if (index == 3) (chunk as Chunk.Conflict).copy(resolution = ConflictResolution.Ours) else chunk
            })).copy(layout = ConflictResolverLayout.STACKED)

            // Ours' chips come first
            render(open, actions(picks), clickText = "Take ↓")
            render(oursTaken, actions(picks), clickText = "Taken")
            render(oursTaken, actions(picks), clickText = "Add ↓")
            render(open, actions(picks), clickText = "Applied")

            assertEquals(listOf("take 3 OURS", "drop 3 OURS", "take 3 THEIRS", "applied 1 false"), calls)
            assertTrue("Take ↓" !in render(oursTaken).texts)
        }

    @Test
    fun `the toolbar switches the layout`(): Unit = runBlocking(Dispatchers.Swing) {
        val layouts = mutableListOf<ConflictResolverLayout>()
        val actions = actions(onSetLayout = { layouts += it })

        render(state(), actions, clickDescription = "Stacked: ours and theirs on top, the result below")
        render(state().copy(layout = ConflictResolverLayout.STACKED), actions, clickDescription = "Columns: ours, the result and theirs side by side")
        // The layout shown isn't picked again
        render(state(), actions, clickDescription = "Columns: ours, the result and theirs side by side")

        assertEquals(listOf(ConflictResolverLayout.STACKED, ConflictResolverLayout.COLUMNS), layouts)
    }

    @Test
    fun `switching the layout keeps the place in the file, and the top pair follows the result`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Ours replaced line 60 with five lines, theirs with one
            val base = (0 until 100).map { "line $it" }
            val ours = base.take(60) + (1..5).map { "ours $it" } + base.drop(61)
            val theirs = base.take(60) + "theirs" + base.drop(61)
            val long = TextMerge(
                text(*base.toTypedArray()),
                text(*ours.toTypedArray()),
                text(*theirs.toTypedArray()),
                listOf(
                    Chunk.Unchanged(lines(0, 60), lines(0, 60), lines(0, 60)),
                    Chunk.Conflict(lines(60, 61), lines(60, 65), lines(60, 61)),
                    Chunk.Unchanged(lines(61, 100), lines(65, 104), lines(61, 100)),
                ),
            )
            val lists = ResolverLists()
            var layout by mutableStateOf(ConflictResolverLayout.COLUMNS)

            scene(lists) { state(long).copy(layout = layout) }.closing { scene ->
                // Opens at the conflict, with three rows above it
                scene.frames()
                assertEquals(57, lists.columns.firstVisibleItemIndex)

                layout = ConflictResolverLayout.STACKED
                scene.frames()
                assertEquals(57, lists.result.firstVisibleItemIndex)
                assertEquals(57, lists.sides.firstVisibleItemIndex)

                // The result's row 70 is the ninth line after the conflict, which is five rows tall on top. The offset
                // within the line goes with it
                lists.result.requestScrollToItem(70, 5)
                scene.frames()
                assertEquals(74 to 5, lists.sides.firstVisibleItemIndex to lists.sides.firstVisibleItemScrollOffset)

                layout = ConflictResolverLayout.COLUMNS
                scene.frames()
                assertEquals(74 to 5, lists.columns.firstVisibleItemIndex to lists.columns.firstVisibleItemScrollOffset)
            }
        }

    @Test
    fun `moving to a conflict shows it on top too, past lines that only the top pair has`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Ours deleted lines 40 to 59; then both changed line 61
            val base = (0 until 100).map { "line $it" }
            val ours = base.take(40) + base[60] + "ours" + base.drop(62)
            val theirs = base.take(61) + "theirs" + base.drop(62)
            val long = TextMerge(
                text(*base.toTypedArray()),
                text(*ours.toTypedArray()),
                text(*theirs.toTypedArray()),
                listOf(
                    Chunk.Unchanged(lines(0, 40), lines(0, 40), lines(0, 40)),
                    Chunk.Change(lines(40, 60), lines(40, 40), lines(40, 60), ChangedBy.OURS),
                    Chunk.Unchanged(lines(60, 61), lines(40, 41), lines(60, 61)),
                    Chunk.Conflict(lines(61, 62), lines(41, 42), lines(61, 62)),
                    Chunk.Unchanged(lines(62, 100), lines(42, 80), lines(62, 100)),
                ),
            )
            val lists = ResolverLists()
            val scrollToChunk = MutableSharedFlow<Int>(extraBufferCapacity = 1)

            scene(lists, scrollToChunk) { state(long).copy(layout = ConflictResolverLayout.STACKED) }.closing { scene ->
                scene.frames()
                lists.result.requestScrollToItem(0)
                scene.frames()
                assertEquals(0, lists.sides.firstVisibleItemIndex)

                scrollToChunk.tryEmit(3)
                scene.frames(count = 60)

                // The result's conflict is its row 42, and the top pair's 61: both with three rows above
                assertEquals(39, lists.result.firstVisibleItemIndex)
                assertEquals(58, lists.sides.firstVisibleItemIndex)
            }
        }

    @Test
    fun `with Wrap lines off, a long line stays on one row and every column scrolls sideways together`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val long = "    val message = " + "\"word\" + ".repeat(60) + "\"end\""
            // Enough lines after the conflict for the list to scroll up and down
            val after = (1..80).map { "line $it" }.toTypedArray()
            val merge = TextMerge(
                text("short", long, "base", *after),
                text("short", long, "ours", *after),
                text("short", long, "theirs", *after),
                listOf(
                    Chunk.Unchanged(lines(0, 2), lines(0, 2), lines(0, 2)),
                    Chunk.Conflict(lines(2, 3), lines(2, 3), lines(2, 3)),
                    Chunk.Unchanged(lines(3, 83), lines(3, 83), lines(3, 83)),
                ),
            )
            val lists = ResolverLists()
            var wrap by mutableStateOf(false)

            scene(lists) { state(merge).copy(wrapLines = wrap) }.closing { scene ->
                scene.frames()

                val short = scene.placesOf("short")
                val unwrapped = scene.placesOf(long)

                // In ours, the result and theirs, one line high, and wider than a column
                assertEquals(3, unwrapped.size, "$unwrapped")
                assertTrue(unwrapped.all { it.height == short.first().height && it.width > 600f }, "$unwrapped, $short")
                assertTrue(lists.sideways.max > 0f)
                // Its row is one line high too: the next row starts a line further down
                val lineHeight = unwrapped.first().top - short.first().top
                assertEquals(unwrapped.first().top + lineHeight, scene.placesOf("ours").first().top)

                // A sideways swipe of two steps over the result moves every column's lines by 2 × 48 dp
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(2f, 0f))
                scene.frames()

                assertEquals(96f, lists.sideways.offset)
                assertEquals(unwrapped.map { it.left - 96f }, scene.placesOf(long).map { it.left })
                assertEquals(short.map { it.left - 96f }, scene.placesOf("short").map { it.left })

                // Up and down stays the list's
                fun listPlace() = lists.columns.firstVisibleItemIndex to lists.columns.firstVisibleItemScrollOffset
                val listTop = listPlace()
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(0f, 1f))
                scene.frames()

                assertEquals(96f, lists.sideways.offset)
                assertTrue(listPlace() != listTop, "${listPlace()}")

                lists.columns.requestScrollToItem(0)
                scene.frames()

                // A swipe that is mostly sideways goes sideways only: the list doesn't drift up or down
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(-1f, 0.5f))
                scene.frames()

                assertEquals(48f, lists.sideways.offset)
                assertEquals(listTop, listPlace())

                // Shift and the wheel go sideways, and the list stays where it is
                val shift = PointerKeyboardModifiers(isShiftPressed = true)
                scene.sendPointerEvent(
                    PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(0f, 1f), keyboardModifiers = shift,
                )
                scene.frames()

                assertEquals(96f, lists.sideways.offset)
                assertEquals(listTop, listPlace())

                // Back past the start stops at it
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(-10f, 0f))
                scene.frames()

                assertEquals(0f, lists.sideways.offset)

                wrap = true
                scene.frames()

                val wrapped = scene.placesOf(long)
                assertTrue(wrapped.size == 3 && wrapped.all { it.height > 2 * short.first().height }, "$wrapped")
            }
        }

    @Test
    fun `the toolbar's Wrap lines calls its action`(): Unit = runBlocking(Dispatchers.Swing) {
        var toggled = 0

        render(state(), actions(onToggleWrapLines = { toggled++ }), clickText = "Wrap lines")

        assertEquals(1, toggled)
    }

    @Test
    fun `dragging a gutter moves the edge between its columns, and a double-click shares the width out again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val changed = mutableListOf<ColumnShares>()
            val lists = ResolverLists()

            scene(lists, actions = actions(onColumnSharesChanged = { changed += it })) { state() }.closing { scene ->
                scene.frames()

                // The undo button of ours' import is in the left gutter; the headers are 30 dp under the toolbar's 40
                val undo = "Undo this change: the result gets the base's lines"
                val gutter = scene.nodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(undo) == true
                }
                val header = Offset(gutter.boundsInRoot.center.x, 55f)

                scene.drag(from = header, to = header + Offset(100f, 0f))

                val dragged = changed.single()

                // A third, and 100 of the 1,095 dp that the gutters and the file list leave
                assertEquals(1f / 3 + 100f / 1095, dragged.ours, 0.002f)
                assertEquals(1f / 3, dragged.theirs, 0.001f)

                // A double-click where the drag left the gutter shares the width out evenly again
                scene.doubleClick(header + Offset(100f, 0f))
                assertEquals(ColumnShares(), changed.last())

                // From a row's gutter too, the other way, beside the 26 dp buttons in the 40 dp gutter, which keep
                // their clicks
                val beside = Offset(header.x - 17f, 80f)

                scene.drag(from = beside, to = beside - Offset(100f, 0f))
                assertEquals(1f / 3 - 100f / 1095, changed.last().ours, 0.002f)

                // Theirs' gutter, with theirs' undo button, moves the edge between the result and theirs
                val theirsGutter = scene.nodes()
                    .filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(undo) == true }
                    .maxBy { it.boundsInRoot.left }
                val theirsHeader = Offset(theirsGutter.boundsInRoot.center.x, 55f)

                scene.drag(from = theirsHeader, to = theirsHeader - Offset(100f, 0f))

                assertEquals(1f / 3 - 100f / 1095, changed.last().ours, 0.002f)
                assertEquals(1f / 3 + 100f / 1095, changed.last().theirs, 0.002f)
            }
        }

    @Test
    fun `dragging the file list's edge resizes it`(): Unit = runBlocking(Dispatchers.Swing) {
        val widths = mutableListOf<Float>()

        scene(ResolverLists(), actions = actions(onFileListWidthChanged = { widths += it })) { state() }.closing { scene ->
            scene.frames()
            // The list is 220 dp wide, and its edge 5
            scene.drag(from = Offset(222f, 300f), to = Offset(282f, 300f))

            assertTrue(widths.single() in 255f..281f, "$widths")
        }
    }

    @Test
    fun `dragging the stacked layout's handle gives the top pair more of the height`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val splits = mutableListOf<Float>()

            scene(ResolverLists(), actions = actions(onStackedSplitChanged = { splits += it })) {
                state().copy(layout = ConflictResolverLayout.STACKED)
            }.closing { scene ->
                scene.frames()
                // Under the 40 dp toolbar, 45% of the 526 dp that the toolbar, the legend and the handle leave, then
                // the middle of the 8 dp handle
                val handle = Offset(800f, 40f + 0.45f * 526f + 4f)

                scene.drag(from = handle, to = handle + Offset(0f, 60f))

                assertTrue(splits.single() in 0.5f..0.57f, "$splits")
            }
        }

    @Test
    fun `in Focus, the file list hides, and the file name in the toolbar switches files`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val selected = mutableListOf<String>()
            val other = StatusEntry("docs/guide.md", StatusType.CONFLICTING)
            val done = StatusEntry("README.md", StatusType.CONFLICTING)
            val focused = state().let { state ->
                state.copy(
                    focused = true,
                    files = state.files + ResolverFileEntry(other, resolvedHere = false) +
                        ResolverFileEntry(done, resolvedHere = true),
                )
            }

            assertTrue("CONFLICTED FILES" in render(state()).texts)

            scene(ResolverLists(), actions = actions(onSelectFile = { selected += it })) { focused }.closing { scene ->
                scene.frames()

                assertTrue(scene.placesOf("CONFLICTED FILES").isEmpty())

                scene.clickText(entry.filePath)
                scene.frames()
                // The menu lists every file; one resolved here can't be opened again
                assertTrue(scene.placesOf("docs/guide.md").isNotEmpty())
                val resolved = scene.nodes().first { node ->
                    node.config.getOrNull(SemanticsActions.OnClick) != null &&
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "README.md" }
                }
                assertTrue(resolved.config.contains(SemanticsProperties.Disabled))
                scene.clickText("docs/guide.md")
                scene.frames()

                assertEquals(listOf("docs/guide.md"), selected)
            }
        }

    @Test
    fun `the toolbar's Focus button turns Focus on and off`(): Unit = runBlocking(Dispatchers.Swing) {
        var toggled = 0
        val actions = actions(onToggleFocus = { toggled++ })

        scene(ResolverLists(), actions = actions) { state() }.closing { scene ->
            scene.frames()
            scene.clickDescription { it.startsWith("Focus: hide the tabs") }
        }
        scene(ResolverLists(), actions = actions) { state().copy(focused = true) }.closing { scene ->
            scene.frames()
            scene.clickDescription { it.startsWith("Leave Focus") && it.endsWith("or Esc)") }
        }

        assertEquals(2, toggled)
    }

    @Test
    fun `a resolved file stays, read-only but scrolling, with a strip that opens the next file`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Enough lines after the conflict for the list to scroll
            val after = (1..80).map { "line $it" }.toTypedArray()
            val long = TextMerge(
                text("a", "base", *after),
                text("a", "ours", *after),
                text("a", "theirs", *after),
                listOf(
                    Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                    Chunk.Conflict(lines(1, 2), lines(1, 2), lines(1, 2)),
                    Chunk.Unchanged(lines(2, 82), lines(2, 82), lines(2, 82)),
                ),
            )
            val guide = StatusEntry("docs/guide.md", StatusType.CONFLICTING)
            val handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "docs/guide.md")
            val resolved = state(long).copy(
                files = listOf(ResolverFileEntry(guide, resolvedHere = false), ResolverFileEntry(entry, resolvedHere = true)),
                handOff = handOff,
            )
            var next = 0
            val taken = mutableListOf<Int>()
            val picks = PickActions(onTake = { chunk, _ -> taken += chunk }, { _, _ -> }, { _, _ -> })
            val lists = ResolverLists()
            val shortcut = if (currentOs == OS.MAC) "⌘⏎" else "Ctrl+Enter"

            scene(lists, actions = actions(picks = picks, onNextFile = { next++ })) { resolved }.closing { scene ->
                // Dimmed: the first frame comes before the animation, and the file then stands out much less from the
                // background
                scene.frames(count = 1)
                val full = scene.contrastInFile()
                scene.frames(count = 30)
                val dimmed = scene.contrastInFile()
                assertTrue(dimmed < 0.6 * full, "$dimmed of $full")

                assertTrue(scene.placesOf("src/ConfigLoader.kt is resolved and staged.").isNotEmpty())
                assertTrue(scene.placesOf("Resolved").isNotEmpty())
                // Nothing left to save or resolve
                assertTrue(scene.placesOf("Save").isEmpty())
                assertTrue(scene.placesOf("Mark resolved").isEmpty())
                assertTrue(scene.placesOf("External merge tool…").isEmpty())

                // The arrows take no clicks, and the list still scrolls
                val arrow = scene.nodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Take ours") }
                }
                scene.press(arrow.boundsInRoot.center)
                scene.release(arrow.boundsInRoot.center)
                scene.frames()
                assertEquals(emptyList<Int>(), taken)

                val top = lists.columns.firstVisibleItemIndex
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 300f), scrollDelta = Offset(0f, 3f))
                scene.frames()
                assertTrue(lists.columns.firstVisibleItemIndex > top, "${lists.columns.firstVisibleItemIndex}")

                scene.clickText("Next file: docs/guide.md ($shortcut)")
                assertEquals(1, next)

            }

            // Resolved outside the resolver
            assertTrue(
                "src/ConfigLoader.kt has no conflicts any more." in
                    render(resolved.copy(handOff = handOff.copy(resolvedHere = false))).texts
            )

            // Once another file is shown, it's about nothing on screen
            val other = render(resolved.copy(handOff = handOff.copy(from = "README.md"))).texts
            assertTrue(other.none { it.endsWith("is resolved and staged.") }, "$other")
            assertTrue("Mark resolved" in other, "$other")
        }

    @Test
    fun `in Focus, the toolbar counts the files, those resolved here first`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = listOf(
            ResolverFileEntry(entry, resolvedHere = false),
            ResolverFileEntry(StatusEntry("docs/guide.md", StatusType.CONFLICTING), resolvedHere = false),
            ResolverFileEntry(StatusEntry("README.md", StatusType.CONFLICTING), resolvedHere = true),
        )

        assertTrue("2 of 3 files" in render(state().copy(files = files, focused = true)).texts)
        // Once the file shown is resolved, it's the last of those resolved here
        val resolved = state().copy(
            files = listOf(files[1], files[2], ResolverFileEntry(entry, resolvedHere = true)),
            focused = true,
            handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "docs/guide.md"),
        )
        assertTrue("2 of 3 files" in render(resolved).texts)
        assertTrue("1 of 2 files" in render(resolved.copy(files = listOf(files[1], ResolverFileEntry(entry, true)))).texts)
        // The file list shows them outside Focus, and one file needs no count
        assertTrue(render(state().copy(files = files)).texts.none { it.endsWith(" files") })
        assertTrue(render(state().copy(focused = true)).texts.none { it.endsWith(" files") })
    }

    @Test
    fun `while the next file loads, the last one stays, takes no clicks, and fades into the next`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val taken = mutableListOf<Pair<Int, ConflictSide>>()
            val picks = PickActions(onTake = { chunk, side -> taken += chunk to side }, { _, _ -> }, { _, _ -> })
            var current by mutableStateOf(state())

            scene(ResolverLists(), actions = actions(picks = picks)) { current }.closing { scene ->
                scene.frames()

                fun clickTakeOurs() {
                    val arrow = scene.nodes().first { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            .any { it.startsWith("Take ours") }
                    }
                    val at = arrow.boundsInRoot.center
                    scene.press(at)
                    scene.release(at)
                    scene.frames(count = 2)
                }

                clickTakeOurs()
                assertEquals(1, taken.size)

                assertTrue(scene.coloursInFile() > 1)

                current = current.copy(file = ResolverFile.Loading("docs/guide.md"))
                scene.frames(count = 2)

                // No "Loading…" flash: the last file stays, under a cover that takes the pointer
                assertTrue(scene.placesOf("Loading…").isEmpty())
                assertTrue(scene.placesOf("Ours: main").isNotEmpty())
                clickTakeOurs()
                assertEquals(1, taken.size)

                // It fades out
                scene.frames()
                assertEquals(1, scene.coloursInFile())

                // A load that takes long enough to notice says so
                delay(600)
                scene.frames()
                assertTrue(scene.placesOf("Loading…").isNotEmpty())

                val guide = (state().file as ResolverFile.Loaded).let { loaded ->
                    loaded.copy(file = loaded.file.copy(path = "docs/guide.md"))
                }
                current = current.copy(file = guide)
                scene.frames(count = 30)

                assertTrue(scene.placesOf("Loading…").isEmpty())
                assertTrue(scene.coloursInFile() > 1)
                clickTakeOurs()
                assertEquals(2, taken.size)
            }
        }

    @Test
    fun `Focus's shortcut is Shift and F with Cmd on macOS, and with Ctrl elsewhere`() {
        val mac = currentOs == OS.MAC

        fun press(cmd: Boolean = false, ctrl: Boolean = false, shift: Boolean = true, alt: Boolean = false, key: Key = Key.F) =
            isFocusShortcut(key, cmd = cmd, ctrl = ctrl, shift = shift, alt = alt)

        assertEquals(mac, press(cmd = true))
        assertEquals(!mac, press(ctrl = true))
        assertEquals(false, press(cmd = mac, ctrl = !mac, shift = false))
        assertEquals(false, press(cmd = mac, ctrl = !mac, alt = true))
        assertEquals(false, press(cmd = mac, ctrl = !mac, key = Key.G))
        assertEquals(false, press(cmd = true, ctrl = true))
    }

    private fun state(
        merge: TextMerge = this.merge,
        workingFile: WorkingFileState = WorkingFileState.MATCHES,
        conflictsInFile: Int = 1,
    ) = ConflictResolverState(
        files = listOf(ResolverFileEntry(entry, resolvedHere = false)),
        file = ResolverFile.Loaded(
            file = ConflictedFile(
                path = entry.filePath,
                base = ConflictStage("1".repeat(40), 0b1000000110100100),
                ours = ConflictStage("2".repeat(40), 0b1000000110100100),
                theirs = ConflictStage("3".repeat(40), 0b1000000110100100),
                conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
                sides = sides,
                content = ConflictContent.Text(this.merge, WorkingFileCheck(workingFile, conflictsInFile, "id")),
            ),
            merge = merge,
            saved = this.merge,
        ),
    )

    private fun actions(
        picks: PickActions = PickActions({ _, _ -> }, { _, _ -> }, { _, _ -> }),
        onMarkResolved: () -> Unit = {},
        onMarkResolvedAsItIs: () -> Unit = {},
        onResolveAgain: () -> Unit = {},
        onUseSide: (StatusEntry, ConflictSide, ConflictSides?) -> Unit = { _, _, _ -> },
        onAnswerUnsaved: (Boolean?) -> Unit = {},
        onSetLayout: (ConflictResolverLayout) -> Unit = {},
        onStackedSplitChanged: (Float) -> Unit = {},
        onColumnSharesChanged: (ColumnShares) -> Unit = {},
        onFileListWidthChanged: (Float) -> Unit = {},
        onToggleWrapLines: () -> Unit = {},
        onToggleFocus: () -> Unit = {},
        onSelectFile: (String) -> Unit = {},
        onNextFile: () -> Unit = {},
    ) = ResolverActions(
        onClose = {},
        onSelectFile = onSelectFile,
        onPreviousConflict = {},
        onNextConflict = {},
        onToggleBase = {},
        onSetLayout = onSetLayout,
        onStackedSplitChanged = onStackedSplitChanged,
        onColumnSharesChanged = onColumnSharesChanged,
        onFileListWidthChanged = onFileListWidthChanged,
        onToggleWrapLines = onToggleWrapLines,
        onToggleFocus = onToggleFocus,
        onSave = {},
        onMarkResolved = onMarkResolved,
        onOpenInExternalTool = {},
        onMarkResolvedAsItIs = onMarkResolvedAsItIs,
        onResolveAgain = onResolveAgain,
        onUseSide = onUseSide,
        onAnswerUnsaved = onAnswerUnsaved,
        onReload = {},
        onDismissPrompt = {},
        onNextFile = onNextFile,
        picks = picks,
    )

    /**
     * What the view shows: its [texts], its icons' [descriptions], the texts that can be clicked, and where each
     * description is.
     */
    private class Rendered(
        val texts: List<String>,
        val descriptions: List<String>,
        val clickable: List<String>,
        val bounds: Map<String, List<Rect>>,
        val textBounds: Map<String, List<Rect>>,
    )

    /** The view of [state] in a 1,400 by 600 window, with [lists] that the test reads, for [frames]. */
    private fun scene(
        lists: ResolverLists,
        scrollToChunk: MutableSharedFlow<Int> = MutableSharedFlow(),
        actions: ResolverActions = actions(),
        state: () -> ConflictResolverState,
    ) = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
        AppTheme {
            ConflictResolverContent(state(), lists, actions, scrollToChunk = scrollToChunk)
        }
    }

    /** The scene's semantics nodes, every one. */
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    /** Clicks the first clickable node that has [text], or a child with it. */
    private fun ImageComposeScene.clickText(text: String) = click { node ->
        (listOf(node) + node.children).any { child ->
            child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }
    }

    /** Clicks the first clickable node whose description, or a child's, matches. */
    private fun ImageComposeScene.clickDescription(matches: (String) -> Boolean) = click { node ->
        (listOf(node) + node.children).any { child ->
            child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any(matches)
        }
    }

    private fun ImageComposeScene.click(matches: (SemanticsNode) -> Boolean) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && matches(it) }

        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
    }

    /** Where the nodes with [text] are laid out, unclipped: their position and size. */
    private fun ImageComposeScene.placesOf(text: String): List<Rect> = nodes()
        .filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        .map { Rect(it.positionInRoot, it.size.toSize()) }

    /**
     * How many colours the file's part of the view has, right of the file list and under the toolbar, every 4 px: one
     * while it's faded out.
     */
    private fun ImageComposeScene.coloursInFile(): Int {
        val bitmap = Bitmap.makeFromImage(render(frameTime))
        val colours = mutableSetOf<Int>()

        for (x in 400 until 1380 step 4) {
            for (y in 100 until 560 step 4) {
                colours += bitmap.getColor(x, y)
            }
        }

        return colours.size
    }

    /**
     * How far the file's part of the view stands out from its background: the pixels' average distance from the most
     * common colour, every 4 px, over the same part as [coloursInFile].
     */
    private fun ImageComposeScene.contrastInFile(): Double {
        val bitmap = Bitmap.makeFromImage(render(frameTime))
        val pixels = (400 until 1380 step 4).flatMap { x -> (100 until 560 step 4).map { y -> bitmap.getColor(x, y) } }
        val background = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key

        fun distance(colour: Int) = (0..2).sumOf { channel ->
            abs((colour shr (8 * channel) and 0xFF) - (background shr (8 * channel) and 0xFF))
        }

        return pixels.sumOf { distance(it) }.toDouble() / pixels.size
    }

    private var eventTime = 0L

    /** Presses the primary button at [from], moves to [to] in steps, and lets go. */
    private fun ImageComposeScene.drag(from: Offset, to: Offset, steps: Int = 10) {
        press(from)
        for (step in 1..steps) {
            eventTime += 16
            val at = from + (to - from) * (step / steps.toFloat())
            sendPointerEvent(PointerEventType.Move, at, timeMillis = eventTime, buttons = PointerButtons(isPrimaryPressed = true))
            frames(count = 1)
        }
        release(to)
        frames()
    }

    /** Two clicks at [at], 100 ms apart. */
    private fun ImageComposeScene.doubleClick(at: Offset) {
        repeat(2) {
            eventTime += 100
            press(at)
            release(at)
            frames(count = 2)
        }
        frames(count = 30)
    }

    private fun ImageComposeScene.press(at: Offset) {
        eventTime += 10
        val pressed = PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Press, at, timeMillis = eventTime, buttons = pressed, button = PointerButton.Primary)
    }

    private fun ImageComposeScene.release(at: Offset) {
        eventTime += 10
        val none = PointerButtons()
        sendPointerEvent(PointerEventType.Release, at, timeMillis = eventTime, buttons = none, button = PointerButton.Primary)
    }

    private inline fun ImageComposeScene.closing(block: (ImageComposeScene) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private var frameTime = 0L

    /** Renders [count] frames, 16 ms apart, so that the effects scroll and the animations end. */
    private fun ImageComposeScene.frames(count: Int = 10) {
        repeat(count) {
            frameTime += 16_000_000L
            render(frameTime)
        }
    }

    /**
     * Renders the view in a 1,400 by 600 window, and clicks what has the text [clickText] or the description
     * [clickDescription]. The prompt comes after the toolbar, so with [inPrompt] it's the last match, else the first.
     * With `LEAF_RENDER_DIR` set, it also saves the image there.
     */
    private fun render(
        state: ConflictResolverState,
        actions: ResolverActions = actions(),
        clickText: String? = null,
        clickDescription: String? = null,
        inPrompt: Boolean = false,
    ): Rendered {
        val scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
            AppTheme {
                ConflictResolverContent(state, ResolverLists(), actions)
            }
        }

        try {
            val image = scene.render()

            System.getenv("LEAF_RENDER_DIR")?.let { dir ->
                val name = (clickText ?: clickDescription ?: "view").replace(Regex("[^A-Za-z0-9]+"), "-")
                File(dir, "resolver-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }

            val nodes = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            fun clickable(matches: (SemanticsNode) -> Boolean) = nodes.filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && matches(node)
            }

            val target = when {
                clickText != null -> clickable { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == clickText } ||
                        node.children.any { child ->
                            child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == clickText }
                        }
                }.let { if (inPrompt) it.last() else it.first() }

                clickDescription != null -> clickable { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(clickDescription) ||
                        node.children.any {
                            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(clickDescription)
                        }
                }.first()

                else -> null
            }

            target?.let { checkNotNull(it.config[SemanticsActions.OnClick].action).invoke() }

            fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

            return Rendered(
                texts = nodes.flatMap { it.texts() },
                descriptions = nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() },
                clickable = clickable { true }.flatMap { node -> node.texts() + node.children.flatMap { it.texts() } },
                bounds = nodes
                    .flatMap { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().map { it to node.boundsInRoot }
                    }
                    .groupBy({ it.first }, { it.second }),
                textBounds = nodes
                    .flatMap { node -> node.texts().map { it to node.boundsInRoot } }
                    .groupBy({ it.first }, { it.second }),
            )
        } finally {
            scene.close()
        }
    }
}

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)
