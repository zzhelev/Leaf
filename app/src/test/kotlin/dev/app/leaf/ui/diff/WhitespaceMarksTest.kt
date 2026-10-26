// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.theme.AppTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val WIDTH = 400
private const val HEIGHT = 60
private val PAPER = Color.White

/** Where the diff marks whitespace (fork-only, upstream issue 138): what it marks, and that the text stays as it was. */
@OptIn(ExperimentalComposeUiApi::class)
class WhitespaceMarksTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun pieces(vararg text: String) = text.toList()

    @Test
    fun `spaces and a tab are marked where the diff shows them, a tab as four columns`() {
        val marks = whitespaceMarksOf(pieces("\tval x = 1\n"))

        assertEquals(
            listOf(
                WhitespaceMark(0, 4, WhitespaceKind.TAB),
                WhitespaceMark(7, 1, WhitespaceKind.SPACE),
                WhitespaceMark(9, 1, WhitespaceKind.SPACE),
                WhitespaceMark(11, 1, WhitespaceKind.SPACE),
            ),
            marks.marks,
        )
        assertEquals(-1, marks.trailingStart)
        assertFalse(marks.endsWithCarriageReturn)
    }

    @Test
    fun `whitespace at the end of the line is trailing, from its first character`() {
        val marks = whitespaceMarksOf(pieces("a  \t\n"))

        assertEquals(1, marks.trailingStart)
        assertEquals(listOf(true, true, true), marks.marks.map(marks::isTrailing))

        assertEquals(-1, whitespaceMarksOf(pieces("a b\n")).trailingStart, "A space in the middle isn't trailing")
        assertEquals(0, whitespaceMarksOf(pieces("   \n")).trailingStart, "A line of whitespace is all trailing")
        assertEquals(4, whitespaceMarksOf(pieces("a  b  \n")).trailingStart, "Only the last run counts")
        assertEquals(1, whitespaceMarksOf(pieces("a  ", "\r", "\n")).trailingStart, "A CR cut off the end isn't whitespace to mark")
        assertEquals(WhitespaceMarks.NONE, whitespaceMarksOf(pieces("")))
        assertEquals(WhitespaceMarks.NONE, whitespaceMarksOf(pieces("\n")))
    }

    @Test
    fun `a CRLF line ending is marked, and a plain one isn't`() {
        assertTrue(whitespaceMarksOf(pieces("a\r\n")).endsWithCarriageReturn)
        assertFalse(whitespaceMarksOf(pieces("a\n")).endsWithCarriageReturn)
        assertFalse(whitespaceMarksOf(pieces("a")).endsWithCarriageReturn, "The last line may have no ending")
        assertTrue(whitespaceMarksOf(pieces("a", "b\r\n")).endsWithCarriageReturn, "In the piece that ends the line")
        // The word diff of a line that gained a CR cuts between the CR and the LF
        assertTrue(whitespaceMarksOf(pieces("a", "\r", "\n")).endsWithCarriageReturn)
        assertTrue(whitespaceMarksOf(pieces("a\r", "\n")).endsWithCarriageReturn)
        assertFalse(whitespaceMarksOf(pieces("a", "\n")).endsWithCarriageReturn)
    }

    @Test
    fun `the pieces of a line that the word diff cut follow each other`() {
        val marks = whitespaceMarksOf(pieces("foo", " ", "bar \n"))

        assertEquals(listOf(3, 7), marks.marks.map { it.start })
        assertEquals(7, marks.trailingStart, "The trailing space is in the last piece")
    }

    @Test
    fun `a line cut where the diff cuts it has marks up to there, and no trailing whitespace`() {
        val marks = whitespaceMarksOf(pieces(" ".repeat(MAX_DIFF_LINE_CHARACTERS + 500) + "\n"))

        assertEquals(MAX_DIFF_LINE_CHARACTERS, marks.marks.size)
        assertEquals(-1, marks.trailingStart)
    }

    // What is drawn

    private class Shown(val text: String, val layout: TextLayoutResult, val bitmap: Bitmap, val node: SemanticsNode)

    private fun show(
        text: String,
        showWhitespace: Boolean,
        pieces: List<String> = listOf(text),
    ): Shown {
        var layout: TextLayoutResult? = null

        scene?.close()
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme {
                CompositionLocalProvider(LocalShowWhitespace provides showWhitespace) {
                    Box(Modifier.fillMaxSize().background(PAPER).padding(8.dp)) {
                        val overlay = rememberWhitespaceOverlay(pieces)

                        Text(
                            text = text.replace("\t", "    ").removeSuffix("\r\n").removeSuffix("\n"),
                            modifier = overlay?.modifier ?: Modifier,
                            style = TextStyle(color = Color.Black, fontSize = 14.sp),
                            onTextLayout = {
                                layout = it
                                overlay?.onTextLayout?.invoke(it)
                            },
                        )
                    }
                }
            }
        }

        val scene = scene!!
        scene.render(System.nanoTime())
        val bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime()))
        val node = scene.semanticsOwners.map { it.rootSemanticsNode }.flatMap { all(it) }
            .first { it.config.getOrNull(SemanticsProperties.Text) != null }

        return Shown(text, checkNotNull(layout), bitmap, node)
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    /** The text's box in the window: the layout's own boxes start at its top left, 8 dp in. */
    private fun Shown.box(index: Int): Rect = layout.getBoundingBox(index).translate(8f, 8f)

    private fun Shown.isPaper(x: Float, y: Float) = bitmap.getColor(x.toInt(), y.toInt()) == PAPER.toArgb()

    @Test
    fun `without the switch nothing is drawn, and the text is what it was`() {
        val off = show("a b\tc  ", showWhitespace = false)

        for (index in off.text.indices) {
            val box = off.box(index)

            if (off.text[index] == ' ') {
                assertTrue(off.isPaper(box.center.x, box.center.y), "No mark at $index")
            }
        }
    }

    @Test
    fun `a space gets a dot in the middle of its cell, and the text around it stays as it was`() {
        val on = show("a b", showWhitespace = true)
        val off = show("a b", showWhitespace = false)
        val space = on.box(1)

        assertFalse(on.isPaper(space.center.x, space.center.y), "The dot")
        assertTrue(off.isPaper(space.center.x, space.center.y), "No dot without the switch")
        // The dot is small: the cell's top and bottom stay clear
        assertTrue(on.isPaper(space.center.x, space.top + 1f), "Clear above the dot")
        assertTrue(on.isPaper(space.center.x, space.bottom - 1f), "Clear below the dot")
        assertEquals(
            off.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text },
            on.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text },
            "What is selected and copied is the text, with no mark in it",
        )
    }

    @Test
    fun `a tab gets an arrow across its four columns`() {
        val on = show("a\tb", showWhitespace = true)
        val first = on.box(1)
        val last = on.box(4)
        val y = first.center.y

        // The shaft, from near the first column to near the last
        val shaft = (first.left + 4).toInt()..(last.right - 4).toInt()

        assertTrue(shaft.count { x -> !on.isPaper(x.toFloat(), y) } > shaft.count() / 2, "A line across the tab")
        assertFalse(on.isPaper(last.right - 4f, y), "To its tip")
        assertTrue(on.isPaper(first.left + 1f, y), "The arrow starts a little in, not at the cell's edge")
        assertTrue(on.isPaper((first.left + last.right) / 2, first.top + 1f), "Nothing above the shaft")
    }

    @Test
    fun `trailing whitespace is tinted, and whitespace in the middle isn't`() {
        val on = show("x  y  ", showWhitespace = true)
        val inside = on.box(1)
        val trailing = on.box(4)

        // The tint, off the dot at the cell's middle
        val tinted = on.bitmap.getColor((trailing.left + 1).toInt(), (trailing.top + 2).toInt())
        val plain = on.bitmap.getColor((inside.left + 1).toInt(), (inside.top + 2).toInt())

        assertNotEquals(PAPER.toArgb(), tinted)
        assertEquals(PAPER.toArgb(), plain)
    }

    @Test
    fun `a CRLF line ends with a CR after its last character`() {
        val crlf = show("abc\r\n", showWhitespace = true, pieces = listOf("abc\r\n"))
        val lf = show("abc\n", showWhitespace = true, pieces = listOf("abc\n"))
        val end = crlf.box(2).right

        val pixelsAfterText = { shown: Shown ->
            (end.toInt() + 4 until WIDTH - 8).sumOf { x ->
                (0 until HEIGHT).count { y -> shown.bitmap.getColor(x, y) != PAPER.toArgb() }
            }
        }

        assertTrue(pixelsAfterText(crlf) > 20, "A small outlined CR")
        assertEquals(0, pixelsAfterText(lf), "Nothing after a plain line")
    }

    @Test
    fun `an empty line of a CRLF file has its CR too`() {
        val crlf = show("", showWhitespace = true, pieces = listOf("\r\n"))
        val lf = show("", showWhitespace = true, pieces = listOf("\n"))

        val marked = { shown: Shown ->
            (0 until WIDTH).sumOf { x -> (0 until HEIGHT).count { y -> shown.bitmap.getColor(x, y) != PAPER.toArgb() } }
        }

        assertTrue(marked(crlf) > 20, "A small outlined CR, where there is no text to draw after")
        assertEquals(0, marked(lf))
    }

    /** [DiffText], the diff's text, in a scene: [showWhitespace] is what its [LocalShowWhitespace] says. */
    private fun diffTextPixels(showWhitespace: Boolean): List<Int> {
        scene?.close()
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme(dev.app.leaf.domain.models.ui.Theme.Light) {
                CompositionLocalProvider(LocalShowWhitespace provides showWhitespace) {
                    Box(Modifier.fillMaxSize().background(PAPER).padding(8.dp)) {
                        DiffText("  a b\t\n", null)
                    }
                }
            }
        }

        scene!!.render(System.nanoTime())
        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))

        return (0 until WIDTH * HEIGHT).map { bitmap.getColor(it % WIDTH, it / WIDTH) }
    }

    @Test
    fun `the diff's own text draws the marks only while the switch is on`() {
        val off = diffTextPixels(showWhitespace = false)
        val on = diffTextPixels(showWhitespace = true)

        assertNotEquals(off, on)
        assertEquals(off, diffTextPixels(showWhitespace = false), "Drawing it twice is the same")
        // What the resolver and other users of DiffText get: no mark, whatever the diff pane says
        assertEquals(off, diffTextPixelsWithoutProvider())
    }

    private fun diffTextPixelsWithoutProvider(): List<Int> {
        scene?.close()
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme(dev.app.leaf.domain.models.ui.Theme.Light) {
                Box(Modifier.fillMaxSize().background(PAPER).padding(8.dp)) {
                    DiffText("  a b\t\n", null)
                }
            }
        }

        scene!!.render(System.nanoTime())
        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))

        return (0 until WIDTH * HEIGHT).map { bitmap.getColor(it % WIDTH, it / WIDTH) }
    }

    @Test
    fun `the marks come from the pieces of the line, as the diff draws them`() {
        val line = MatchLine(
            listOf(
                DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, "foo"),
                DiffMatchPatch.Diff(DiffMatchPatch.Operation.INSERT, " "),
                DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, "bar\n"),
            )
        )
        val marks = whitespaceMarksOf(line.diffs.map { it.text })

        assertEquals(listOf(3), marks.marks.map { it.start })
    }
}
