// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.log.SearchStep.NEXT
import dev.app.leaf.domain.log.SearchStep.PREVIOUS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchNavigationTest {
    @Test
    fun `next and previous move one result in the middle`() {
        assertEquals(4, steppedSearchIndex(current = 3, total = 12, step = NEXT))
        assertEquals(2, steppedSearchIndex(current = 3, total = 12, step = PREVIOUS))
    }

    @Test
    fun `next from the last result wraps to the first`() {
        assertEquals(1, steppedSearchIndex(current = 12, total = 12, step = NEXT))
    }

    @Test
    fun `previous from the first result wraps to the last`() {
        assertEquals(12, steppedSearchIndex(current = 1, total = 12, step = PREVIOUS))
    }

    @Test
    fun `the results next to the ends don't wrap`() {
        assertEquals(12, steppedSearchIndex(current = 11, total = 12, step = NEXT))
        assertEquals(1, steppedSearchIndex(current = 2, total = 12, step = PREVIOUS))
    }

    @Test
    fun `two results swap in both directions`() {
        assertEquals(2, steppedSearchIndex(current = 1, total = 2, step = NEXT))
        assertEquals(1, steppedSearchIndex(current = 2, total = 2, step = NEXT))
        assertEquals(2, steppedSearchIndex(current = 1, total = 2, step = PREVIOUS))
        assertEquals(1, steppedSearchIndex(current = 2, total = 2, step = PREVIOUS))
    }

    @Test
    fun `one result stays where it is`() {
        assertEquals(1, steppedSearchIndex(current = 1, total = 1, step = NEXT))
        assertEquals(1, steppedSearchIndex(current = 1, total = 1, step = PREVIOUS))
    }

    @Test
    fun `no results is index 0 whatever the step and the index`() {
        for (step in SearchStep.entries) {
            for (current in listOf(-3, 0, 1, 5)) {
                assertEquals(0, steppedSearchIndex(current, total = 0, step = step), "$step from $current")
            }
        }
        assertEquals(0, steppedSearchIndex(current = 2, total = -1, step = NEXT))
    }

    @Test
    fun `an index before the first result steps in from the start`() {
        assertEquals(1, steppedSearchIndex(current = 0, total = 5, step = NEXT))
        assertEquals(5, steppedSearchIndex(current = 0, total = 5, step = PREVIOUS))
        assertEquals(1, steppedSearchIndex(current = -2, total = 5, step = NEXT))
        assertEquals(5, steppedSearchIndex(current = -2, total = 5, step = PREVIOUS))
    }

    @Test
    fun `an index past the last result, as when the list shrank, steps in from the end`() {
        assertEquals(1, steppedSearchIndex(current = 9, total = 3, step = NEXT))
        assertEquals(3, steppedSearchIndex(current = 9, total = 3, step = PREVIOUS))
    }

    @Test
    fun `a full lap of next steps visits every result once and comes back`() {
        for (total in 1..8) {
            for (start in 1..total) {
                val visited = mutableListOf<Int>()
                var index = start

                repeat(total) {
                    index = steppedSearchIndex(index, total, NEXT)
                    visited += index
                }

                assertEquals(start, index, "total $total from $start")
                assertEquals((1..total).toSet(), visited.toSet(), "total $total from $start")
            }
        }
    }

    @Test
    fun `a full lap of previous steps visits every result once and comes back`() {
        for (total in 1..8) {
            for (start in 1..total) {
                val visited = mutableListOf<Int>()
                var index = start

                repeat(total) {
                    index = steppedSearchIndex(index, total, PREVIOUS)
                    visited += index
                }

                assertEquals(start, index, "total $total from $start")
                assertEquals((1..total).toSet(), visited.toSet(), "total $total from $start")
            }
        }
    }

    @Test
    fun `next then previous is the identity for every index in range`() {
        for (total in 1..8) {
            for (index in 1..total) {
                val forward = steppedSearchIndex(index, total, NEXT)
                val backward = steppedSearchIndex(index, total, PREVIOUS)

                assertEquals(index, steppedSearchIndex(forward, total, PREVIOUS), "total $total, $index")
                assertEquals(index, steppedSearchIndex(backward, total, NEXT), "total $total, $index")
            }
        }
    }

    @Test
    fun `a step always lands on a result when there are any, whatever the index`() {
        for (total in 1..6) {
            for (current in -2..total + 3) {
                for (step in SearchStep.entries) {
                    val stepped = steppedSearchIndex(current, total, step)

                    assertTrue(stepped in 1..total, "$step from $current of $total gave $stepped")
                }
            }
        }
    }
}
