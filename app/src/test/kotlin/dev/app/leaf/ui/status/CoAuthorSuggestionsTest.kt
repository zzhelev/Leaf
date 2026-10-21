// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.commitmessage.CoAuthor
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.sort.SortMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val MAYA = CoAuthor("Maya Rodriguez", "maya@acme.dev")
private val MARCO = CoAuthor("Marco Bianchi", "marco@acme.dev")
private val PRIYA = CoAuthor("Priya Natarajan", "priya@acme.dev")
private val PEOPLE = listOf(MAYA, MARCO, PRIYA)

private const val TYPING = "Fix the parser\n\nCo-authored-by: ma"
private const val ELSEWHERE = "elsewhere"

/**
 * The commit field's co-author suggestions (fork-only), rendered offscreen: they show while the focused field's caret is
 * on a `Co-authored-by` line, above the field, and take ↓, ↑, Enter and Tab from it, which keeps the focus; and the
 * "Add co-author" item of the title row's menu.
 */
class CoAuthorSuggestionsTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun close() {
        hosts.forEach { it.close() }
    }

    // --- The menu

    @Test
    fun `the menu has Add Signed-off-by and Add co-author wherever the field can be edited, after Amend`() {
        fun labels(state: RepositoryState, isAmenableRebase: Boolean = false): List<String> =
            commitMessageMenuItems(
                commitMessageHeader(state, isAmenableRebase, isAmend = false, isAmendRebaseInteractive = false),
                amendLabel = "Amend last commit",
                coAuthorLabel = "Add co-author",
                onToggleAmend = {},
                onAddCoAuthor = {},
                signOffLabel = "Add Signed-off-by",
            ).map { item -> (item as? SortMenuItem.Option)?.label ?: "—" }

        assertEquals(listOf("Amend last commit", "Add Signed-off-by", "—", "Add co-author"), labels(RepositoryState.SAFE))
        assertEquals(listOf("Add Signed-off-by", "—", "Add co-author"), labels(RepositoryState.MERGING))
        assertEquals(listOf("Add Signed-off-by", "—", "Add co-author"), labels(RepositoryState.CHERRY_PICKING))
        assertEquals(listOf("Add Signed-off-by", "—", "Add co-author"), labels(RepositoryState.REVERTING))
        assertEquals(
            listOf("Amend last commit", "Add Signed-off-by", "—", "Add co-author"),
            labels(RepositoryState.REBASING_INTERACTIVE, isAmenableRebase = true),
        )
        assertEquals(emptyList<String>(), labels(RepositoryState.REBASING_INTERACTIVE), "A read-only field")
    }

    @Test
    fun `Add Signed-off-by is checked while the setting is on, and switches it`() {
        var toggles = 0

        fun signOff(isSignOff: Boolean) = commitMessageMenuItems(
            commitMessageHeader(RepositoryState.SAFE, false, isAmend = false, isAmendRebaseInteractive = false, isSignOff),
            amendLabel = "Amend last commit",
            coAuthorLabel = "Add co-author",
            onToggleAmend = {},
            onAddCoAuthor = {},
            signOffLabel = "Add Signed-off-by",
            onToggleSignOff = { toggles++ },
        ).filterIsInstance<SortMenuItem.Option>().single { it.label == "Add Signed-off-by" }

        assertTrue(signOff(true).isChecked)
        assertFalse(signOff(false).isChecked)
        signOff(false).onClick()
        assertEquals(1, toggles)
    }

    @Test
    fun `the title row says with Signed-off-by while the setting is on, after amending`(): Unit = runBlocking(Dispatchers.Swing) {
        Host("Fix", isSignOff = true).use { host ->
            assertTrue("· with Signed-off-by" in host.texts(), host.texts().toString())
        }

        Host("Fix", isSignOff = true, isAmend = true).use { host ->
            assertTrue("· amending last commit · with Signed-off-by" in host.texts(), host.texts().toString())
        }

        Host("Fix").use { host ->
            assertTrue(host.texts().none { "Signed-off-by" in it })
        }
    }

    @Test
    fun `choosing Add co-author asks the view model for the line`() {
        var added = 0
        val item = commitMessageMenuItems(
            commitMessageHeader(RepositoryState.SAFE, false, isAmend = false, isAmendRebaseInteractive = false),
            amendLabel = "Amend last commit",
            coAuthorLabel = "Add co-author",
            onToggleAmend = {},
            onAddCoAuthor = { added++ },
        ).last() as SortMenuItem.Option

        item.onClick()

        assertEquals(1, added)
        assertTrue(item.closesMenu)
    }

    // --- The suggestions

    @Test
    fun `suggests whom the line matches, only while the field has the focus`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(TYPING)

        assertTrue(host.rows().isEmpty(), "Not before the field has the focus")

        host.focus()

        assertEquals(listOf("Maya Rodriguez", "Marco Bianchi"), host.rows())
        assertEquals(CoAuthorAction.SuggestionsShown(true), host.actions.last(), "Registered for Esc")
        assertTrue(host.texts().any { it.startsWith("Someone else?") })
    }

    @Test
    fun `down and Enter pick the highlighted person, and the field keeps the focus`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val host = Host(TYPING)
            host.focus()

            assertEquals("Maya Rodriguez", host.highlighted(), "Something is typed: the first row")
            host.press(Key.DirectionDown)
            assertEquals("Marco Bianchi", host.highlighted())
            host.press(Key.DirectionDown)
            assertEquals("Marco Bianchi", host.highlighted(), "It stops at the last row")
            host.press(Key.DirectionUp)
            host.press(Key.DirectionUp)
            assertEquals("Maya Rodriguez", host.highlighted(), "And at the first")
            host.press(Key.DirectionDown)
            host.press(Key.Enter)

            assertEquals("Fix the parser\n\nCo-authored-by: Marco Bianchi <marco@acme.dev>", host.value.text)
            assertEquals(TextRange(host.value.text.length), host.value.selection)
            assertTrue(host.rows().isEmpty(), "The line names someone now")
            assertTrue(host.isFocused())
            assertEquals(CoAuthorAction.SuggestionsShown(false), host.actions.last())
        }

    @Test
    fun `Tab picks too`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host("Fix\n\nCo-authored-by: pri")
        host.focus()

        host.press(Key.Tab)

        assertEquals("Fix\n\nCo-authored-by: Priya Natarajan <priya@acme.dev>", host.value.text)
    }

    @Test
    fun `on an empty line nothing is highlighted, Tab picks the first, and down highlights it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Host("Fix\n\nCo-authored-by: ").use { host ->
                host.focus()

                assertEquals(listOf("Maya Rodriguez", "Marco Bianchi", "Priya Natarajan"), host.rows())
                assertNull(host.highlighted())
                host.press(Key.DirectionDown)
                assertEquals("Maya Rodriguez", host.highlighted())
            }

            Host("Fix\n\nCo-authored-by: ").use { host ->
                host.focus()
                host.press(Key.Tab)

                assertEquals("Fix\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>", host.value.text)
            }
        }

    @Test
    fun `Enter after a co-author starts the next line, and Enter on the empty line ends the list`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val host = Host("Fix\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>")
            host.focus()

            host.press(Key.Enter)

            assertEquals("Fix\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\nCo-authored-by: ", host.value.text)
            assertEquals(TextRange(host.value.text.length), host.value.selection)
            assertEquals(listOf("Marco Bianchi", "Priya Natarajan"), host.rows(), "Maya is named already")
            assertNull(host.highlighted())

            host.press(Key.Enter)

            assertEquals("Fix\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\n", host.value.text)
            assertTrue(host.rows().isEmpty())

            host.press(Key.Enter)
            assertEquals("Fix\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\n\n", host.value.text, "A line feed again")
        }

    @Test
    fun `a click on a row picks it`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(TYPING)
        host.focus()

        host.clickRow("Marco Bianchi")

        assertEquals("Fix the parser\n\nCo-authored-by: Marco Bianchi <marco@acme.dev>", host.value.text)
        assertTrue(host.isFocused())
    }

    @Test
    fun `the suggestions sit above the field`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(TYPING)
        host.focus()

        val rows = host.rowNodes()
        val field = host.field().boundsInWindow

        assertTrue(rows.all { it.boundsInWindow.bottom <= field.top }, "${rows.map { it.boundsInWindow }} over $field")
    }

    @Test
    fun `none for a line that names someone, for no match, off the line, or in a read-only field`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (message in listOf(
                "Fix\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>",
                "Fix\n\nCo-authored-by: nobody",
                "Fix the parser",
            )) {
                Host(message).use { host ->
                    host.focus()
                    assertTrue(host.rows().isEmpty(), message)
                }
            }

            // A field that can't be edited can't have the focus either
            Host(TYPING, readOnly = true).use { host ->
                assertNull(host.field().config.getOrNull(SemanticsActions.RequestFocus))
                assertTrue(host.rows().isEmpty())
            }
        }

    @Test
    fun `after Esc they hide until the text changes`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(TYPING)
        host.focus()

        // Esc goes to the tab, which closes the suggestions through the view model
        host.coAuthors = host.coAuthors.copy(dismissals = 1)
        host.settle()
        assertTrue(host.rows().isEmpty())

        host.type("$TYPING${"r"}")
        assertEquals(listOf("Marco Bianchi"), host.rows())
    }

    @Test
    fun `the line that Add co-author adds gets the focus, and the suggestions show`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val before = TextFieldValue("Fix the parser")
            val after = "Fix the parser\n\nCo-authored-by: \n"
            val host = Host(after, caret = after.length - 1)

            host.coAuthors = host.coAuthors.copy(insertion = CoAuthorInsertion(1, before, after))
            host.settle()

            assertTrue(host.isFocused())
            assertEquals(listOf("Maya Rodriguez", "Marco Bianchi", "Priya Natarajan"), host.rows())
            assertNull(host.highlighted(), "Nothing typed yet: Enter would end the list")
        }

    @Test
    fun `leaving the field tells the view model, which takes out an untouched line`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val host = Host(TYPING)
            host.focus()

            host.blur()

            assertTrue(CoAuthorAction.FieldLeft in host.actions)
            assertTrue(host.rows().isEmpty())
        }

    @Test
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun `keys with a modifier, and other keys, stay with the field`() {
        fun key(key: Key, meta: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) =
            suggestionKey(
                KeyEvent(key = key, type = KeyEventType.KeyDown, isMetaPressed = meta, isCtrlPressed = ctrl, isShiftPressed = shift),
                highlighted = 0,
                count = 3,
            )

        assertEquals(SuggestionKey.Pick(0), key(Key.Enter))
        assertNull(key(Key.Enter, meta = true), "Cmd+Enter commits")
        assertNull(key(Key.Enter, ctrl = true), "Ctrl+Enter commits")
        assertNull(key(Key.Enter, shift = true))
        assertNull(key(Key.A))
        assertNull(suggestionKey(KeyEvent(key = Key.Enter, type = KeyEventType.KeyUp), 0, 3))
        assertEquals(SuggestionKey.Highlight(1), key(Key.DirectionDown))

        // Nothing highlighted: Enter stays with the field, which ends the list, and Tab takes the first
        fun none(key: Key) = suggestionKey(KeyEvent(key = key, type = KeyEventType.KeyDown), highlighted = -1, count = 3)
        assertNull(none(Key.Enter))
        assertEquals(SuggestionKey.Pick(0), none(Key.Tab))
        assertEquals(SuggestionKey.Highlight(0), none(Key.DirectionDown))
        assertEquals(SuggestionKey.Highlight(0), none(Key.DirectionUp))
    }

    /** [CommitField] with [message], the caret at [caret], and [PEOPLE] to suggest. */
    private inner class Host(
        message: String,
        caret: Int = message.length,
        readOnly: Boolean = false,
        isSignOff: Boolean = false,
        isAmend: Boolean = false,
    ) : AutoCloseable {
        var value by mutableStateOf(TextFieldValue(message, TextRange(caret)))
        var coAuthors by mutableStateOf(CoAuthorState(candidates = PEOPLE))
        val actions = mutableListOf<CoAuthorAction>()

        private val scene = ImageComposeScene(width = 400, height = 520, density = Density(1f)) {
            AppTheme {
                Column {
                    // Somewhere else to put the focus
                    Box(Modifier.size(8.dp).focusable().semantics { contentDescription = ELSEWHERE })
                    // Room above the field, where the suggestions go
                    Spacer(Modifier.height(232.dp))

                    Box {
                        CommitField(
                            canCommit = true,
                            isAmend = isAmend,
                            canAmend = true,
                            doCommit = {},
                            commitMessage = value,
                            previousCommitMessage = "Previous",
                            repositoryState = if (readOnly) RepositoryState.REBASING_INTERACTIVE else RepositoryState.SAFE,
                            isAmenableRebaseInteractive = false,
                            showsContinue = false,
                            finishBlocker = null,
                            isAmendRebaseInteractive = false,
                            finishHint = null,
                            isLoading = false,
                            setCommitMessage = { value = it },
                            onResetRepoState = {},
                            onAbortRebase = {},
                            onContinueRebase = {},
                            onSkipRebase = {},
                            onAmendChecked = {},
                            onAmendRebaseInteractiveChecked = {},
                            modifier = Modifier.height(240.dp).width(400.dp),
                            coAuthors = coAuthors,
                            onCoAuthorAction = { actions += it },
                            isSignOff = isSignOff,
                        )
                    }
                }
            }
        }

        private var isClosed = false

        init {
            scene.render()
            hosts += this
        }

        private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            val all = mutableListOf<SemanticsNode>()

            fun collect(node: SemanticsNode) {
                all += node
                node.children.forEach(::collect)
            }

            collect(owner.unmergedRootSemanticsNode)
            all
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun field(): SemanticsNode = nodes().first { it.config.contains(SemanticsProperties.EditableText) }

        fun isFocused() = field().config.getOrNull(SemanticsProperties.Focused) == true

        /** The suggestion rows: buttons with a selected state, in the order shown. */
        fun rowNodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            val merged = mutableListOf<SemanticsNode>()

            fun collect(node: SemanticsNode) {
                merged += node
                node.children.forEach(::collect)
            }

            collect(owner.rootSemanticsNode)
            merged.filter { it.config.contains(SemanticsProperties.Selected) }
        }

        /** The names in the rows. */
        fun rows(): List<String> = rowNodes().map(::nameIn)

        fun highlighted(): String? = rowNodes().firstOrNull { it.config[SemanticsProperties.Selected] }?.let(::nameIn)

        /** A row's texts are the avatar's initial, the name and the email. */
        private fun nameIn(row: SemanticsNode): String = row.config[SemanticsProperties.Text][1].text

        suspend fun clickRow(name: String) {
            val row = rowNodes().first { node -> nameIn(node) == name }

            checkNotNull(row.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        suspend fun focus() {
            checkNotNull(field().config[SemanticsActions.RequestFocus].action).invoke()
            settle()
        }

        suspend fun blur() {
            val elsewhere = nodes().first { ELSEWHERE in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            checkNotNull(elsewhere.config[SemanticsActions.RequestFocus].action).invoke()
            settle()
        }

        suspend fun type(text: String) {
            checkNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        suspend fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
            settle()
        }

        suspend fun settle() {
            repeat(4) {
                scene.render(System.nanoTime())
                delay(15)
            }
        }

        override fun close() {
            if (!isClosed) {
                isClosed = true
                scene.close()
            }
        }
    }
}
