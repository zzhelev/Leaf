// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package dev.app.leaf.ui.find

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import dev.app.leaf.domain.find.FindMatch
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.ui.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val FIND = "Find"
private const val LINE = "Line"

/**
 * Find, go to line and Select all in the diff (fork-only, upstream issues 275 and 312), rendered as the diff pane lays
 * the lines out, with the tab's keys.
 */
class FindInDiffTest {
    private fun withDiff(
        diff: DiffResult.TextDiff,
        wrap: Boolean = true,
        theme: Theme = Theme.Dark,
        width: Int = 900,
        test: suspend DiffFindScene.() -> Unit,
    ): Unit = runBlocking(Dispatchers.Swing) {
        DiffFindScene(diff, wrap = wrap, theme = theme, width = width).use { scene ->
            scene.settle()
            // The tab has the focus, as its keys need
            scene.root.requestFocus()
            scene.settle()
            scene.test()
        }
    }

    @Test
    fun `the command key and F opens the strip with the focus in its field, and typing finds and counts`() = withDiff(hunksDiff) {
        assertFalse(hasField(FIND))

        press(command(Key.F))

        assertTrue(find.isOpen)
        assertTrue(isFocused(FIND))

        type(FIND, "parser")

        assertEquals(5, find.result.matches.size)
        assertEquals(0, find.current)
        assertTrue("1 of 5" in texts(), texts().toString())

        find.matchCase = true
        settle()

        assertEquals(4, find.result.matches.size)
        assertTrue("1 of 4" in texts(), texts().toString())

        type(FIND, "nothing like it")
        assertTrue("0 of 0" in texts(), texts().toString())
    }

    @Test
    fun `in a narrow pane find keeps its room next to the counter, and go to line its count`() =
        withDiff(hunksDiff, width = 330) {
            press(command(Key.F))
            type(FIND, "parser")

            assertTrue("1 of 5" in texts(), texts().toString())
            assertTrue(fieldWidth(FIND) >= 120f, "the typed text has room: ${fieldWidth(FIND)} px")

            press(goToLineKey)
            assertTrue("of 400" in texts(), texts().toString())
            assertTrue(fieldWidth(LINE) >= 48f, "a six-digit line has room: ${fieldWidth(LINE)} px")
        }

    @Test
    fun `find and go to line take turns in the strip, and each keeps what was typed`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")
        assertTrue(hasField(FIND))
        assertFalse(hasField(LINE), "find shows no line field")

        press(goToLineKey)
        assertFalse(hasField(FIND), "go to line shows no find field")
        assertTrue(isFocused(LINE))
        assertTrue("Go to line" in texts(), texts().toString())
        assertTrue(find.result.matches.isEmpty(), "find's matches go")

        type(LINE, "300")
        press(plain(Key.Enter))
        assertEquals(17, find.goToRow)

        press(command(Key.F))
        assertTrue(isFocused(FIND))
        assertFalse(hasField(LINE))
        assertEquals("parser", find.query.text)
        assertEquals(5, find.result.matches.size, "found again")

