// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.EnumRule
import dev.app.leaf.domain.commitmessage.ConventionalCommitsMode
import dev.app.leaf.domain.commitmessage.ConventionalHistory
import dev.app.leaf.domain.commitmessage.conventionalHistory
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val PERSON = Identity("Ann Lee", "ann@acme.dev")

private fun historyOf(vararg subjects: String): ConventionalHistory =
    conventionalHistory(subjects.map { Commit("0".repeat(40), it, PERSON, PERSON, 0L, listOf("p")) })

/** A repository whose subjects use Conventional Commits: fix most, then feat, with scopes. */
private val CONVENTIONAL = historyOf(
    "fix(log): a", "fix(log): b", "fix(clone): c", "fix: z", "feat(clone): d", "feat(clone): e", "feat(conflicts): f",
    "docs: g", "Plain subject",
)

/** A repository whose subjects don't. */
private val PLAIN = historyOf("Fix the parser", "Add tags to the clone dialog", "feat: one")

/**
 * The commit field's Conventional Commits suggestions (fork-only), rendered offscreen: types at the subject's start and
 * scopes after the type's parenthesis, as the setting and the history say, with the keys and the notes.
 */
class CommitTypeSuggestionsTest {
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun close() {
        hosts.forEach { it.close() }
    }

    @Test
    fun `suggests types in a repository that uses them, the most used first`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host("f", CONVENTIONAL)
        host.focus()

