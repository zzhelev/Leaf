// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** Height of the handle between two resizable sections of a pane, in dp. */
const val SECTION_DIVIDER_HEIGHT = 8f

/** The smallest Staged or Unstaged list, in dp: the header, the column header of Split columns and one row. */
const val STATUS_MIN_LIST_HEIGHT = 100f

/** The smallest commit field, in dp: a one-line message box, the Amend checkbox and the buttons. */
const val STATUS_MIN_COMMIT_FIELD_HEIGHT = 140f

const val STATUS_DEFAULT_STAGED_SHARE = 0.5f
const val STATUS_DEFAULT_COMMIT_FIELD_HEIGHT = 192f

/**
 * The sizes the user gave the status pane's sections. They are kept as asked, and [fitTo] fits them to the pane's
 * height, so a shorter window shrinks the sections and a taller one brings them back.
 */
data class StatusSectionSizes(
    /** Staged's share of the height the two lists split, from 0 to 1. */
    val stagedShare: Float = STATUS_DEFAULT_STAGED_SHARE,
    /** In dp. */
    val commitFieldHeight: Float = STATUS_DEFAULT_COMMIT_FIELD_HEIGHT,
) {
    /** These sizes, with a damaged or impossible value replaced by its default. */
    fun orDefaults() = StatusSectionSizes(
        stagedShare = stagedShare.takeIf { it in 0f..1f } ?: STATUS_DEFAULT_STAGED_SHARE,
        commitFieldHeight = commitFieldHeight.takeIf { it.isFinite() && it > 0f } ?: STATUS_DEFAULT_COMMIT_FIELD_HEIGHT,
    )

    /**
     * The sections' heights in a pane [height] dp tall, both dividers included. Each list keeps at least
     * [STATUS_MIN_LIST_HEIGHT] and the commit field at least [STATUS_MIN_COMMIT_FIELD_HEIGHT]. In a pane too short for
     * that, the commit field keeps its minimum and the lists split what's left evenly.
     */
    fun fitTo(height: Float): StatusSectionHeights {
        val available = (height - 2 * SECTION_DIVIDER_HEIGHT).coerceAtLeast(0f)
        val commitField = commitFieldHeight
            .coerceAtMost(available - 2 * STATUS_MIN_LIST_HEIGHT)
            .coerceAtLeast(STATUS_MIN_COMMIT_FIELD_HEIGHT)
            .coerceAtMost(available)
        val lists = available - commitField
        val staged = if (lists >= 2 * STATUS_MIN_LIST_HEIGHT) {
            (lists * stagedShare).coerceIn(STATUS_MIN_LIST_HEIGHT, lists - STATUS_MIN_LIST_HEIGHT)
        } else {
            lists / 2
        }

        return StatusSectionHeights(staged = staged, unstaged = lists - staged, commitField = commitField)
    }

    /** The sizes after dragging the handle between the lists [delta] dp down, in a pane [height] dp tall. */
    fun withListsDividerMoved(height: Float, delta: Float, stagedOnTop: Boolean): StatusSectionSizes {
        val heights = fitTo(height)
        val lists = heights.staged + heights.unstaged

        if (lists < 2 * STATUS_MIN_LIST_HEIGHT) return this

        val staged = (heights.staged + if (stagedOnTop) delta else -delta)
            .coerceIn(STATUS_MIN_LIST_HEIGHT, lists - STATUS_MIN_LIST_HEIGHT)

        return copy(stagedShare = staged / lists)
    }

    /**
     * The sizes after dragging the handle above the commit field [delta] dp down, in a pane [height] dp tall. The top
     * list keeps its height, so only the bottom list and the commit field change, as with any split pane. Once the
     * bottom list is at its minimum, the top list gives way.
     */
    fun withCommitDividerMoved(height: Float, delta: Float, stagedOnTop: Boolean): StatusSectionSizes {
        val heights = fitTo(height)
        val available = height - 2 * SECTION_DIVIDER_HEIGHT
        val maxCommitField = available - 2 * STATUS_MIN_LIST_HEIGHT

        if (maxCommitField < STATUS_MIN_COMMIT_FIELD_HEIGHT) return this

        val commitField = (heights.commitField - delta).coerceIn(STATUS_MIN_COMMIT_FIELD_HEIGHT, maxCommitField)
        val lists = available - commitField
        val top = (if (stagedOnTop) heights.staged else heights.unstaged)
            .coerceIn(STATUS_MIN_LIST_HEIGHT, lists - STATUS_MIN_LIST_HEIGHT)
        val staged = if (stagedOnTop) top else lists - top

        return StatusSectionSizes(stagedShare = staged / lists, commitFieldHeight = commitField)
    }
}

/** In dp. */
data class StatusSectionHeights(
    val staged: Float,
    val unstaged: Float,
    val commitField: Float,
)