        press(goToLineKey)
        assertEquals("300", find.lineText.text)
    }

    @Test
    fun `the next match from go to line shows find again`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")
        press(goToLineKey)

        press(command(Key.G).takeIf { isMac } ?: plain(Key.F3))

        assertEquals(FindField.FIND, find.mode)
        assertTrue(isFocused(FIND))
        assertEquals(5, find.result.matches.size)
    }

    @Test
    fun `going to a line takes find's marks away`() = withDiff(hunksDiff, theme = Theme.Light) {
        press(command(Key.F))
        type(FIND, "parser")
        assertTrue(pixelsOf(LIGHT_FIND_COLORS.match) > 50)

        press(goToLineKey)
        assertEquals(0, pixelsOf(LIGHT_FIND_COLORS.match))
        assertEquals(0, pixelsOf(LIGHT_FIND_COLORS.current))
    }

    @Test
    fun `matches and the current one are drawn in the line, in both themes, and closing takes them away`() {
        for (theme in listOf(Theme.Light, Theme.Dark)) withDiff(hunksDiff, theme = theme) {
            val colors = if (theme == Theme.Light) LIGHT_FIND_COLORS else DARK_FIND_COLORS

            assertEquals(0, pixelsOf(colors.match), "$theme: no match before find")

            press(command(Key.F))
            type(FIND, "parser")

            assertTrue(pixelsOf(colors.match) > 50, "$theme: the matches' fill shows")
            assertTrue(pixelsOf(colors.current) > 10, "$theme: the current match's fill shows")
            assertTrue(pixelsOf(colors.outline) > 10, "$theme: the current match's outline shows")

            press(plain(Key.Escape))

            assertFalse(find.isOpen, "$theme: Esc closes the strip")
            assertFalse(hasField(FIND))
            assertEquals(0, pixelsOf(colors.match), "$theme: no match after closing")
            assertEquals(0, pixelsOf(colors.current), "$theme: no current match after closing")
            assertTrue(isViewFocused, "$theme: the view takes the focus back")
        }
    }

    @Test
    fun `a match out of sight is scrolled to, with the rows above it`() = withDiff(fullDiff) {
        press(command(Key.F))
        type(FIND, "here")

        // Line 300 is the full view's row 301
        assertEquals(listOf(FindMatch(301, 0, 10, 14)), find.result.matches)
        assertTrue(301 in visibleRows(), visibleRows().toString())
        assertTrue(listState.firstVisibleItemIndex > 250, "it isn't the top row: ${listState.firstVisibleItemIndex}")
        assertTrue(listState.firstVisibleItemIndex < 301, "rows show above it: ${listState.firstVisibleItemIndex}")
    }

    @Test
    fun `typing starts from the first match below the list's top`() = withDiff(fullDiff) {
        listState.scrollToItem(100)
        settle()

        press(command(Key.F))
        type(FIND, "parser")

        // Below row 100 there is only line 300's
        assertEquals(301, find.currentMatch?.row)
    }

    @Test
    fun `Enter, Shift+Enter and the tab's keys step through the matches, and wrap at both ends`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")
        assertEquals(0, find.current)

        press(plain(Key.Enter))
        assertEquals(1, find.current)

        press(plain(Key.Enter, shift = true))
        press(plain(Key.Enter, shift = true))
        assertEquals(4, find.current, "previous from the first is the last")

        press(plain(Key.Enter))
        assertEquals(0, find.current, "next from the last is the first")

        press(plain(Key.F3))
        assertEquals(1, find.current)
        press(plain(Key.F3, shift = true))
        assertEquals(0, find.current)

        if (isMac) {
            press(command(Key.G))
            assertEquals(1, find.current)
            press(command(Key.G, shift = true))
            assertEquals(0, find.current)
        }

        clickButton("Next match")
        assertEquals(1, find.current)
        clickButton("Previous match")
        assertEquals(0, find.current)
        assertTrue("1 of 5" in texts(), texts().toString())
    }

    @Test
    fun `typing on keeps the match that find had stepped to, rather than starting from the top`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")
        repeat(3) { press(plain(Key.Enter)) }
        assertEquals(FindMatch(9, 0, 7, 13), find.currentMatch)

        type(FIND, "parser(")

        // "Parser(" on row 7 comes first, but find stays at row 9's
        assertEquals(listOf(FindMatch(7, 0, 13, 20), FindMatch(9, 0, 7, 14)), find.result.matches)
        assertEquals(1, find.current)
    }

    @Test
    fun `a diff loaded again doesn't scroll back to a match that the user scrolled away from`() = withDiff(fullDiff) {
        press(command(Key.F))
        type(FIND, "here")
        assertTrue(301 in visibleRows())

        listState.scrollToItem(0)
        settle()
        diff = DiffResult.Text(fullDiff.diffEntry, fullDiff.hunks)
        settle()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(1, find.result.matches.size, "it still finds")
    }

    @Test
    fun `a strip that comes back after a file without lines leaves the focus where it is`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")
        assertTrue(isFocused(FIND))

        root.requestFocus()
        settle()
        isHidden = true
        settle()
        isHidden = false
        settle()

        assertTrue(hasField(FIND), "the strip is back, with its text")
        assertFalse(isFocused(FIND), "and doesn't take the focus")
    }

    @Test
    fun `the next match's key opens the strip when it is closed`() = withDiff(hunksDiff) {
        press(plain(Key.F3))

        assertTrue(find.isOpen)
        assertTrue(isFocused(FIND))
    }

    @Test
    fun `the command key and F goes to the log's search while the log has the focus`() = withDiff(hunksDiff) {
        targets.isLogFocused = true
        press(command(Key.F))

        assertEquals(1, logSearches)
        assertFalse(find.isOpen)

        targets.isLogFocused = false
        press(command(Key.F))

        assertEquals(1, logSearches)
        assertTrue(find.isOpen)
    }

    @Test
    fun `Esc closes the strip only while the view has the focus`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")

        targets.focusChanged(find, hasFocus = false)
        press(plain(Key.Escape))
        assertTrue(find.isOpen, "Esc is someone else's while the focus is elsewhere")

        targets.focusChanged(find, hasFocus = true)
        press(plain(Key.Escape))
        assertFalse(find.isOpen)
    }

    @Test
    fun `go to line goes to the new version's line in the unified view`() = withDiff(hunksDiff) {
        press(goToLineKey)

        assertTrue(find.isOpen)
        assertTrue(isFocused(LINE))
        assertTrue("of 400" in texts(), texts().toString())

        type(LINE, "300")
        press(plain(Key.Enter))

        // Line 300 is in the second hunk: its header is row 12, and it starts at line 296
        assertEquals(17, find.goToRow)
        assertTrue(17 in visibleRows(), visibleRows().toString())
        assertEquals(0, completeFileRequests)

        type(LINE, "6")
        press(plain(Key.Enter))
        // The old line 6 was replaced: the new one is the row after it
        assertEquals(7, find.goToRow)
    }

    @Test
    fun `go to line goes to the new side's line in the split view`() = withDiff(split(hunksDiff)) {
        press(goToLineKey)
        type(LINE, "300")
        press(plain(Key.Enter))

        // A split row pairs the removed and the added line 6, so line 300 is a row higher than in the unified view
        assertEquals(16, find.goToRow)
        assertTrue(16 in visibleRows(), visibleRows().toString())
    }

    @Test
    fun `a line that the hunks leave out shows the whole file, then is gone to`() = withDiff(hunksDiff) {
        press(goToLineKey)
        type(LINE, "150")
        press(plain(Key.Enter))

        assertEquals(1, completeFileRequests)
        assertTrue(diff === fullDiff)
        // Line 150 is the full view's row 151, after the header and the replaced line's two rows
        assertEquals(151, find.goToRow)
        assertTrue(151 in visibleRows(), visibleRows().toString())
        assertTrue(isFocused(LINE), "the line field keeps the focus")
    }

    @Test
    fun `a line past the end is the last one`() = withDiff(fullDiff) {
        press(goToLineKey)
        type(LINE, "9999")
        press(plain(Key.Enter))

        assertEquals(FILE_LINES + 1, find.goToRow)
    }

    @Test
    fun `Select all selects every line, also those out of sight, and Copy copies them`() = withDiff(hunksDiff) {
        click(Offset(300f, 60f))
        press(command(Key.A))

        assertEquals(0, find.selectedCell)

        press(command(Key.C))

        val expected = hunksDiff.hunks.flatMap { it.lines }.joinToString("\n") { it.text.trimEnd('\n') }
        assertEquals(expected, clipboard.text)
        assertEquals(21, clipboard.text!!.lines().size, "every line of the two hunks, the replaced one's two too")

        click(Offset(300f, 60f))
        assertNull(find.selectedCell, "a press ends it")
    }

    @Test
    fun `Select all in the split view copies the side that was pressed`() = withDiff(split(hunksDiff)) {
        click(Offset(200f, 60f))
        press(command(Key.A))
        press(command(Key.C))

        assertEquals(0, find.selectedCell)
        assertTrue(clipboard.text!!.lines().contains("val old = 0"), "the old side")
        assertFalse(clipboard.text!!.lines().contains("val parser = Parser()"))

        click(Offset(700f, 60f))
        press(command(Key.A))
        press(command(Key.C))

        assertEquals(1, find.selectedCell)
        assertTrue(clipboard.text!!.lines().contains("val parser = Parser()"), "the new side")
        assertFalse(clipboard.text!!.lines().contains("val old = 0"))
    }

    @Test
    fun `another key ends Select all, and the selection is drawn`() = withDiff(hunksDiff, theme = Theme.Light) {
        click(Offset(300f, 60f))
        val before = pixels()

        press(command(Key.A))
        assertTrue(differingPixels(before, pixels()) > 1000, "every line's text has the selection's background")

        press(plain(Key.DirectionDown))
        assertNull(find.selectedCell)
        assertTrue(differingPixels(before, pixels()) < 100, "and loses it")
    }

    @Test
    fun `Select all works after Esc closes the strip, without a click in the lines`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")
        press(plain(Key.Escape))
        assertTrue(isViewFocused, "the view has the focus, which isn't in its lines")

        press(command(Key.A))
        press(command(Key.C))

        assertEquals(0, find.selectedCell)
        assertEquals(21, clipboard.text?.lines()?.size, "every line of the two hunks")
    }

    @Test
    fun `Select all works with the focus on the view's own frame`() = withDiff(hunksDiff) {
        view.requestFocus()
        settle()

        press(command(Key.A))

        assertEquals(0, find.selectedCell)
    }

    @Test
    fun `Select all in the find field selects what is typed, not the lines`() = withDiff(hunksDiff) {
        press(command(Key.F))
        type(FIND, "parser")

        press(command(Key.A))

        assertNull(find.selectedCell)
        assertEquals(0, find.query.selection.start)
        assertEquals(6, find.query.selection.end)
    }

    @Test
    fun `a match off to the side of lines that don't wrap is scrolled into sight`() {
        val long = "x".repeat(300) + " needle"
        val hunk = Hunk(
            "@@ -1 +1 @@",
            listOf(Line("short\n", 0, 0, LineType.CONTEXT), Line("$long\n", 1, 1, LineType.ADDED)),
            RawText("short\n".toByteArray()),
            RawText("short\n$long\n".toByteArray()),
        )
        val diff = DiffResult.Text(hunksDiff.diffEntry, listOf(hunk))

        withDiff(diff, wrap = false) {
            val sideways = sideways!!
            assertEquals(0f, sideways.offset)

            press(command(Key.F))
            type(FIND, "needle")

            assertTrue(sideways.offset > 0f, "it scrolled sideways")
            assertFalse(find.sidewaysRevealPending)

            // The user scrolls back, and the match stays where it is
            sideways.scrollTo(0f)
            settle()
            assertEquals(0f, sideways.offset)

            // Go to line goes to a line's start
            press(plain(Key.Enter))
            assertTrue(sideways.offset > 0f)
            press(goToLineKey)
            type(LINE, "2")
            press(plain(Key.Enter))
            assertEquals(0f, sideways.offset)
        }
    }
}
