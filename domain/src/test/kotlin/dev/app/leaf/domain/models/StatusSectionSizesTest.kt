// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

// A pane this tall leaves 800 dp for the sections once the two 8 dp dividers are taken out.
private const val PANE = 816f

private const val DELTA = 0.001f

class StatusSectionSizesTest {
    private fun assertHeights(staged: Float, unstaged: Float, commitField: Float, actual: StatusSectionHeights) {
        assertEquals(staged, actual.staged, DELTA, "staged")
        assertEquals(unstaged, actual.unstaged, DELTA, "unstaged")
        assertEquals(commitField, actual.commitField, DELTA, "commit field")
    }

    @Test
    fun `the defaults split the lists evenly above a 192 dp commit field`() {
        assertHeights(304f, 304f, 192f, StatusSectionSizes().fitTo(PANE))
    }

    @Test
    fun `sizes that fit are kept as asked`() {
        val sizes = StatusSectionSizes(stagedShare = 0.25f, commitFieldHeight = 300f)

        assertHeights(125f, 375f, 300f, sizes.fitTo(PANE))
    }

    @Test
    fun `a shorter pane shrinks the commit field down to what the lists need, and a taller one restores it`() {
        val sizes = StatusSectionSizes(commitFieldHeight = 500f)

        assertHeights(100f, 100f, 400f, sizes.fitTo(616f))
        assertHeights(250f, 250f, 500f, sizes.fitTo(1016f))
    }

    @Test
    fun `each list keeps its minimum`() {
        assertHeights(100f, 508f, 192f, StatusSectionSizes(stagedShare = 0.05f).fitTo(PANE))
        assertHeights(508f, 100f, 192f, StatusSectionSizes(stagedShare = 0.95f).fitTo(PANE))
    }

    @Test
    fun `in a pane too short for every minimum, the commit field keeps its own and the lists split the rest`() {
        val sizes = StatusSectionSizes(stagedShare = 0.9f, commitFieldHeight = 300f)

        assertHeights(80f, 80f, 140f, sizes.fitTo(316f))
        assertHeights(0f, 0f, 84f, sizes.fitTo(100f))
        assertHeights(0f, 0f, 0f, sizes.fitTo(0f))
    }

    @Test
    fun `moving the lists divider resizes the two lists`() {
        val stagedOnTop = StatusSectionSizes().withListsDividerMoved(PANE, delta = 50f, stagedOnTop = true)
        val stagedBelow = StatusSectionSizes().withListsDividerMoved(PANE, delta = 50f, stagedOnTop = false)

        assertHeights(354f, 254f, 192f, stagedOnTop.fitTo(PANE))
        assertHeights(254f, 354f, 192f, stagedBelow.fitTo(PANE))
    }

    @Test
    fun `the lists divider stops at the minimums, and moves back at once`() {
        val atBottom = StatusSectionSizes().withListsDividerMoved(PANE, delta = 1000f, stagedOnTop = true)
        val back = atBottom.withListsDividerMoved(PANE, delta = -10f, stagedOnTop = true)

        assertHeights(508f, 100f, 192f, atBottom.fitTo(PANE))
        assertHeights(498f, 110f, 192f, back.fitTo(PANE))
    }

    @Test
    fun `moving the commit divider keeps the top list's height`() {
        val stagedOnTop = StatusSectionSizes().withCommitDividerMoved(PANE, delta = -100f, stagedOnTop = true)
        val stagedBelow = StatusSectionSizes().withCommitDividerMoved(PANE, delta = -100f, stagedOnTop = false)

        assertHeights(304f, 204f, 292f, stagedOnTop.fitTo(PANE))
        assertHeights(204f, 304f, 292f, stagedBelow.fitTo(PANE))
        assertEquals(292f, stagedOnTop.commitFieldHeight, DELTA)
    }

    @Test
    fun `once the bottom list is at its minimum, the top list gives way to the commit field`() {
        // Unstaged is 304 dp; growing the commit field by 250 would leave it 54.
        val sizes = StatusSectionSizes().withCommitDividerMoved(PANE, delta = -250f, stagedOnTop = true)

        assertHeights(258f, 100f, 442f, sizes.fitTo(PANE))
    }

    @Test
    fun `the commit divider stops at the minimums`() {
        val up = StatusSectionSizes().withCommitDividerMoved(PANE, delta = -1000f, stagedOnTop = true)
        val down = StatusSectionSizes().withCommitDividerMoved(PANE, delta = 1000f, stagedOnTop = true)

        assertHeights(100f, 100f, 600f, up.fitTo(PANE))
        assertHeights(304f, 356f, 140f, down.fitTo(PANE))
    }

    @Test
    fun `the commit divider moves back at once from a commit field the window had shrunk`() {
        // Asked for 500 dp, but a 616 dp pane leaves room for 400.
        val sizes = StatusSectionSizes(commitFieldHeight = 500f)
        val moved = sizes.withCommitDividerMoved(616f, delta = 10f, stagedOnTop = true)

        assertEquals(390f, moved.commitFieldHeight, DELTA)
    }

    @Test
    fun `dividers don't move in a pane too short for the minimums`() {
        val sizes = StatusSectionSizes(stagedShare = 0.3f, commitFieldHeight = 250f)

        assertSame(sizes, sizes.withListsDividerMoved(316f, delta = 20f, stagedOnTop = true))
        assertSame(sizes, sizes.withCommitDividerMoved(316f, delta = 20f, stagedOnTop = true))
    }

    @Test
    fun `damaged values fall back to their defaults`() {
        assertEquals(StatusSectionSizes(), StatusSectionSizes(Float.NaN, Float.NaN).orDefaults())
        assertEquals(StatusSectionSizes(), StatusSectionSizes(-0.1f, Float.POSITIVE_INFINITY).orDefaults())
        assertEquals(StatusSectionSizes(), StatusSectionSizes(1.5f, 0f).orDefaults())
        assertEquals(StatusSectionSizes(0.2f, 250f), StatusSectionSizes(0.2f, 250f).orDefaults())
    }
}
