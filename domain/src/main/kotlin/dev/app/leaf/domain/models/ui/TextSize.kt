// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

/**
 * The size of Leaf's main text, in points, which every other text size follows (fork-only). Settings → Appearance →
 * Text size, and Cmd or Ctrl with `+`, `−` and `0`. It changes the text only, where Scale changes everything.
 */
object TextSize {
    /** The size of the lists' text, which Leaf has always had. */
    const val DEFAULT = 13

    /** What the settings offer, and what the shortcuts step through. */
    val CHOICES = listOf(11, 12, 13, 14, 15, 16, 17, 18, 20)

    /** A stored size that isn't one of the [CHOICES] reads as the default, as a hand-edited file can hold anything. */
    fun of(stored: Int?): Int = if (stored in CHOICES) stored!! else DEFAULT

    /** The next size up, or [size] itself at the largest. */
    fun bigger(size: Int): Int = CHOICES.firstOrNull { it > size } ?: CHOICES.last()

    /** The next size down, or [size] itself at the smallest. */
    fun smaller(size: Int): Int = CHOICES.lastOrNull { it < size } ?: CHOICES.first()

    /** How much bigger than the default every text is, Compose's `fontScale`. */
    fun scaleOf(size: Int): Float = size / DEFAULT.toFloat()
}
