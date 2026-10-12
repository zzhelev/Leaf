// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Columns layout's widths as the gutters move them, and the file list's width. */
class ResolverSizesTest {
    private val even = ColumnShares()

    @Test
    fun `a gutter moves the edge between its two columns, and theirs has what's left`() {
        val wider = even.movingOursGutter(0.1f, min = 0.1f)

        assertShares(0.4333f, 0.2333f, 0.3333f, wider)

        val narrower = even.movingTheirsGutter(-0.1f, min = 0.1f)

        assertShares(0.3333f, 0.2333f, 0.4333f, narrower)
        assertShares(0.3333f, 0.3333f, 0.3333f, even)
    }

    @Test
    fun `no column goes below the minimum`() {
        assertShares(0.5667f, 0.1f, 0.3333f, even.movingOursGutter(0.5f, min = 0.1f))
        assertShares(0.1f, 0.5667f, 0.3333f, even.movingOursGutter(-0.5f, min = 0.1f))
        assertShares(0.3333f, 0.5667f, 0.1f, even.movingTheirsGutter(0.5f, min = 0.1f))
        assertShares(0.3333f, 0.1f, 0.5667f, even.movingTheirsGutter(-0.5f, min = 0.1f))
    }

    @Test
    fun `a column already below the minimum, as in a narrower window, doesn't shrink, and can grow`() {
        // The minimum went up to 0.3 when the window got narrower
        val narrow = ColumnShares(ours = 0.2f, result = 0.5f)

        assertShares(0.2f, 0.5f, 0.3f, narrow.movingOursGutter(-0.05f, min = 0.3f))
        assertShares(0.3f, 0.4f, 0.3f, narrow.movingOursGutter(0.1f, min = 0.3f))
        // Theirs is at the minimum: the result can't take from it
        assertShares(0.2f, 0.5f, 0.3f, narrow.movingTheirsGutter(0.1f, min = 0.3f))
        // Nor can a gutter move when neither column has room
        assertShares(0.2f, 0.5f, 0.3f, ColumnShares(0.2f, 0.5f).movingOursGutter(0.1f, min = 0.6f))
    }

    @Test
    fun `the file list stays between its smallest and largest width`() {
        assertEquals(280f, fileListWidthAfterDrag(DEFAULT_FILE_LIST_WIDTH, 60f))
        assertEquals(MIN_FILE_LIST_WIDTH, fileListWidthAfterDrag(DEFAULT_FILE_LIST_WIDTH, -200f))
        assertEquals(MAX_FILE_LIST_WIDTH, fileListWidthAfterDrag(DEFAULT_FILE_LIST_WIDTH, 900f))
    }

    private fun assertShares(ours: Float, result: Float, theirs: Float, shares: ColumnShares) {
        assertEquals(ours, shares.ours, 0.0005f, "ours of $shares")
        assertEquals(result, shares.result, 0.0005f, "result of $shares")
        assertEquals(theirs, shares.theirs, 0.0005f, "theirs of $shares")
    }
}
