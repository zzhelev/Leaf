// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.LogColumnsSettings
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.models.SearchHit
import dev.app.leaf.repositoryopen.LogSearch
import dev.app.leaf.repositoryopen.SearchHits
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.getParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private val KEY_HINT = if (currentOs == OS.MAC) "⌘+F" else "Ctrl+F"

private val THREE_OF_FIVE = LogSearch.SearchResults(
    SearchHits((1..5).map { SearchHit("hash$it", it) }),
    index = 3,
)

/**
 * The log's filter bar (fork-only, option A of the log header's options): the branch filter, the search field and the
 * columns button above the column headers, and the field's search: typing, the counter and its buttons, the keys, an
 * ended search, Ctrl+F (⌘F) and leaving the field. Set `LEAF_RENDER_DIR` for PNGs, and pass `--rerun`.
 */
class LogFilterBarTest {
    @Test
    fun `the bar has the branch filter, the field and the columns button, and the header only the columns`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Bar(width = 900).use { bar ->
                val filter = bar.bounds("Branches")
                val field = bar.fieldBounds()
                val hint = bar.bounds(KEY_HINT)
                val columns = bar.bounds("Columns")

                assertTrue("Search commits" in bar.texts())
                assertTrue(filter.right <= field.left, "The field comes after the filter")
                // The field is 320 wide, 8 after the filter, and the hint ends 8 before its end
                assertEquals(filter.right + 8f + 320f - 8f, hint.right, 2f, "The hint ends at ${hint.right}")
                // The icon is centred in its 48 px button
                assertEquals(900f - 15f, columns.right, 2f, "The columns button is at the bar's end")
                assertTrue(columns.bottom <= bar.bounds("Graph").top, "The bar is above the column headers")
                // The header row has its titles and no control
                assertEquals(listOf("Graph", "Message", "Date"), bar.texts().filter { it in setOf("Graph", "Message", "Date") })
            }
        }

    @Test
    fun `in a narrow log, the field shrinks and the columns button stays at the end`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Bar(width = 360).use { bar ->
                val field = bar.fieldBounds()
                val hint = bar.bounds(KEY_HINT)
                val columns = bar.bounds("Columns")

                assertTrue(bar.bounds("Branches").right <= field.left)
                assertTrue(hint.right <= columns.left, "The field's hint ends at ${hint.right}, the button starts at ${columns.left}")
                assertTrue(columns.right <= 360f)
            }
        }

    @Test
    fun `typing searches, and the counter and its buttons show while the field has text`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Bar().use { bar ->
                bar.focusField()
                bar.type("parser")

                assertEquals(listOf("parser"), bar.typed)

                bar.search = THREE_OF_FIVE
                bar.settle()

                assertTrue("3/5" in bar.texts(), "${bar.texts()}")
                assertFalse(bar.texts().any { it.endsWith("+F") })

                bar.clickDescription("Previous result (Shift+Enter)")
                bar.clickDescription("Next result (Enter)")

                assertEquals(listOf("previous", "next"), bar.steps)

                bar.clickDescription("End the search (Esc)")

                // Then the focus leaves the emptied field, which ends it again: the view model's end is idempotent
                assertTrue(bar.closes >= 1)
                assertTrue(bar.listHasFocus)
            }
        }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun `Enter and Shift+Enter in the field step through the results`(): Unit = runBlocking(Dispatchers.Swing) {
        Bar().use { bar ->
            bar.focusField()
            bar.type("parser")
            bar.search = THREE_OF_FIVE
            bar.settle()

            bar.press(Key.Enter)
            bar.press(Key.Enter, shift = true)

            assertEquals(listOf("next", "previous"), bar.steps)
        }
    }

    @Test
    fun `an ended search empties the field, and the focus goes to the log's list`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Bar().use { bar ->
                bar.focusField()
                bar.type("parser")
                bar.search = THREE_OF_FIVE
                bar.settle()

                // Esc, through the tab
                bar.search = LogSearch.NotSearching
                bar.settle()

                assertTrue("Search commits" in bar.texts(), "The placeholder is back: ${bar.texts()}")
                assertTrue(bar.listHasFocus, "The log's list has the focus")
            }
        }

    @Test
    fun `Ctrl+F puts the focus in the field, which says so for Esc, and leaving it empty ends the search`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Bar().use { bar ->
                bar.requestFocus()

                assertTrue(bar.fieldHasFocus())
                assertEquals(1, bar.focuses)

                bar.focusList()

                assertEquals(1, bar.closes, "An empty field ends the search when left")
            }

            Bar().use { bar ->
                bar.requestFocus()
                bar.type("parser")
                bar.focusList()

                assertEquals(0, bar.closes, "A field with a search keeps it")
            }
        }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun `Ctrl+F, or Cmd+F on a Mac, is the log search's alone, and is the hint the field shows`() {
        val isMac = currentOs == OS.MAC
        val press = KeyEvent(Key.F, KeyEventType.KeyDown, isCtrlPressed = !isMac, isMetaPressed = isMac)

        assertTrue(press.matchesBinding(KeybindingOption.FIND))
        assertEquals(
            listOf(KeybindingOption.FIND),
            KeybindingOption.entries.filter { press.matchesBinding(it) },
        )
        assertEquals(KEY_HINT, getParts(KeybindingOption.FIND.keyBinding!!).joinToString("+"))
    }

    @Test
    fun `renders the bar, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (dark in listOf(true, false)) {
            for ((name, search) in listOf("idle" to LogSearch.NotSearching, "searching" to THREE_OF_FIVE)) {
                Bar(width = 900, dark = dark).use { bar ->
                    if (search != LogSearch.NotSearching) {
                        bar.focusField()
                        bar.type("parser")
                        bar.search = search
                        bar.settle()
                    }

                    File(folder, "log-bar-$name-${if (dark) "dark" else "light"}.png").writeBytes(bar.png())
                }
            }
        }
    }

    private class Bar(width: Int = 700, dark: Boolean = true) : AutoCloseable {
        var search by mutableStateOf<LogSearch>(LogSearch.NotSearching)
        val typed = mutableListOf<String>()
        val steps = mutableListOf<String>()
        var closes = 0
        var focuses = 0
        var listHasFocus = false
        private val list = FocusRequester()
        private val focusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private var frameTime = 0L

        private val scene = ImageComposeScene(width, 160, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(if (dark) Theme.Dark else Theme.Light) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    LogFilterBar(
                        branchFilterControl = { Text("Branches", Modifier.width(110.dp)) },
                        searchField = { modifier ->
                            LogSearchField(
                                search = search,
                                initialText = "",
                                onTextChange = { typed += it },
                                onPrevious = { steps += "previous" },
                                onNext = { steps += "next" },
                                onClose = {
                                    closes++
                                    search = LogSearch.NotSearching
                                },
                                onFocused = { focuses++ },
                                modifier = modifier,
                                focusAfterClose = list,
                                focusRequests = focusRequests,
                            )
                        },
                        columnsMenuItems = emptyList(),
                    )
                    GraphHeader(
                        graphWidth = 80.dp,
                        columns = LogColumnsSettings().fitting(width - 300f, LOG_COLUMN_SPACING).shown,
                        columnsMenuItems = emptyList(),
                        onGraphDrag = {},
                        onGraphDragStopped = {},
                        onColumnResize = { _, _ -> },
                        onColumnResizeFinished = {},
                    )
                    Box(
                        Modifier
                            .height(40.dp)
                            .width(100.dp)
                            .focusRequester(list)
                            .onFocusChanged { listHasFocus = it.isFocused }
                            .focusable()
                    )
                }
            }
        }

        init {
            settle()
        }

        fun settle() = repeat(6) {
            scene.render(frameTime)
            frameTime += 16_000_000L
        }

        fun png(): ByteArray = scene.render(frameTime).encodeToData(EncodedImageFormat.PNG)!!.bytes

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun bounds(textOrDescription: String): Rect = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == textOrDescription } ||
                textOrDescription in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.boundsInRoot

        private fun field() = nodes().first { it.config.contains(SemanticsProperties.EditableText) }

        fun fieldBounds(): Rect = field().boundsInRoot

        fun fieldHasFocus() = field().config.getOrNull(SemanticsProperties.Focused) == true

        fun focusField() {
            checkNotNull(field().config[SemanticsActions.RequestFocus].action).invoke()
            settle()
        }

        fun requestFocus() {
            focusRequests.tryEmit(Unit)
            settle()
        }

        fun focusList() {
            list.requestFocus()
            settle()
        }

        fun type(text: String) {
            checkNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }

        fun clickDescription(description: String) {
            val node = nodes(merged = true).first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        fun press(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
            settle()
        }

        override fun close() = scene.close()

        private fun nodes(merged: Boolean = false): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }

            return nodes
        }
    }
}
