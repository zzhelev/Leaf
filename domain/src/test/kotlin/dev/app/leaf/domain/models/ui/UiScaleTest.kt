// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UiScaleTest {
    @Test
    fun `the default is the screen's own scale, on any screen`() {
        for (screen in listOf(1f, 1.25f, 1.5f, 2f, 3f)) {
            assertEquals(screen, UiScale.densityOf(UiScale.DEFAULT, screen), "$screen")
        }
    }

    @Test
    fun `a scale is on top of the screen's`() {
        assertEquals(1.5f, UiScale.densityOf(1.5f, 1f))
        assertEquals(3f, UiScale.densityOf(1.5f, 2f))
        assertEquals(0.75f * 1.25f, UiScale.densityOf(0.75f, 1.25f))
    }

    @Test
    fun `no stored scale, or one that can't be right, reads as the default`() {
        assertEquals(1f, UiScale.of(null))
        for (stored in listOf(0f, -1f, 0.1f, 5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(UiScale.DEFAULT, UiScale.of(stored), "$stored")
        }
        for (stored in UiScale.CHOICES + 0.83f) {
            assertEquals(stored, UiScale.of(stored), "$stored")
        }
    }

    @Test
    fun `the choices go from 75 to 300 percent, with the default among them`() {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f), UiScale.CHOICES)
    }

    @Test
    fun `an old scale keeps the size that it gave on the screen that Leaf starts on`() {
        // (stored, screen, now)
        val cases = listOf(
            Triple(2f, 2f, 1f), // 200% picked on a Retina screen: the screen's own
            Triple(1f, 1f, 1f), // 100% picked on a 1x monitor, and started there
            Triple(1f, 2f, 0.5f), // the same, started on the Retina screen: as small as it was
            Triple(1.5f, 1f, 1.5f),
            Triple(3f, 2f, 1.5f),
            Triple(1.25f, 1.5f, 0.83f), // Windows at 150%, to the whole percent
            Triple(1.75f, 1.25f, 1.4f),
        )

        for ((stored, screen, now) in cases) {
            assertEquals(now, UiScale.fromAbsolute(stored, screen), "$stored on $screen")
            if (now == UiScale.fromAbsolute(stored, screen)) {
                assertEquals(stored, UiScale.densityOf(now, screen), 0.01f, "$stored on $screen")
            }
        }
    }

    @Test
    fun `an old scale or a screen's that can't be right gives none`() {
        for ((stored, screen) in listOf(0f to 2f, -1f to 1f, Float.NaN to 1f, 1f to 0f, 1f to Float.NaN, 0.1f to 2f, 9f to 1f)) {
            assertNull(UiScale.fromAbsolute(stored, screen), "$stored on $screen")
        }
    }
}
