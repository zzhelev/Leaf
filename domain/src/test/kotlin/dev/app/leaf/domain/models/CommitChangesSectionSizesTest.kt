// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

// A pane this tall leaves 500 dp for the files list and the message once the 8 dp divider is taken out.
private const val PANE = 508f

private const val DELTA = 0.001f

class CommitChangesSectionSizesTest {
    private fun assertHeights(files: Float, message: Float, actual: CommitChangesSectionHeights) {
        assertEquals(files, actual.files, DELTA, "files")
        assertEquals(message, actual.message, DELTA, "message")
    }

    @Test
    fun `the default message is 120 dp, as before it could be resized`() {
        assertHeights(380f, 120f, CommitChangesSectionSizes().fitTo(PANE))
    }

    @Test
    fun `a size that fits is kept as asked`() {
        assertHeights(200f, 300f, CommitChangesSectionSizes(messageHeight = 300f).fitTo(PANE))
    }

    @Test
    fun `a shorter pane shrinks the message down to what the files list needs, and a taller one restores it`() {
        val sizes = CommitChangesSectionSizes(messageHeight = 450f)

        assertHeights(100f, 400f, sizes.fitTo(PANE))
        assertHeights(250f, 450f, sizes.fitTo(708f))
    }

    @Test
    fun `the message keeps its minimum`() {
        assertHeights(460f, 40f, CommitChangesSectionSizes(messageHeight = 10f).fitTo(PANE))
    }

    @Test
    fun `in a pane too short for both minimums, the message keeps its own and the files list gets the rest`() {
        val sizes = CommitChangesSectionSizes(messageHeight = 300f)

        assertHeights(60f, 40f, sizes.fitTo(108f))
        assertHeights(0f, 22f, sizes.fitTo(30f))
        assertHeights(0f, 0f, sizes.fitTo(0f))
    }

    @Test
    fun `moving the divider up grows the message, and down shrinks it`() {
        val up = CommitChangesSectionSizes().withDividerMoved(PANE, delta = -100f)
        val down = CommitChangesSectionSizes().withDividerMoved(PANE, delta = 50f)

        assertHeights(280f, 220f, up.fitTo(PANE))
        assertHeights(430f, 70f, down.fitTo(PANE))
        assertEquals(220f, up.messageHeight, DELTA)
    }

    @Test
    fun `the divider stops at the minimums, and moves back at once`() {
        val top = CommitChangesSectionSizes().withDividerMoved(PANE, delta = -1000f)
        val bottom = CommitChangesSectionSizes().withDividerMoved(PANE, delta = 1000f)

        assertHeights(100f, 400f, top.fitTo(PANE))
        assertHeights(460f, 40f, bottom.fitTo(PANE))
        assertHeights(110f, 390f, top.withDividerMoved(PANE, delta = 10f).fitTo(PANE))
        assertHeights(450f, 50f, bottom.withDividerMoved(PANE, delta = -10f).fitTo(PANE))
    }

    @Test
    fun `the divider moves back at once from a message the window had shrunk`() {
        // Asked for 450 dp, but the pane leaves room for 400.
        val moved = CommitChangesSectionSizes(messageHeight = 450f).withDividerMoved(PANE, delta = 10f)

        assertEquals(390f, moved.messageHeight, DELTA)
    }

    @Test
    fun `the divider doesn't move in a pane too short for the minimums`() {
        val sizes = CommitChangesSectionSizes(messageHeight = 250f)

        assertSame(sizes, sizes.withDividerMoved(140f, delta = 20f))
    }

    @Test
    fun `damaged values fall back to the default`() {
        assertEquals(CommitChangesSectionSizes(), CommitChangesSectionSizes(Float.NaN).orDefaults())
        assertEquals(CommitChangesSectionSizes(), CommitChangesSectionSizes(Float.POSITIVE_INFINITY).orDefaults())
        assertEquals(CommitChangesSectionSizes(), CommitChangesSectionSizes(0f).orDefaults())
        assertEquals(CommitChangesSectionSizes(), CommitChangesSectionSizes(-5f).orDefaults())
        assertEquals(CommitChangesSectionSizes(250f), CommitChangesSectionSizes(250f).orDefaults())
    }
}
