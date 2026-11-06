// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.domain.models.SECTION_DIVIDER_HEIGHT
import dev.app.leaf.domain.models.STATUS_COMMIT_TITLE_HEIGHT
import dev.app.leaf.domain.models.STATUS_MIN_COMMIT_FIELD_HEIGHT
import dev.app.leaf.domain.models.StatusSectionFolds
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.ui.components.SectionFold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val PANE = 700f

/** A folded list's header in these tests, and a folded commit message's title row and buttons. */
private const val HEADER = 34f
private const val FOLDED_COMMIT = 90f

/**
 * The status pane's sections folded and open (fork-only), laid out by [StatusSections] in a 700 px pane, with boxes in
 * place of the lists and the commit field: what each one's height is, where the handles are, that a fold is saved and
 * comes back, and where the focus goes when the section that had it folds.
 */
class StatusSectionsTest {
    @Test
    fun `with every section open, the sections are as tall as the saved sizes give, as before`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes(stagedShare = 0.3f)).use { sections ->
                val heights = StatusSectionSizes(stagedShare = 0.3f).fitTo(PANE)

                assertEquals(heights.staged, sections.bounds("staged").height, 1f)
                assertEquals(heights.unstaged, sections.bounds("unstaged").height, 1f)
                assertEquals(heights.commitSection, sections.bounds("commit").height, 1f)
                assertEquals(PANE, sections.bounds("commit").bottom, 1f)
                // The handles between them
                assertEquals(SECTION_DIVIDER_HEIGHT, sections.bounds("unstaged").top - sections.bounds("staged").bottom, 1f)
                assertEquals(SECTION_DIVIDER_HEIGHT, sections.bounds("commit").top - sections.bounds("unstaged").bottom, 1f)
            }
        }

    @Test
    fun `a folded list is its header, and the open list takes its room, with no handle between them`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes(folds = StatusSectionFolds(staged = true))).use { sections ->
                val commit = 192f + STATUS_COMMIT_TITLE_HEIGHT

                assertEquals(Rect(0f, 0f, 330f, HEADER), sections.bounds("staged"))
                assertEquals(HEADER, sections.bounds("unstaged").top, 1f)
                assertEquals(PANE - HEADER - SECTION_DIVIDER_HEIGHT - commit, sections.bounds("unstaged").height, 1f)
                assertEquals(commit, sections.bounds("commit").height, 1f)
                assertEquals(PANE, sections.bounds("commit").bottom, 1f)
            }
        }

    @Test
    fun `the lists keep their order when Staged is below, folded or not`(): Unit = runBlocking(Dispatchers.Swing) {
        Sections(StatusSectionSizes(folds = StatusSectionFolds(unstaged = true)), stagedOnTop = false).use { sections ->
            assertEquals(Rect(0f, 0f, 330f, HEADER), sections.bounds("unstaged"))
            assertEquals(HEADER, sections.bounds("staged").top, 1f)
            assertEquals(sections.bounds("commit").top - SECTION_DIVIDER_HEIGHT, sections.bounds("staged").bottom, 1f)
        }
    }

    @Test
    fun `a folded commit message keeps its buttons at the bottom, and the lists split the rest as saved`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes(stagedShare = 0.25f, folds = StatusSectionFolds(commitMessage = true))).use { sections ->
                val lists = PANE - FOLDED_COMMIT - SECTION_DIVIDER_HEIGHT

                assertEquals(Rect(0f, PANE - FOLDED_COMMIT, 330f, PANE), sections.bounds("commit"))
                assertEquals(lists * 0.25f, sections.bounds("staged").height, 1f)
                assertEquals(lists * 0.75f, sections.bounds("unstaged").height, 1f)
                assertEquals(sections.bounds("commit").top, sections.bounds("unstaged").bottom, 1f, "No handle above it")
            }
        }

    @Test
    fun `with both lists folded, the open commit message takes what their headers leave`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes(folds = StatusSectionFolds(staged = true, unstaged = true))).use { sections ->
                assertEquals(Rect(0f, 0f, 330f, HEADER), sections.bounds("staged"))
                assertEquals(Rect(0f, HEADER, 330f, 2 * HEADER), sections.bounds("unstaged"))
                assertEquals(Rect(0f, 2 * HEADER, 330f, PANE), sections.bounds("commit"))
            }
        }

    @Test
    fun `with everything folded, the headers stack at the top`(): Unit = runBlocking(Dispatchers.Swing) {
        val folds = StatusSectionFolds(staged = true, unstaged = true, commitMessage = true)

        Sections(StatusSectionSizes(folds = folds)).use { sections ->
            assertEquals(Rect(0f, 0f, 330f, HEADER), sections.bounds("staged"))
            assertEquals(Rect(0f, HEADER, 330f, 2 * HEADER), sections.bounds("unstaged"))
            assertEquals(Rect(0f, 2 * HEADER, 330f, 2 * HEADER + FOLDED_COMMIT), sections.bounds("commit"))
        }
    }

    @Test
    fun `while the message is in the centre pane, the commit section is as small as it goes`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes(commitFieldHeight = 300f), isMessageExpanded = true).use { sections ->
                assertEquals(STATUS_MIN_COMMIT_FIELD_HEIGHT + STATUS_COMMIT_TITLE_HEIGHT, sections.bounds("commit").height, 1f)
            }
        }

    @Test
    fun `a click folds a section and saves it, a second opens it again at its size`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val sizes = StatusSectionSizes(stagedShare = 0.3f, commitFieldHeight = 250f)

            Sections(sizes).use { sections ->
                val before = listOf("staged", "unstaged", "commit").map(sections::bounds)

                sections.click("staged")

                assertEquals(StatusSectionFolds(staged = true), sections.saved.last().folds)
                assertEquals(HEADER, sections.bounds("staged").height, 1f)

                sections.click("commit")

                assertEquals(StatusSectionFolds(staged = true, commitMessage = true), sections.saved.last().folds)
                assertEquals(FOLDED_COMMIT, sections.bounds("commit").height, 1f)

                sections.click("staged")
                sections.click("commit")

                assertEquals(sizes, sections.saved.last())
                assertEquals(before, listOf("staged", "unstaged", "commit").map(sections::bounds))
            }
        }

    @Test
    fun `with Staged folded, the handle above the commit field moves it, and the open list gives way`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes(folds = StatusSectionFolds(staged = true))).use { sections ->
                val handle = sections.bounds("commit").top - SECTION_DIVIDER_HEIGHT / 2

                sections.drag(Offset(150f, handle), dy = -100f)

                assertEquals(292f, sections.saved.last().commitFieldHeight, 1f)
                assertEquals(292f + STATUS_COMMIT_TITLE_HEIGHT, sections.bounds("commit").height, 1f)
                assertEquals(sections.bounds("commit").top - SECTION_DIVIDER_HEIGHT, sections.bounds("unstaged").bottom, 1f)
            }
        }

    @Test
    fun `folding the section that has the focus gives it to the tab, and leaves it where it was otherwise`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Sections(StatusSectionSizes()).use { sections ->
                sections.focus("in-staged")
                assertTrue(sections.isFocused("in-staged"))

                sections.click("staged")

                assertTrue(sections.isFocused("tab"), "The tab has the focus after Staged folded")
            }

            Sections(StatusSectionSizes()).use { sections ->
                sections.focus("log")

                sections.click("staged")

                assertTrue(sections.isFocused("log"), "The log keeps the focus")
                assertFalse(sections.isFocused("tab"))
            }
        }

    /**
     * [StatusSections] with [initial] sizes, which come back as the view model saves them, inside a tab with a focusable
     * "tab" and "log". Each section is a box tagged with its name that folds with a click; Staged's holds a focusable.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private class Sections(
        initial: StatusSectionSizes,
        stagedOnTop: Boolean = true,
        isMessageExpanded: Boolean = false,
    ) : AutoCloseable {
        private var sizes by mutableStateOf(initial)
        val saved = mutableListOf<StatusSectionSizes>()
        private val focused = mutableSetOf<String>()
        private val requesters = mutableMapOf<String, FocusRequester>()
        private var frameTime = 0L

        private val scene = ImageComposeScene(
            width = 330,
            height = PANE.toInt() + 40,
            density = Density(1f),
            coroutineContext = Dispatchers.Swing,
        ) {
            val tab = remember { FocusRequester() }

            CompositionLocalProvider(LocalTabFocusRequester provides tab) {
                Column {
                    Focusable("tab", Modifier.focusRequester(tab).height(20.dp).fillMaxWidth())
                    Focusable("log", Modifier.height(20.dp).fillMaxWidth())

                    StatusSections(
                        sizes = sizes,
                        stagedOnTop = stagedOnTop,
                        isMessageExpanded = isMessageExpanded,
                        onSizesChanged = {
                            saved += it
                            sizes = it
                        },
                        modifier = Modifier.height(PANE.dp),
                        staged = { modifier, fold ->
                            Section("staged", modifier, fold, HEADER) {
                                Focusable("in-staged", Modifier.height(10.dp).fillMaxWidth())
                            }
                        },
                        unstaged = { modifier, fold -> Section("unstaged", modifier, fold, HEADER) },
                        commitMessage = { modifier, fold -> Section("commit", modifier, fold, FOLDED_COMMIT) },
                    )
                }
            }
        }

        @androidx.compose.runtime.Composable
        private fun Focusable(name: String, modifier: Modifier) {
            val requester = remember { FocusRequester().also { requesters[name] = it } }

            Box(
                modifier
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) focused += name else focused -= name }
                    .focusable()
                    .testTag(name)
            )
        }

        @androidx.compose.runtime.Composable
        private fun Section(
            name: String,
            modifier: Modifier,
            fold: SectionFold,
            foldedHeight: Float,
            content: @androidx.compose.runtime.Composable () -> Unit = {},
        ) {
            Box(
                modifier
                    .then(if (fold.isFolded) Modifier.height(foldedHeight.dp) else Modifier)
                    .fillMaxWidth()
                    .testTag(name)
                    .clickable { fold.onToggle() }
            ) {
                if (!fold.isFolded) content()
            }
        }

        init {
            settle()
        }

        private fun settle() = repeat(6) {
            scene.render(frameTime)
            frameTime += 16_000_000L
        }

        /** [name]'s bounds in the pane, which starts under the tab's and the log's 40 px. */
        fun bounds(name: String): Rect = node(name).boundsInRoot.translate(0f, -40f)

        fun click(name: String) {
            checkNotNull(node(name).config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        fun focus(name: String) {
            requesters.getValue(name).requestFocus()
            settle()
        }

        fun isFocused(name: String) = name in focused

        @OptIn(InternalComposeUiApi::class)
        fun drag(from: Offset, dy: Float) {
            val start = from.copy(y = from.y + 40f)

            scene.sendPointerEvent(PointerEventType.Move, start)
            scene.sendPointerEvent(
                PointerEventType.Press,
                start,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            for (step in 1..10) {
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    start.copy(y = start.y + dy * step / 10),
                    buttons = PointerButtons(isPrimaryPressed = true),
                )
            }
            scene.sendPointerEvent(PointerEventType.Release, start.copy(y = start.y + dy), button = PointerButton.Primary)
            settle()
        }

        override fun close() = scene.close()

        private fun node(tag: String): SemanticsNode = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }
    }
}
