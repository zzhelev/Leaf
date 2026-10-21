// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.commitmessage.CleanupMode
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

private const val RUNS_ON = "No blank line after the subject."
private const val INSERT = "Insert one"

/** A subject of [length] characters. */
private fun subject(length: Int) = "Make the parser keep its place after a rebase stops at a conflict".padEnd(length, 'x').take(length)

/**
 * The commit field's conventions (fork-only): the subject's count in the title row and its tint past 72 characters,
 * comment lines dimmed as git leaves them out, the hint for a subject that runs on with its link, and the ruler.
 */
class CommitMessageConventionsTest {
    // --- The subject's count

    @Test
    fun `the count shows past 50 characters, muted, and as N of 72 past 72`(): Unit = runBlocking(Dispatchers.Swing) {
        Field(subject(50)).use { assertTrue(it.counts().isEmpty(), it.counts().toString()) }
        Field(subject(51)).use { assertEquals(listOf("51"), it.counts()) }
        Field(subject(72)).use { assertEquals(listOf("72"), it.counts()) }
        Field(subject(87)).use { assertEquals(listOf("87 / 72"), it.counts()) }
        // The subject is the first line git keeps: comments and blank lines before it don't count
        Field("# " + subject(80) + "\n\n" + subject(60) + "\n\n" + subject(90)).use { assertEquals(listOf("60"), it.counts()) }
    }

