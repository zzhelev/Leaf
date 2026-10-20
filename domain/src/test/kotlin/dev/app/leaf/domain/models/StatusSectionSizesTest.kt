// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// A pane this tall leaves 800 dp for the sections once the two 8 dp dividers and the 34 dp title row are taken out.
private const val PANE = 850f

private const val DELTA = 0.001f

class StatusSectionSizesTest {
    private fun assertHeights(
        staged: Float,
        unstaged: Float,
        commitField: Float,
        actual: StatusSectionHeights,
        commitTitle: Float = STATUS_COMMIT_TITLE_HEIGHT,
    ) {
        assertEquals(staged, actual.staged, DELTA, "staged")
        assertEquals(unstaged, actual.unstaged, DELTA, "unstaged")
        assertEquals(commitField, actual.commitField, DELTA, "commit field")
        assertEquals(commitTitle, actual.commitTitle, DELTA, "commit title")
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

        assertHeights(100f, 100f, 400f, sizes.fitTo(650f))
        assertHeights(250f, 250f, 500f, sizes.fitTo(1050f))
    }

    @Test
    fun `each list keeps its minimum`() {
        assertHeights(100f, 508f, 192f, StatusSectionSizes(stagedShare = 0.05f).fitTo(PANE))
        assertHeights(508f, 100f, 192f, StatusSectionSizes(stagedShare = 0.95f).fitTo(PANE))
    }

    @Test
    fun `in a pane too short for every minimum, the commit field keeps its own and the lists split the rest`() {
        val sizes = StatusSectionSizes(stagedShare = 0.9f, commitFieldHeight = 300f)

        assertHeights(80f, 80f, 140f, sizes.fitTo(350f))
        // The title row comes first, then what is left goes to the commit field
        assertHeights(0f, 0f, 50f, sizes.fitTo(100f))
        // No room for it either, or for the dividers
        assertHeights(0f, 0f, 0f, sizes.fitTo(16f), commitTitle = 0f)
        assertHeights(0f, 0f, 0f, sizes.fitTo(0f), commitTitle = 0f)
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
        // Asked for 500 dp, but a 650 dp pane leaves room for 400.
        val sizes = StatusSectionSizes(commitFieldHeight = 500f)
        val moved = sizes.withCommitDividerMoved(650f, delta = 10f, stagedOnTop = true)

        assertEquals(390f, moved.commitFieldHeight, DELTA)
    }

    @Test
    fun `dividers don't move in a pane too short for the minimums`() {
        val sizes = StatusSectionSizes(stagedShare = 0.3f, commitFieldHeight = 250f)

        assertSame(sizes, sizes.withListsDividerMoved(350f, delta = 20f, stagedOnTop = true))
        assertSame(sizes, sizes.withCommitDividerMoved(350f, delta = 20f, stagedOnTop = true))
    }

    @Test
    fun `the title row comes on top of the saved commit field height, so a saved height gives the same field`() {
        // What counted as the commit field before the title row was the message, its hint and the buttons: still is
        for (saved in listOf(140f, 192f, 250f, 400f)) {
            val heights = StatusSectionSizes(commitFieldHeight = saved).fitTo(PANE)

            assertEquals(saved, heights.commitField, DELTA, "saved $saved")
            assertEquals(STATUS_COMMIT_TITLE_HEIGHT, heights.commitTitle, DELTA)
        }
    }

    @Test
    fun `a pane 34 dp taller than before gives the sections of before, and the same pane gives the lists 34 dp less`() {
        val before = 816f
        val sizes = StatusSectionSizes()

        assertHeights(304f, 304f, 192f, sizes.fitTo(before + STATUS_COMMIT_TITLE_HEIGHT))

        val same = sizes.fitTo(before)

        assertHeights(287f, 287f, 192f, same)
    }

    @Test
    fun `the sections add up to the pane, whatever the pane and the sizes`() {
        val random = java.util.Random(344)

        repeat(5_000) {
            val pane = random.nextFloat() * 1_400f + 16f
            val sizes = StatusSectionSizes(random.nextFloat(), random.nextFloat() * 900f + 1f)
            val delta = random.nextFloat() * 600f - 300f
            val stagedOnTop = random.nextBoolean()

            for (candidate in listOf(
                sizes,
                sizes.withListsDividerMoved(pane, delta, stagedOnTop),
                sizes.withCommitDividerMoved(pane, delta, stagedOnTop),
            )) {
                val heights = candidate.fitTo(pane)
                val total = heights.staged + heights.unstaged + heights.commitSection + 2 * SECTION_DIVIDER_HEIGHT

                assertTrue(heights.staged >= -DELTA && heights.unstaged >= -DELTA && heights.commitField >= -DELTA)
                // A pane too short for the dividers is the one case that overflows, as it always did
                assertEquals(pane, total, DELTA * 10, "pane $pane, sizes $candidate")
            }
        }
    }

    @Test
    fun `a pane that fits the minimums keeps them, the title row included`() {
        val random = java.util.Random(34)
        val minimum = 2 * STATUS_MIN_LIST_HEIGHT + STATUS_MIN_COMMIT_FIELD_HEIGHT + STATUS_COMMIT_TITLE_HEIGHT +
            2 * SECTION_DIVIDER_HEIGHT

        repeat(2_000) {
            val pane = minimum + random.nextFloat() * 1_000f
            val sizes = StatusSectionSizes(random.nextFloat(), random.nextFloat() * 1_500f + 1f)
            val moved = sizes.withCommitDividerMoved(pane, random.nextFloat() * 800f - 400f, random.nextBoolean())
            val heights = moved.fitTo(pane)

            assertTrue(heights.staged >= STATUS_MIN_LIST_HEIGHT - DELTA, "staged ${heights.staged} in $pane")
            assertTrue(heights.unstaged >= STATUS_MIN_LIST_HEIGHT - DELTA, "unstaged ${heights.unstaged} in $pane")
            assertTrue(heights.commitField >= STATUS_MIN_COMMIT_FIELD_HEIGHT - DELTA, "field ${heights.commitField} in $pane")
        }
    }

    @Test
    fun `a saved height too big for the window shrinks to what the lists leave, and never past the title row`() {
        // Saved 900 dp, in a 650 dp pane: 650 - 16 - 34 = 600 for the lists and the field, the lists keep 200 of it
        val heights = StatusSectionSizes(commitFieldHeight = 900f).fitTo(650f)

        assertHeights(100f, 100f, 400f, heights)
        assertEquals(650f, heights.staged + heights.unstaged + heights.commitSection + 16f, DELTA)
        assertEquals(434f, heights.commitSection, DELTA, "The title row and the field")
    }

    @Test
    fun `damaged values fall back to their defaults`() {
        assertEquals(StatusSectionSizes(), StatusSectionSizes(Float.NaN, Float.NaN).orDefaults())
        assertEquals(StatusSectionSizes(), StatusSectionSizes(-0.1f, Float.POSITIVE_INFINITY).orDefaults())
        assertEquals(StatusSectionSizes(), StatusSectionSizes(1.5f, 0f).orDefaults())
        assertEquals(StatusSectionSizes(0.2f, 250f), StatusSectionSizes(0.2f, 250f).orDefaults())
    }
}