        assertEquals(listOf("fix", "feat"), host.rows())
        assertEquals("fix", host.highlighted(), "Something is typed: the first row")
        assertTrue("8 of the last 9 subjects here start with a type" in host.texts(), host.texts().toString())
    }

    @Test
    fun `the setting decides, automatic follows the history, always shows, off never`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Host("f", PLAIN).use { it.focus(); assertTrue(it.rows().isEmpty(), "Automatic, a plain history") }
            Host("f", PLAIN, ConventionalCommitsMode.ALWAYS).use { host ->
                host.focus()
                assertEquals(listOf("feat", "fix"), host.rows(), "feat was used once")
                assertTrue(host.texts().none { "subjects here" in it }, "No note on why: the setting asks for it")
            }
            Host("f", CONVENTIONAL, ConventionalCommitsMode.OFF).use { it.focus(); assertTrue(it.rows().isEmpty()) }
        }

    @Test
    fun `Enter picks the type, and the caret waits after its colon`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host("fe", CONVENTIONAL)
        host.focus()

        host.press(Key.Enter)

        assertEquals("feat: ", host.value.text)
        assertEquals(TextRange(6), host.value.selection)
        assertTrue(host.rows().isEmpty())
    }

    @Test
    fun `after the parenthesis the scopes of the type show, none highlighted, and down and Enter pick one`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val host = Host("feat(", CONVENTIONAL)
            host.focus()

            assertEquals(listOf("clone", "conflicts"), host.rows())
            assertNull(host.highlighted())
            assertTrue("Type ! before the colon for a breaking change" in host.texts())

            host.press(Key.DirectionDown)
            host.press(Key.Enter)

            assertEquals("feat(clone): ", host.value.text)
            assertEquals(TextRange(host.value.text.length), host.value.selection)
        }

    @Test
    fun `nothing while a plain subject is typed, nor before the field has the focus`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Host("Fix the parser", CONVENTIONAL).use { it.focus(); assertTrue(it.rows().isEmpty()) }
            Host("f", CONVENTIONAL).use { assertTrue(it.rows().isEmpty(), "Not focused") }
        }

    @Test
    fun `after Esc they hide until the text changes`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host("f", CONVENTIONAL)
        host.focus()

        host.coAuthors = host.coAuthors.copy(dismissals = 1)
        host.settle()
        assertTrue(host.rows().isEmpty())

        host.type("fe")
        assertEquals(listOf("feat"), host.rows())
    }

    @Test
    fun `a commitlint config narrows the types, adds its own, and says so`(): Unit = runBlocking(Dispatchers.Swing) {
        val rules = CommitlintRules("commitlint.config.js", types = EnumRule(setOf("feat", "fix", "security"), allows = true))

        Host("f", CONVENTIONAL, rules = rules).use { host ->
            host.focus()
            assertEquals(listOf("fix", "feat"), host.rows())
            assertTrue("Types from commitlint.config.js" in host.texts(), host.texts().toString())
        }

        Host("d", CONVENTIONAL, rules = rules).use { host ->
            host.focus()
            assertTrue(host.rows().isEmpty(), "docs isn't allowed")
        }

        Host("s", CONVENTIONAL, rules = rules).use { host ->
            host.focus()
            assertEquals(listOf("security"), host.rows())
        }
    }

    @Test
    fun `with a commitlint config, automatic suggests in a plain history too`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host("f", PLAIN, rules = CommitlintRules(".commitlintrc"))
        host.focus()

        assertEquals(listOf("feat", "fix"), host.rows())
        assertTrue(".commitlintrc is set up here" in host.texts(), host.texts().toString())
    }

    @Test
    fun `scopes come from the config when it lists them`(): Unit = runBlocking(Dispatchers.Swing) {
        val rules = CommitlintRules(".commitlintrc.json", scopes = EnumRule(setOf("ui", "clone"), allows = true))
        val host = Host("feat(", CONVENTIONAL, rules = rules)
        host.focus()

        assertEquals(listOf("clone", "ui"), host.rows(), "clone was used twice with feat; conflicts isn't allowed")
        assertTrue("Scopes from .commitlintrc.json" in host.texts(), host.texts().toString())
    }

    @Test
    fun `a click on a row picks it`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host("f", CONVENTIONAL)
        host.focus()

        host.clickRow("feat")

        assertEquals("feat: ", host.value.text)
    }

    /** [CommitField] with [message], the caret at its end, the [history] and the [mode]. */
    private inner class Host(
        message: String,
        history: ConventionalHistory,
        mode: ConventionalCommitsMode = ConventionalCommitsMode.AUTOMATIC,
        rules: CommitlintRules? = null,
    ) : AutoCloseable {
        var value by mutableStateOf(TextFieldValue(message, TextRange(message.length)))
        var coAuthors by mutableStateOf(CoAuthorState())
        private var isClosed = false

        private val scene = ImageComposeScene(width = 400, height = 520, density = Density(1f)) {
            AppTheme {
                Column {
                    // Room above the field, where the suggestions go
                    Spacer(Modifier.height(240.dp))

                    Box {
                        CommitField(
                            canCommit = true,
                            isAmend = false,
                            canAmend = true,
                            doCommit = {},
                            commitMessage = value,
                            previousCommitMessage = "Previous",
                            repositoryState = RepositoryState.SAFE,
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
                            messageHints = CommitMessageHints(conventionalCommits = mode),
                            coAuthors = coAuthors,
                            conventionalHistory = history,
                            commitlintRules = rules,
                        )
                    }
                }
            }
        }

        init {
            scene.render()
            hosts += this
        }

        private fun nodes(merged: Boolean): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            val all = mutableListOf<SemanticsNode>()

            fun collect(node: SemanticsNode) {
                all += node
                node.children.forEach(::collect)
            }

            collect(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
            all
        }

        fun texts(): List<String> = nodes(merged = false).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        private fun field(): SemanticsNode = nodes(merged = false).first { it.config.contains(SemanticsProperties.EditableText) }

        private fun rowNodes() = nodes(merged = true).filter { it.config.contains(SemanticsProperties.Selected) }

        /** The types or scopes in the rows: each row's first text. */
        fun rows(): List<String> = rowNodes().map { it.config[SemanticsProperties.Text].first().text }

        fun highlighted(): String? =
            rowNodes().firstOrNull { it.config[SemanticsProperties.Selected] }?.config?.get(SemanticsProperties.Text)?.first()?.text

        suspend fun clickRow(name: String) {
            val row = rowNodes().first { it.config[SemanticsProperties.Text].first().text == name }

            checkNotNull(row.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        suspend fun focus() {
            checkNotNull(field().config[SemanticsActions.RequestFocus].action).invoke()
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
