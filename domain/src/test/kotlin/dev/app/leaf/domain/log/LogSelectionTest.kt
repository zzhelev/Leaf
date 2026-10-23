// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogSelectionTest {
    private val rows = listOf("e", "d", "c", "b", "a")

    @Test
    fun `no range selects nothing more than the log's commit`() {
        assertEquals(emptyList<String>(), LogSelection().commitsIn(rows))
        assertEquals(emptyList<String>(), LogSelection("c", "c").commitsIn(rows))
    }

    @Test
    fun `a range is the rows between its ends, in the log's order, whichever end is above`() {
        assertEquals(listOf("d", "c", "b"), LogSelection("d", "b").commitsIn(rows))
        assertEquals(listOf("d", "c", "b"), LogSelection("b", "d").commitsIn(rows))
    }

    @Test
    fun `an end the log doesn't have is no range`() {
        assertEquals(emptyList<String>(), LogSelection("d", "z").commitsIn(rows))
        assertEquals(emptyList<String>(), LogSelection("z", "d").commitsIn(rows))
    }

    @Test
    fun `Shift-click ranges from the log's selected commit, then from the same anchor`() {
        val first = LogSelection().extendedTo("b", selected = "d")

        assertEquals(LogSelection("d", "b"), first)
        // The anchor stays, whatever the log's selection says
        assertEquals(LogSelection("d", "e"), first.extendedTo("e", selected = "x"))
    }

    @Test
    fun `Shift-click with nothing selected, or on the anchor, is no range`() {
        assertEquals(LogSelection(), LogSelection().extendedTo("b", selected = null))
        assertEquals(LogSelection(), LogSelection().extendedTo("d", selected = "d"))
        assertEquals(LogSelection(), LogSelection("d", "b").extendedTo("d", selected = "d"))
    }

    @Test
    fun `Shift and the arrows move the lead from the anchor, and back to no range`() {
        val down = LogSelection().stepped(1, rows, selected = "d")

        assertEquals(LogSelection("d", "c"), down)
        assertEquals(LogSelection("d", "b"), down?.stepped(1, rows, selected = "d"))
        assertEquals(LogSelection(), down?.stepped(-1, rows, selected = "d"))
        assertEquals(LogSelection("d", "e"), LogSelection().stepped(-1, rows, selected = "d"))
    }

    @Test
    fun `the arrows stop at the log's ends, and need a start that the log has`() {
        assertNull(LogSelection().stepped(-1, rows, selected = "e"))
        assertNull(LogSelection("c", "a").stepped(1, rows, selected = "c"))
        assertNull(LogSelection().stepped(1, rows, selected = null))
        assertNull(LogSelection().stepped(1, rows, selected = "z"))
        assertNull(LogSelection("z", "d").stepped(1, rows, selected = "z"))
    }

    @Test
    fun `a refresh that loses either end leaves no range`() {
        val range = LogSelection("d", "b")

        assertEquals(range, range.prunedTo(rows))
        assertEquals(LogSelection(), range.prunedTo(listOf("e", "d", "c")))
        assertEquals(LogSelection(), range.prunedTo(listOf("c", "b")))
        assertEquals(LogSelection(), LogSelection().prunedTo(rows))
    }
}
