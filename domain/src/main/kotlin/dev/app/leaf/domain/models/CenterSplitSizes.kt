// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** The log's default share of the centre pane's height while a diff is open under it. */
const val CENTER_DEFAULT_LOG_SHARE = 0.4f

/** The smallest log above a diff, in dp: its header and four rows. */
const val CENTER_MIN_LOG_HEIGHT = 150f

/** The smallest diff under the log, in dp: its header and some lines of the file. */
const val CENTER_MIN_DIFF_HEIGHT = 160f

/**
 * How the centre pane splits between the log and the diff under it. The share is kept as the user asked, and [fitTo] fits
 * it to the pane's height, so a shorter window shrinks the two and a taller one brings them back.
 */
data class CenterSplitSizes(
    /** The log's share of the height the two split, from 0 to 1. */
    val logShare: Float = CENTER_DEFAULT_LOG_SHARE,
) {
    /** These sizes, with a damaged or impossible value replaced by its default. */
    fun orDefaults() = CenterSplitSizes(
        logShare = logShare.takeIf { it.isFinite() && it in 0f..1f } ?: CENTER_DEFAULT_LOG_SHARE,
    )

    /**
     * The log's and the diff's heights in a pane [height] dp tall, the divider between them left out, or null when the
     * pane is too short for both at their minimums: then the diff takes the whole pane, as it did before the log stayed.
     */
    fun fitTo(height: Float): CenterSplitHeights? {
        val available = height - SECTION_DIVIDER_HEIGHT

        // Also false for NaN
        if (!(available >= CENTER_MIN_LOG_HEIGHT + CENTER_MIN_DIFF_HEIGHT)) return null

        val log = (available * logShare).coerceIn(CENTER_MIN_LOG_HEIGHT, available - CENTER_MIN_DIFF_HEIGHT)

        return CenterSplitHeights(log = log, diff = available - log)
    }

    /** The sizes after dragging the divider [delta] dp down, in a pane [height] dp tall. A pane too short to split keeps them. */
    fun withDividerMoved(height: Float, delta: Float): CenterSplitSizes {
        val heights = fitTo(height) ?: return this
        val available = heights.log + heights.diff
        val log = (heights.log + delta).coerceIn(CENTER_MIN_LOG_HEIGHT, available - CENTER_MIN_DIFF_HEIGHT)

        return CenterSplitSizes(logShare = log / available)
    }
}

/** In dp. These two and the divider between them add up to the pane's height. */
data class CenterSplitHeights(
    val log: Float,
    val diff: Float,
)
