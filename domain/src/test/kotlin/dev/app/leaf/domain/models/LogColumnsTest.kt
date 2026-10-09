// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogColumnsTest {
    private val all = LogColumnsSettings()
        .toggled(LogColumn.Author)
        .toggled(LogColumn.Commit)

    @Test
    fun `by default only the date is shown, as before the columns existed`() {
        val settings = LogColumnsSettings()

        assertEquals(listOf(LogColumn.Author, LogColumn.Date, LogColumn.Commit), settings.columns.map { it.column })
        assertFalse(settings.isVisible(LogColumn.Author))
        assertTrue(settings.isVisible(LogColumn.Date))
        assertFalse(settings.isVisible(LogColumn.Commit))
        assertEquals(listOf(150f, 110f, 80f), settings.columns.map { it.width })
        assertFalse(settings.dateShowsTime)
        assertEquals(120f, settings.graphMaxWidth)
    }

    @Test
    fun `toggling shows and hides one column`() {
        val shown = LogColumnsSettings().toggled(LogColumn.Commit)

        assertTrue(shown.isVisible(LogColumn.Commit))
        assertTrue(shown.isVisible(LogColumn.Date))
        assertFalse(shown.isVisible(LogColumn.Author))
        assertEquals(LogColumnsSettings(), shown.toggled(LogColumn.Commit))
    }

    @Test
    fun `resizing keeps a column between the minimum and maximum widths`() {
        assertEquals(200f, LogColumnsSettings().resized(LogColumn.Author, 200f).entry(LogColumn.Author).width)
        assertEquals(48f, LogColumnsSettings().resized(LogColumn.Author, 10f).entry(LogColumn.Author).width)
        assertEquals(600f, LogColumnsSettings().resized(LogColumn.Author, 5000f).entry(LogColumn.Author).width)
        assertEquals(150f, LogColumnsSettings().resized(LogColumn.Author, Float.NaN).entry(LogColumn.Author).width)
        assertEquals(110f, LogColumnsSettings().resized(LogColumn.Author, 10f).entry(LogColumn.Date).width)
    }

    @Test
    fun `showing the time widens a narrower date column, and hiding it doesn't shrink it`() {
        val withTime = LogColumnsSettings().withDateShowingTime(true)

        assertTrue(withTime.dateShowsTime)
        assertEquals(180f, withTime.entry(LogColumn.Date).width)

        val wide = LogColumnsSettings().resized(LogColumn.Date, 180f).withDateShowingTime(true)

        assertEquals(180f, wide.entry(LogColumn.Date).width)

        val withoutTime = withTime.withDateShowingTime(false)

        assertFalse(withoutTime.dateShowsTime)
        assertEquals(180f, withoutTime.entry(LogColumn.Date).width)
    }

    @Test
    fun `the graph's widest size stays between its limits`() {
        assertEquals(300f, LogColumnsSettings().withGraphMaxWidth(300f).graphMaxWidth)
        assertEquals(56f, LogColumnsSettings().withGraphMaxWidth(20f).graphMaxWidth)
        assertEquals(2000f, LogColumnsSettings().withGraphMaxWidth(9000f).graphMaxWidth)
        assertEquals(120f, LogColumnsSettings().withGraphMaxWidth(Float.POSITIVE_INFINITY).graphMaxWidth)
    }

    @Test
    fun `every visible column is shown when they fit`() {
        // 200 for the message + 150 + 110 + 80
        val fitted = all.fitting(availableWidth = 540f)

        assertEquals(listOf(LogColumn.Author, LogColumn.Date, LogColumn.Commit), fitted.shown.map { it.column })
        assertTrue(fitted.leftOut.isEmpty())
    }

    @Test
    fun `columns give way in the order commit, author, date`() {
        assertEquals(
            FittedLogColumns(all.columns.filter { it.column != LogColumn.Commit }, setOf(LogColumn.Commit)),
            all.fitting(availableWidth = 539f),
        )
        assertEquals(
            FittedLogColumns(
                all.columns.filter { it.column == LogColumn.Date },
                setOf(LogColumn.Commit, LogColumn.Author),
            ),
            all.fitting(availableWidth = 459f),
        )
        assertEquals(
            FittedLogColumns(emptyList(), setOf(LogColumn.Commit, LogColumn.Author, LogColumn.Date)),
            all.fitting(availableWidth = 309f),
        )
        assertEquals(listOf(LogColumn.Date), all.fitting(availableWidth = 310f).shown.map { it.column })
    }

    @Test
    fun `a hidden column takes no room and is not reported as left out`() {
        // Author is hidden: 200 + 110 + 80
        val settings = LogColumnsSettings().toggled(LogColumn.Commit)

        assertEquals(listOf(LogColumn.Date, LogColumn.Commit), settings.fitting(390f).shown.map { it.column })

        val narrow = settings.fitting(389f)

        assertEquals(listOf(LogColumn.Date), narrow.shown.map { it.column })
        assertEquals(setOf(LogColumn.Commit), narrow.leftOut)
    }

    @Test
    fun `each shown column also needs room for its divider`() {
        // 200 + (150 + 8) + (110 + 8) + (80 + 8)
        assertTrue(all.fitting(availableWidth = 564f, spacing = 8f).leftOut.isEmpty())
        assertEquals(setOf(LogColumn.Commit), all.fitting(availableWidth = 563f, spacing = 8f).leftOut)
    }

    @Test
    fun `the message's minimum width can be changed`() {
        assertTrue(all.fitting(availableWidth = 440f, messageMinWidth = 100f).leftOut.isEmpty())
        assertEquals(setOf(LogColumn.Commit), all.fitting(availableWidth = 439f, messageMinWidth = 100f).leftOut)
    }

    @Test
    fun `the graph is as wide as its lanes, up to its widest size, and never below the minimum`() {
        assertEquals(70f, graphColumnWidth(lanesWidth = 70f, maxWidth = 120f))
        assertEquals(120f, graphColumnWidth(lanesWidth = 300f, maxWidth = 120f))
        assertEquals(56f, graphColumnWidth(lanesWidth = 42f, maxWidth = 120f))
        assertEquals(56f, graphColumnWidth(lanesWidth = 300f, maxWidth = 30f))
    }

    @Test
    fun `dragging the graph's divider sets its widest size`() {
        // Narrower and wider, within the lanes
        assertEquals(100f, draggedGraphMaxWidth(maxWidth = 120f, shownWidth = 120f, lanesWidth = 300f, delta = -20f))
        assertEquals(150f, draggedGraphMaxWidth(maxWidth = 120f, shownWidth = 120f, lanesWidth = 300f, delta = 30f))
        // Not below the minimum
        assertEquals(56f, draggedGraphMaxWidth(maxWidth = 120f, shownWidth = 60f, lanesWidth = 300f, delta = -30f))
        // Not past the lanes
        assertEquals(300f, draggedGraphMaxWidth(maxWidth = 120f, shownWidth = 290f, lanesWidth = 300f, delta = 30f))
    }

    @Test
    fun `dragging past the lanes keeps a wider size set in another repository`() {
        // The repository needs 70 dp, and the saved size of 300 dp came from a busier one
        assertEquals(300f, draggedGraphMaxWidth(maxWidth = 300f, shownWidth = 70f, lanesWidth = 70f, delta = 10f))
        assertEquals(300f, draggedGraphMaxWidth(maxWidth = 300f, shownWidth = 70f, lanesWidth = 70f, delta = 0f))
        // Dragging narrower takes the size from what's shown
        assertEquals(60f, draggedGraphMaxWidth(maxWidth = 300f, shownWidth = 70f, lanesWidth = 70f, delta = -10f))
    }

    @Test
    fun `a drag that changes nothing on screen keeps the saved size`() {
        // A graph of one lane is shown at the minimum width, and can't get narrower
        assertEquals(120f, draggedGraphMaxWidth(maxWidth = 120f, shownWidth = 56f, lanesWidth = 28f, delta = -30f))
        assertEquals(120f, draggedGraphMaxWidth(maxWidth = 120f, shownWidth = 56f, lanesWidth = 28f, delta = 30f))
        // A busy repository's graph already at the minimum
        assertEquals(56f, draggedGraphMaxWidth(maxWidth = 56f, shownWidth = 56f, lanesWidth = 300f, delta = -5f))
    }

    @Test
    fun `a column can widen only as far as the other shown columns leave room`() {
        // 600 - 200 for the message - the others
        assertEquals(210f, all.maxWidthFor(LogColumn.Author, availableWidth = 600f))
        assertEquals(170f, all.maxWidthFor(LogColumn.Date, availableWidth = 600f))
        // The others' dividers and its own
        assertEquals(186f, all.maxWidthFor(LogColumn.Author, availableWidth = 600f, spacing = 8f))
        // Commit doesn't fit at 539, so it takes no room
        assertEquals(229f, all.maxWidthFor(LogColumn.Author, availableWidth = 539f))
        // Never below the minimum or above the maximum
        assertEquals(48f, all.maxWidthFor(LogColumn.Date, availableWidth = 220f))
        assertEquals(600f, LogColumnsSettings().maxWidthFor(LogColumn.Date, availableWidth = 5000f))
    }
}
