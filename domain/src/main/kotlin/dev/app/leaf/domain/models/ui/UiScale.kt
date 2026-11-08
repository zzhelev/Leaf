// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

import kotlin.math.roundToInt

/**
 * Settings → Appearance → Scale (fork-only since 2026-11-08): how much bigger or smaller than the screen's own scale
 * Leaf draws everything, text, icons and spacing. 100% is the screen's own scale on every screen: 2 points a pixel on a
 * Mac's Retina screen, the display's scaling on Windows. Until 2026-11-08 the setting was the scale itself
 * (`scale_ui`), so a scale picked on one screen was wrong on another.
 */
object UiScale {
    const val DEFAULT = 1f

    /** What the settings offer. A stored scale that isn't one of them is offered too. */
    val CHOICES = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f)

    /** The smallest and largest scale that a stored value can be, as a hand-edited file can hold anything. */
    private const val MIN = 0.25f
    private const val MAX = 4f

    /** A stored scale, or the default for none or one that can't be right. */
    fun of(stored: Float?): Float = if (stored != null && stored in MIN..MAX) stored else DEFAULT

    /** Compose's density for a screen whose own scale is [screenScale]. */
    fun densityOf(scale: Float, screenScale: Float): Float = screenScale * scale

    /**
     * A scale stored before 2026-11-08, which was the density itself, as a scale on top of [screenScale]: the size that
     * it gave on that screen, to the whole percent. Null when either can't be right.
     */
    fun fromAbsolute(absolute: Float, screenScale: Float): Float? {
        if (absolute.isNaN() || absolute <= 0f || screenScale.isNaN() || screenScale <= 0f) return null

        val scale = (absolute / screenScale * 100).roundToInt() / 100f
        return scale.takeIf { it in MIN..MAX }
    }
}