    @Test
    fun `the count is amber past 72 and muted below, in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((theme, scheme) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
            val titleBackground = scheme.tertiarySurface.toArgb()
            val over = Field(subject(87), theme = theme).use { it.ink("87 / 72", titleBackground) }
            val under = Field(subject(60), theme = theme).use { it.ink("60", titleBackground) }

            assertTrue(isAmber(over), "$theme: ${Integer.toHexString(over)}")
            assertFalse(isAmber(under), "$theme: ${Integer.toHexString(under)}")
            assertTrue(contrast(over, titleBackground) >= 4.5, "$theme: ${contrast(over, titleBackground)}")
        }
    }

    @Test
    fun `no count with the setting off, nor in a field that can't be edited`(): Unit = runBlocking(Dispatchers.Swing) {
        Field(subject(90), hints = CommitMessageHints(subjectLength = false)).use { assertTrue(it.counts().isEmpty()) }
        Field(subject(90), readOnlyRebase = true).use { assertTrue(it.counts().isEmpty()) }
    }

    // --- The field's styles

    @Test
    fun `the field tints the subject past 72 characters, and dims and slants the lines git leaves out`() {
        val text = subject(80) + "\n\n# Leave this out\nBody\n"
        val styling = CommitMessageStyling(MessageCleanup.DEFAULT, true, Color.Gray, Color.Yellow)
        val styled = styling.filter(AnnotatedString(text)).text

        val tint = styled.spanStyles.single { it.item.background == Color.Yellow }
        assertEquals(subject(80).substring(72), text.substring(tint.start, tint.end))

        val dropped = styled.spanStyles.single { it.item.color == Color.Gray }
        assertEquals("# Leave this out", text.substring(dropped.start, dropped.end))
        assertEquals(FontStyle.Italic, dropped.item.fontStyle)

        // The setting off: no tint, and comment lines are still shown as left out
        val untinted = styling.copy(tintsOverflow = false).filter(AnnotatedString(text)).text
        assertTrue(untinted.spanStyles.none { it.item.background == Color.Yellow })
        assertEquals(1, untinted.spanStyles.size)

        // git keeps comment lines with commit.cleanup=whitespace
        val kept = styling.copy(cleanup = MessageCleanup(CleanupMode.WHITESPACE, "#")).filter(AnnotatedString(text)).text
        assertTrue(kept.spanStyles.none { it.item.color == Color.Gray })
    }

    @Test
    fun `a dimmed comment line is drawn fainter than the message`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((theme, scheme) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
            val background = scheme.background.toArgb()
            val message = Field("Keep this line", theme = theme).use { it.fieldInk(background) }
            val comment = Field("Subject\n\n# Leave this line out", theme = theme).use {
                it.fieldInk(background, fromY = 50f)
            }

            assertTrue(
                contrast(comment, background) < contrast(message, background) - 2.0,
                "$theme: comment ${contrast(comment, background)}, message ${contrast(message, background)}",
            )
        }
    }

    // --- A subject that runs on

    @Test
    fun `says that a subject runs on, and its link inserts the blank line`(): Unit = runBlocking(Dispatchers.Swing) {
        Field("Fix the parser\nIt lost its place").use { field ->
            assertEquals(listOf("$RUNS_ON $INSERT"), field.texts().filter { RUNS_ON in it })

            field.clickLink(INSERT)

            assertEquals("Fix the parser\n\nIt lost its place", field.value.text)
            assertTrue(field.texts().none { RUNS_ON in it }, "The hint goes with the blank line")
        }
    }

    @Test
    fun `no hint for a subject that stands alone, nor in a field that can't be edited`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (message in listOf("Fix the parser", "Fix the parser\n\nBody", "Fix the parser\n# a comment\n")) {
                Field(message).use { assertTrue(it.texts().none { RUNS_ON in it }, message) }
            }

            Field("Fix the parser\nIt lost its place", readOnlyRebase = true).use { field ->
                assertTrue(field.texts().none { RUNS_ON in it })
            }
        }

    @Test
    fun `inserting the blank line keeps the caret and the selection with their text`() {
        val text = "Fix the parser\nIt lost its place"
        val at = "Fix the parser".length

        fun inserted(selection: TextRange) =
            TextFieldValue(text, selection).withBlankLineAfterSubject(MessageCleanup.DEFAULT).selection

        assertEquals(TextRange(at), inserted(TextRange(at)), "A caret at the subject's end stays there")
        assertEquals(TextRange(3), inserted(TextRange(3)))
        assertEquals(TextRange(at + 4), inserted(TextRange(at + 3)))
        assertEquals(TextRange(2, at + 6), inserted(TextRange(2, at + 5)))

        val standsAlone = TextFieldValue("Fix\n\nBody", TextRange(4))
        assertEquals(standsAlone, standsAlone.withBlankLineAfterSubject(MessageCleanup.DEFAULT))
    }

    // --- The ruler

    @Test
    fun `the ruler shows at its column once the field is wide enough, and not before`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val ruler = CommitMessageHints(rulerColumn = 72)

            Field("Subject", hints = ruler, width = 700).use { field ->
                val x = checkNotNull(field.rulerX()) { "No ruler" }
                // Column 72 of the 13 px monospace font, 16 px in
                assertTrue(x in 560..600, "the ruler is at $x")
            }

            // Nor past the field's edge, over what is next to it: Compose doesn't clip the field's drawing
            Field("Subject", hints = ruler, width = 330, sceneWidth = 700).use { field -> assertNull(field.rulerX()) }
            Field("Subject", width = 700).use { field -> assertNull(field.rulerX(), "No ruler without the setting") }

            Field("Subject", hints = CommitMessageHints(rulerColumn = 50), width = 700).use { field ->
                val x = field.rulerX()!!
                assertTrue(x in 390..420, "column 50 is at $x")
            }
        }

    // --- The helpers

    /** [CommitField] with [message] and nothing else, as the status pane shows it. */
    private inner class Field(
        message: String,
        private val hints: CommitMessageHints = CommitMessageHints(),
        private val readOnlyRebase: Boolean = false,
        theme: Theme = Theme.Dark,
        private val width: Int = 400,
        private val sceneWidth: Int = width,
    ) : AutoCloseable {
        var value by mutableStateOf(TextFieldValue(message))

        private val state = StatusState(
            isLoading = false,
            commitMessage = TextFieldValue(message),
            previousCommitMessage = "Previous",
            // A rebase's stop that can't be amended: the field shows the last commit's message, read-only
            repositoryState = if (readOnlyRebase) RepositoryState.REBASING_INTERACTIVE else RepositoryState.SAFE,
        )

        private val scene = ImageComposeScene(width = sceneWidth, height = 260, density = Density(1f)) {
            AppTheme(selectedTheme = theme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    CommitField(
                        canCommit = state.canCommit,
                        isAmend = false,
                        canAmend = state.canAmend,
                        doCommit = {},
                        commitMessage = value,
                        previousCommitMessage = state.previousCommitMessage,
                        repositoryState = state.repositoryState,
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
                        modifier = Modifier.height(260.dp).width(width.dp),
                        messageHints = hints,
                    )
                }
            }
        }

        init {
            scene.render()
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

        /** The title row's count: the texts that are only digits, or digits over 72. */
        fun counts(): List<String> = texts().filter { it.matches(Regex("""\d+( / 72)?""")) }

        suspend fun clickLink(text: String) {
            val link = nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text } ||
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text in it } ||
                        node.parent?.config?.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text })
            }

            checkNotNull(checkNotNull(link) { "No link: ${texts()}" }.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        private suspend fun settle() {
            repeat(6) {
                scene.render(System.nanoTime())
                delay(20)
            }
        }

        private suspend fun bitmap(): Bitmap {
            settle()

            return Bitmap.makeFromImage(scene.render(System.nanoTime()))
        }

        /** The color of [text]: the pixel in its bounds farthest from [background]. */
        suspend fun ink(text: String, background: Int): Int {
            val bounds = nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
                .boundsInRoot

            return farthest(bitmap(), bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt(), background)
        }

        /** The color of the field's text below [fromY]: the pixel farthest from [background]. */
        suspend fun fieldInk(background: Int, fromY: Float = 0f): Int {
            val field = nodes().first { it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot

            return farthest(
                bitmap(),
                field.left.toInt(),
                maxOf(field.top, field.top + fromY).toInt(),
                field.right.toInt(),
                (field.bottom - 4f).toInt(),
                background,
            )
        }

        /** Where a vertical line runs down most of the field's height, in the field or beside it: the ruler, or null. */
        suspend fun rulerX(): Int? {
            val field = nodes().first { it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot
            val bitmap = bitmap()
            val top = field.top.toInt() + 2
            val bottom = field.bottom.toInt() - 4
            val background = bitmap.getColor(field.left.toInt() + 2, bottom - 2)

            return (field.left.toInt() + 20 until sceneWidth - 2).firstOrNull { x ->
                (top until bottom).count { y -> bitmap.getColor(x, y) != background } > (bottom - top) * 0.9
            }
        }

        override fun close() = scene.close()
    }
}

private fun farthest(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, background: Int): Int {
    var best = background
    var bestContrast = 0.0

    for (y in top until bottom) {
        for (x in left until right) {
            val color = bitmap.getColor(x, y)
            val c = contrast(color, background)

            if (c > bestContrast) {
                bestContrast = c
                best = color
            }
        }
    }

    return best
}

/** Reddish-yellow: more red than green by some margin, and little blue. */
private fun isAmber(color: Int): Boolean {
    val red = (color shr 16) and 0xFF
    val green = (color shr 8) and 0xFF
    val blue = color and 0xFF

    return red > green + 20 && green > blue + 40 && abs(red - blue) > 100
}

private fun contrast(a: Int, b: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }

        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    val la = luminance(a)
    val lb = luminance(b)

    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
