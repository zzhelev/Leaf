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
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.CommitTemplate
import dev.app.leaf.domain.commitmessage.ConventionalCommitsMode
import dev.app.leaf.domain.commitmessage.TicketKeyStyle
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
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

private const val KEY = "PROJ-482"
private const val ELSEWHERE = "elsewhere"
private val TEMPLATE = CommitTemplate("/home/ann/.gitmessage", "\n# Why is this change needed?\n")
private val STAGED = StatusEntry("a.txt", StatusType.MODIFIED, EntryType.STAGED)

private val LONG_BODY = "Fix the parser\n\nThe branch field only listed branches, so a tag had to be typed by hand, and a " +
    "typo only showed once git failed to find it."

private fun state(
    message: String = "",
    inputs: MessageStartInputs = MessageStartInputs(key = KEY),
    repositoryState: RepositoryState = RepositoryState.SAFE,
    isAmend: Boolean = false,
    hints: CommitMessageHints = CommitMessageHints(),
) = StatusState(
    isLoading = false,
    staged = listOf(STAGED),
    commitMessage = TextFieldValue(message, TextRange(message.length)),
    previousCommitMessage = "Previous",
    repositoryState = repositoryState,
    isAmend = isAmend,
    messageHints = hints,
    messageStartInputs = inputs,
)

/**
 * What an empty commit field offers to start from, the branch's ticket key and git's commit template, and "Wrap body at
 * 72 columns" in its menu (fork-only): the state's rules, and the field rendered offscreen.
 */
class MessageStartAndWrapTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun close() {
        hosts.forEach { it.close() }
    }

    // --- The state

    @Test
    fun `the start is offered for a plain commit only`() {
        assertEquals("PROJ-482: ", state().messageStart?.text)
        assertNull(state(isAmend = true).messageStart)
        assertNull(state(repositoryState = RepositoryState.MERGING).messageStart)
        assertNull(state(repositoryState = RepositoryState.CHERRY_PICKING).messageStart)
        assertNull(state(repositoryState = RepositoryState.REVERTING).messageStart)
        assertNull(state(repositoryState = RepositoryState.REBASING_INTERACTIVE).messageStart)
        assertNull(state().copy(isSquashMergeInProgress = true).messageStart)
        assertNull(state(inputs = MessageStartInputs()).messageStart)
    }

    @Test
    fun `the key follows the history's style, and goes before the template`() {
        assertEquals("[PROJ-482] ", state(inputs = MessageStartInputs(KEY, TicketKeyStyle.BRACKETS)).messageStart?.text)
        assertEquals(
            "PROJ-482: \n# Why is this change needed?\n",
            state(inputs = MessageStartInputs(KEY, template = TEMPLATE)).messageStart?.text,
        )
        assertEquals("\n# Why is this change needed?\n", state(inputs = MessageStartInputs(template = TEMPLATE)).messageStart?.text)
    }

    @Test
    fun `where Conventional Commits are suggested, the key is the first scope, not in front of the subject`() {
        val always = CommitMessageHints(conventionalCommits = ConventionalCommitsMode.ALWAYS)

        assertNull(state(hints = always).messageStart)
        assertEquals(KEY, state(hints = always).scopeKey)
        assertEquals(TEMPLATE.text, state(inputs = MessageStartInputs(KEY, template = TEMPLATE), hints = always).messageStart?.text)
        assertNull(state().scopeKey)
    }

    @Test
    fun `the start unedited stops Commit and says why`() {
        val keyOnly = state("PROJ-482: ")
        assertFalse(keyOnly.canCommit)
        assertEquals(FinishBlocker.UneditedStart(KEY, isTemplate = false), keyOnly.finishBlocker())
        assertEquals(keyOnly.finishBlocker(), keyOnly.finishHint())

        val template = state(TEMPLATE.text, MessageStartInputs(template = TEMPLATE))
        // The template has only comments: that's an empty message first
        assertEquals(FinishBlocker.EmptyMessage(commentsOnly = true), template.finishBlocker())

        val withText = CommitTemplate("/t", "Summary\n\n# Why?\n")
        val untouched = state(withText.text, MessageStartInputs(template = withText))
        assertEquals(FinishBlocker.UneditedStart(null, isTemplate = true), untouched.finishBlocker())

        assertTrue(state("PROJ-482: Fix the parser").canCommit)
        assertTrue(state("Summary\n\nBecause.\n", MessageStartInputs(template = withText)).canCommit)
    }

    // --- The field

    @Test
    fun `the focused empty field shows the key and what Tab does, and Tab writes it`(): Unit = runBlocking(Dispatchers.Swing) {
        Host(state()).use { host ->
            assertFalse("Tab adds PROJ-482 from the branch name" in host.texts(), "Not before the field has the focus")
            assertTrue("Write your commit message here" in host.texts())

            host.focus()
            assertTrue("PROJ-482: " in host.texts(), host.texts().toString())
            assertTrue("Tab adds PROJ-482 from the branch name" in host.texts(), host.texts().toString())

            host.press(Key.Tab)
            assertEquals(TextFieldValue("PROJ-482: ", TextRange(10)), host.value)
            assertFalse("Tab adds PROJ-482 from the branch name" in host.texts())
        }
    }

    @Test
    fun `the key and the template, with the template's file in the tooltip`(): Unit = runBlocking(Dispatchers.Swing) {
        Host(state(inputs = MessageStartInputs(KEY, template = TEMPLATE))).use { host ->
            host.focus()
            assertTrue("Tab adds PROJ-482 and the commit template" in host.texts(), host.texts().toString())

            host.press(Key.Tab)
            assertEquals("PROJ-482: \n# Why is this change needed?\n", host.value.text)
            assertEquals(TextRange(10), host.value.selection)
        }

        Host(state(inputs = MessageStartInputs(template = TEMPLATE))).use { host ->
            host.focus()
            assertTrue("Tab starts from the commit template" in host.texts(), host.texts().toString())
        }
    }

    @Test
    fun `nothing is offered once the field has text, and Tab with a modifier writes nothing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Host(state("Fix")).use { host ->
                host.focus()
                assertFalse(host.texts().any { it.startsWith("Tab ") })
            }

            Host(state()).use { host ->
                host.focus()
                host.press(Key.Tab, shift = true)
                assertEquals("", host.value.text)
            }
        }

    @Test
    fun `the branch's key is the first scope where Conventional Commits are suggested`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val always = CommitMessageHints(conventionalCommits = ConventionalCommitsMode.ALWAYS)

            Host(state(hints = always)).use { host ->
                host.focus()
                assertFalse(host.texts().any { it.startsWith("Tab ") }, "No key in front of the subject")

                host.type("feat(")
                assertEquals(KEY, host.firstRow(), host.texts().toString())
                assertTrue("From the branch name" in host.texts())

                host.press(Key.Tab)
                assertEquals("feat(PROJ-482): ", host.value.text)
            }
        }

    // --- Wrap body

    @Test
    fun `Wrap body is greyed out, saying why, while every line fits`() {
        fun wrapItem(message: String) = commitMessageMenuItems(
            commitMessageHeader(
                RepositoryState.SAFE, false, isAmend = false, isAmendRebaseInteractive = false,
                canWrapBody = dev.app.leaf.domain.commitmessage.canWrapBody(message, state().messageCleanup),
            ),
            amendLabel = "Amend last commit",
            coAuthorLabel = "Add co-author",
            onToggleAmend = {},
            onAddCoAuthor = {},
            wrapBodyLabel = "Wrap body at 72 columns",
            wrapBodyHint = "Every line fits",
        ).filterIsInstance<SortMenuItem.Option>().single { it.label == "Wrap body at 72 columns" }

        assertFalse(wrapItem("Fix the parser\n\nShort.").isEnabled)
        assertEquals("Every line fits", wrapItem("Fix the parser\n\nShort.").hint)
        assertTrue(wrapItem(LONG_BODY).isEnabled)
        assertNull(wrapItem(LONG_BODY).hint)
    }

    @Test
    fun `Wrap body wraps the body from the menu, keeping the caret before the same word`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Host(state(LONG_BODY)).use { host ->
                host.value = TextFieldValue(LONG_BODY, TextRange(LONG_BODY.indexOf("git failed")))

                host.clickDescription("More actions")
                host.click("Wrap body at 72 columns")

                val wrapped = "Fix the parser\n\nThe branch field only listed branches, so a tag had to be typed by " +
                    "hand,\nand a typo only showed once git failed to find it."
                assertEquals(wrapped, host.value.text)
                assertEquals(TextRange(wrapped.indexOf("git failed")), host.value.selection)
            }
        }

    @Test
    fun `undo puts the lines back as they were`(): Unit = runBlocking(Dispatchers.Swing) {
        Host(state(LONG_BODY)).use { host ->
            host.focus()
            host.clickDescription("More actions")
            host.click("Wrap body at 72 columns")
            assertTrue('\n' in host.value.text.substringAfter("\n\n"))

            host.focus()
            host.pressUndo()
            assertEquals(LONG_BODY, host.value.text)
        }
    }

    @Test
    fun `the menu's Wrap does nothing while every line fits`(): Unit = runBlocking(Dispatchers.Swing) {
        Host(state("Fix the parser\n\nShort.")).use { host ->
            host.clickDescription("More actions")
            host.click("Wrap body at 72 columns")

            assertEquals("Fix the parser\n\nShort.", host.value.text)
            assertTrue("Every line fits" in host.texts())
        }
    }

    /** A commit field whose message is [value], rendered from [base] with it as the status pane renders it. */
    private inner class Host(private val base: StatusState) : AutoCloseable {
        var value by mutableStateOf(base.commitMessage)

        private val scene = ImageComposeScene(width = 400, height = 520, density = Density(1f)) {
            AppTheme {
                val state = base.copy(commitMessage = value)

                Column {
                    Box(Modifier.size(8.dp).focusable().semantics { contentDescription = ELSEWHERE })
                    Spacer(Modifier.height(232.dp))

                    CommitField(
                        canCommit = state.canCommit,
                        isAmend = state.isAmend,
                        canAmend = state.canAmend,
                        doCommit = {},
                        commitMessage = state.commitMessage,
                        previousCommitMessage = state.previousCommitMessage,
                        repositoryState = state.repositoryState,
                        isAmenableRebaseInteractive = false,
                        showsContinue = false,
                        finishBlocker = state.finishBlocker(),
                        isAmendRebaseInteractive = false,
                        finishHint = state.finishHint(),
                        isLoading = false,
                        setCommitMessage = { value = it },
                        onResetRepoState = {},
                        onAbortRebase = {},
                        onContinueRebase = {},
                        onSkipRebase = {},
                        onAmendChecked = {},
                        onAmendRebaseInteractiveChecked = {},
                        modifier = Modifier.height(260.dp).width(400.dp),
                        messageCleanup = state.messageCleanup,
                        messageHints = state.messageHints,
                        conventionalHistory = state.conventionalHistory,
                        commitlintRules = state.commitlintRules,
                        messageStart = state.messageStart,
                        scopeKey = state.scopeKey,
                    )
                }
            }
        }

        private var isClosed = false

        init {
            scene.render()
            hosts += this
        }

        private fun nodes(merged: Boolean = false): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            val all = mutableListOf<SemanticsNode>()

            fun collect(node: SemanticsNode) {
                all += node
                node.children.forEach(::collect)
            }

            collect(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
            all
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        private fun field(): SemanticsNode = nodes().first { it.config.contains(SemanticsProperties.EditableText) }

        /** The first suggestion row's name. */
        fun firstRow(): String? = nodes(merged = true)
            .firstOrNull { it.config.contains(SemanticsProperties.Selected) }
            ?.config?.get(SemanticsProperties.Text)?.firstOrNull()?.text

        suspend fun focus() {
            checkNotNull(field().config[SemanticsActions.RequestFocus].action).invoke()
            settle()
        }

        suspend fun type(text: String) {
            checkNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }

        suspend fun clickDescription(description: String) {
            val node = nodes(merged = true).first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        /** Clicks the element whose text is [text], when it takes clicks: a greyed-out menu item may not. */
        suspend fun click(text: String) {
            val node = nodes(merged = true).firstOrNull { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            node?.config?.get(SemanticsActions.OnClick)?.action?.invoke()
            settle()
        }

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        suspend fun press(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isShiftPressed = shift))
            settle()
        }

        /** Cmd+Z on macOS, Ctrl+Z elsewhere. */
        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        suspend fun pressUndo() {
            val meta = System.getProperty("os.name").startsWith("Mac")

            scene.sendKeyEvent(KeyEvent(Key.Z, KeyEventType.KeyDown, isMetaPressed = meta, isCtrlPressed = !meta))
            scene.sendKeyEvent(KeyEvent(Key.Z, KeyEventType.KeyUp, isMetaPressed = meta, isCtrlPressed = !meta))
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
