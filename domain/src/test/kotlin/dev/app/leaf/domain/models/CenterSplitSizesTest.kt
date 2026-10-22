// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private const val DELTA = 0.001f

// 808 dp leaves 800 for the log and the diff once the 8 dp divider is taken out
private const val PANE = 808f

class CenterSplitSizesTest {
    @Test
    fun `the default gives the log 40 percent of what the divider leaves`() {
        val heights = CenterSplitSizes().fitTo(PANE)!!

        assertEquals(320f, heights.log, DELTA)
        assertEquals(480f, heights.diff, DELTA)
    }

    @Test
    fun `the heights and the divider add up to the pane`() {
        val random = Random(11)

        repeat(5_000) {
            val height = 318f + random.nextFloat() * 2_000f
            val sizes = CenterSplitSizes(logShare = random.nextFloat())
            val heights = sizes.fitTo(height)!!

            assertEquals(height, heights.log + heights.diff + SECTION_DIVIDER_HEIGHT, DELTA, "$sizes in $height")
            assertTrue(heights.log >= CENTER_MIN_LOG_HEIGHT - DELTA, "log in $height with $sizes: ${heights.log}")
            assertTrue(heights.diff >= CENTER_MIN_DIFF_HEIGHT - DELTA, "diff in $height with $sizes: ${heights.diff}")
        }
    }

    @Test
    fun `a share that would leave a section too small is held at the minimum`() {
        val tiny = CenterSplitSizes(logShare = 0.01f).fitTo(PANE)!!
        val huge = CenterSplitSizes(logShare = 0.99f).fitTo(PANE)!!

        assertEquals(CENTER_MIN_LOG_HEIGHT, tiny.log, DELTA)
        assertEquals(CENTER_MIN_DIFF_HEIGHT, huge.diff, DELTA)
    }

    @Test
    fun `a shorter window shrinks both and a taller one brings the share back`() {
        val sizes = CenterSplitSizes(logShare = 0.5f)

        assertEquals(400f, sizes.fitTo(800f + SECTION_DIVIDER_HEIGHT)!!.log, DELTA)
        assertEquals(200f, sizes.fitTo(400f + SECTION_DIVIDER_HEIGHT)!!.log, DELTA)
        assertEquals(500f, sizes.fitTo(1000f + SECTION_DIVIDER_HEIGHT)!!.log, DELTA)
    }

    @Test
    fun `a pane too short for both minimums is not split`() {
        val smallest = CENTER_MIN_LOG_HEIGHT + CENTER_MIN_DIFF_HEIGHT + SECTION_DIVIDER_HEIGHT

        assertNotNull(CenterSplitSizes().fitTo(smallest))
        assertNull(CenterSplitSizes().fitTo(smallest - 1f))
        assertNull(CenterSplitSizes().fitTo(0f))
        assertNull(CenterSplitSizes().fitTo(-50f))
        assertNull(CenterSplitSizes().fitTo(Float.NaN))
    }

    @Test
    fun `a pane just big enough gives each section its minimum`() {
        val heights = CenterSplitSizes(logShare = 0.9f).fitTo(CENTER_MIN_LOG_HEIGHT + CENTER_MIN_DIFF_HEIGHT + SECTION_DIVIDER_HEIGHT)!!

        assertEquals(CENTER_MIN_LOG_HEIGHT, heights.log, DELTA)
        assertEquals(CENTER_MIN_DIFF_HEIGHT, heights.diff, DELTA)
    }

    @Test
    fun `dragging the divider moves it by the distance and keeps the sections above their minimums`() {
        val sizes = CenterSplitSizes()
        val moved = sizes.withDividerMoved(PANE, 100f)

        assertEquals(420f, moved.fitTo(PANE)!!.log, DELTA)
        assertEquals(220f, sizes.withDividerMoved(PANE, -100f).fitTo(PANE)!!.log, DELTA)
        assertEquals(CENTER_MIN_LOG_HEIGHT, sizes.withDividerMoved(PANE, -10_000f).fitTo(PANE)!!.log, DELTA)
        assertEquals(CENTER_MIN_DIFF_HEIGHT, sizes.withDividerMoved(PANE, 10_000f).fitTo(PANE)!!.diff, DELTA)
    }

    @Test
    fun `a drag in a pane too short to split changes nothing`() {
        val sizes = CenterSplitSizes(logShare = 0.3f)

        assertSame(sizes, sizes.withDividerMoved(300f, 50f))
    }

    @Test
    fun `a drag starts from the fitted heights, so a share past the minimum doesn't make the divider jump`() {
        // The share asks for 5% of 800 dp, but the log is held at 150 dp. Dragging down 10 dp gives 160, not 50.
        val sizes = CenterSplitSizes(logShare = 0.05f)

        assertEquals(160f, sizes.withDividerMoved(PANE, 10f).fitTo(PANE)!!.log, DELTA)
    }

    @Test
    fun `a saved share is a share, so it fits another window height`() {
        val dragged = CenterSplitSizes().withDividerMoved(PANE, 80f)

        assertEquals(0.5f, dragged.logShare, DELTA)
        assertEquals(0.5f * 1_192f, dragged.fitTo(1_200f)!!.log, DELTA)
    }

    @Test
    fun `random drags never leave a section under its minimum`() {
        val random = Random(5)
        var sizes = CenterSplitSizes()

        repeat(5_000) {
            val height = 318f + random.nextFloat() * 1_500f
            sizes = sizes.withDividerMoved(height, (random.nextFloat() - 0.5f) * 1_000f)
            val heights = sizes.fitTo(height)!!

            assertTrue(heights.log >= CENTER_MIN_LOG_HEIGHT - DELTA && heights.diff >= CENTER_MIN_DIFF_HEIGHT - DELTA, "$heights in $height")
            assertTrue(sizes.logShare in 0f..1f, "${sizes.logShare}")
        }
    }

    @Test
    fun `damaged values are replaced by the default`() {
        assertEquals(CenterSplitSizes(), CenterSplitSizes(Float.NaN).orDefaults())
        assertEquals(CenterSplitSizes(), CenterSplitSizes(-0.1f).orDefaults())
        assertEquals(CenterSplitSizes(), CenterSplitSizes(1.5f).orDefaults())
        assertEquals(CenterSplitSizes(), CenterSplitSizes(Float.POSITIVE_INFINITY).orDefaults())
        assertEquals(CenterSplitSizes(0.7f), CenterSplitSizes(0.7f).orDefaults())
    }
}
