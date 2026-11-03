// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.menu

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val WINDOW = IntSize(1280, 692)
private const val MARGIN = 8

/** Where a menu opens, and its scrollbar's thumb (fork-only). */
class MenuFitTest {
    @Test
    fun `a menu that fits opens at the pointer`() {
        assertEquals(IntOffset(340, 259), menuPositionAt(IntOffset(340, 259), WINDOW, IntSize(300, 400), MARGIN))
    }

    @Test
    fun `a menu that doesn't fit below moves up just enough to end the margin before the window's bottom`() {
        assertEquals(684 - 596, menuPositionAt(IntOffset(340, 259), WINDOW, IntSize(300, 596), MARGIN).y)
        assertEquals(684 - 120, menuPositionAt(IntOffset(340, 650), WINDOW, IntSize(300, 120), MARGIN).y)
    }

    @Test
    fun `a menu as tall as the window less its margins starts at the top margin`() {
        // fitsWindowHeight cut it to 692 - 2 * 8
        assertEquals(MARGIN, menuPositionAt(IntOffset(340, 259), WINDOW, IntSize(300, 676), MARGIN).y)
    }

    @Test
    fun `a menu that doesn't fit to the right moves left to end the margin before the window's edge`() {
        assertEquals(1272 - 300, menuPositionAt(IntOffset(1100, 100), WINDOW, IntSize(300, 200), MARGIN).x)
    }

    @Test
    fun `a pointer closer to the window's start than the margin opens the menu at the margin`() {
        assertEquals(IntOffset(MARGIN, MARGIN), menuPositionAt(IntOffset(2, 3), WINDOW, IntSize(300, 200), MARGIN))
    }

    @Test
    fun `a menu wider than the window less its margins starts at its edge rather than off it`() {
        assertEquals(0, fittedStart(preferred = 100, size = 1285, extent = 1280, margin = MARGIN))
        assertEquals(2, fittedStart(preferred = 100, size = 1270, extent = 1280, margin = MARGIN))
    }

    @Test
    fun `a header menu opens below its button, or above it when there's no room below`() {
        assertEquals(40, menuTopBelowOrAbove(below = 40, aboveEnd = 10, height = 300, windowHeight = 692, margin = MARGIN))
        assertEquals(600 - 300, menuTopBelowOrAbove(below = 640, aboveEnd = 600, height = 300, windowHeight = 692, margin = MARGIN))
    }

    @Test
    fun `a header menu that fits neither below nor above its button moves up to fit`() {
        assertEquals(684 - 500, menuTopBelowOrAbove(below = 300, aboveEnd = 260, height = 500, windowHeight = 692, margin = MARGIN))
        assertEquals(MARGIN, menuTopBelowOrAbove(below = 300, aboveEnd = 260, height = 676, windowHeight = 692, margin = MARGIN))
    }

    @Test
    fun `the thumb is as long as the share of the entries in view, and moves with the scroll`() {
        // 672 px in view out of 880: the thumb is 672 / 880 of the track
        val top = scrollbarThumb(viewport = 672f, maxScroll = 208f, scroll = 0f, inset = 3f, minLength = 24f)
        val bottom = scrollbarThumb(viewport = 672f, maxScroll = 208f, scroll = 208f, inset = 3f, minLength = 24f)

        assertEquals(3f, top.top)
        assertEquals(666f * 672f / 880f, top.length, 0.01f)
        assertEquals(672f - 3f, bottom.top + bottom.length, 0.01f)
    }

    @Test
    fun `a long list's thumb keeps its least length`() {
        val thumb = scrollbarThumb(viewport = 200f, maxScroll = 20_000f, scroll = 10_000f, inset = 3f, minLength = 24f)

        assertEquals(24f, thumb.length)
        assertEquals(3f + (194f - 24f) / 2, thumb.top, 0.01f)
    }
}
