// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** Height of the handle between two resizable sections of a pane, in dp. */
const val SECTION_DIVIDER_HEIGHT = 8f

/** The smallest Staged or Unstaged list, in dp: the header, the column header of Split columns and one row. */
const val STATUS_MIN_LIST_HEIGHT = 100f

/** The smallest commit field, in dp: a one-line message box, the line that says why Commit is disabled, and the buttons. */
const val STATUS_MIN_COMMIT_FIELD_HEIGHT = 140f

/**
 * The commit message's title row, in dp, as tall as the Staged and Unstaged headers. It comes on top of the commit field,
 * so [StatusSectionSizes.commitFieldHeight] counts only what is under it: the message, its hint and the buttons.
 */
const val STATUS_COMMIT_TITLE_HEIGHT = 34f

const val STATUS_DEFAULT_STAGED_SHARE = 0.5f
const val STATUS_DEFAULT_COMMIT_FIELD_HEIGHT = 192f

/**
 * A folded list's header, in dp, as the Staged and Unstaged headers are tall: what the open sections leave it when they
 * share the pane (fork-only). An open search field comes under it, and the open sections then give way.
 */
const val STATUS_FOLDED_HEADER_HEIGHT = 34f

/** One of the status pane's three sections, which fold to their header (fork-only). */
enum class StatusSection { STAGED, UNSTAGED, COMMIT_MESSAGE }

/**
 * Which of the status pane's sections are folded (fork-only). A folded list shows its header, and a folded commit
 * message its title row with the subject, what stops the button, and the buttons. The open sections share the room.
 */
data class StatusSectionFolds(
    val staged: Boolean = false,
    val unstaged: Boolean = false,
    val commitMessage: Boolean = false,
) {
    /** How many of Staged and Unstaged are open, from 0 to 2. */
    val openLists: Int get() = (if (staged) 0 else 1) + (if (unstaged) 0 else 1)

    fun isFolded(section: StatusSection): Boolean = when (section) {
        StatusSection.STAGED -> staged
        StatusSection.UNSTAGED -> unstaged
        StatusSection.COMMIT_MESSAGE -> commitMessage
    }

    /** These folds with [section] folded, or opened again when it was folded. */
    fun toggled(section: StatusSection): StatusSectionFolds = when (section) {
        StatusSection.STAGED -> copy(staged = !staged)
        StatusSection.UNSTAGED -> copy(unstaged = !unstaged)
        StatusSection.COMMIT_MESSAGE -> copy(commitMessage = !commitMessage)
    }
}

/**
 * The sizes the user gave the status pane's sections. They are kept as asked, and [fitTo] fits them to the pane's
 * height, so a shorter window shrinks the sections and a taller one brings them back. Folding a section keeps its size,
 * which comes back when it opens again.
 */
data class StatusSectionSizes(
    /** Staged's share of the height the two lists split, from 0 to 1. */
    val stagedShare: Float = STATUS_DEFAULT_STAGED_SHARE,
    /** In dp. */
    val commitFieldHeight: Float = STATUS_DEFAULT_COMMIT_FIELD_HEIGHT,
    /** Fork-only. */
    val folds: StatusSectionFolds = StatusSectionFolds(),
) {
    /** These sizes, with a damaged or impossible value replaced by its default. */
    fun orDefaults() = StatusSectionSizes(
        stagedShare = stagedShare.takeIf { it in 0f..1f } ?: STATUS_DEFAULT_STAGED_SHARE,
        commitFieldHeight = commitFieldHeight.takeIf { it.isFinite() && it > 0f } ?: STATUS_DEFAULT_COMMIT_FIELD_HEIGHT,
        folds = folds,
    )

    /**
     * The sections' heights in a pane [height] dp tall with every section open, both dividers and the commit message's
     * title row included. Each list keeps at least [STATUS_MIN_LIST_HEIGHT] and the commit field at least
     * [STATUS_MIN_COMMIT_FIELD_HEIGHT]. In a pane too short for that, the commit field keeps its minimum and the lists
     * split what's left evenly.
     */
    fun fitTo(height: Float): StatusSectionHeights {
        val open = copy(folds = StatusSectionFolds())
        val commitField = checkNotNull(open.commitFieldIn(height))
        val (staged, unstaged) = splitLists(availableFor(height, openLists = 2) - commitField)

        return StatusSectionHeights(
            staged = staged,
            unstaged = unstaged,
            commitField = commitField,
            commitTitle = commitTitleFor(height),
        )
    }

    /**
     * The commit field's height under its title row in a pane [height] dp tall (fork-only): as asked, but leaving each
     * open list [STATUS_MIN_LIST_HEIGHT], each folded one its header, and itself [STATUS_MIN_COMMIT_FIELD_HEIGHT]. Null
     * while the commit message is folded, or while both lists are, when it takes what their headers leave.
     */
    fun commitFieldIn(height: Float): Float? {
        if (folds.commitMessage || folds.openLists == 0) return null

        val available = availableFor(height, folds.openLists)

        return commitFieldHeight
            .coerceAtMost(available - folds.openLists * STATUS_MIN_LIST_HEIGHT)
            .coerceAtLeast(STATUS_MIN_COMMIT_FIELD_HEIGHT)
            .coerceAtMost(available)
    }

    /** [commitFieldIn] with the title row on top: the whole commit section (fork-only). */
    fun commitSectionIn(height: Float): Float? = commitFieldIn(height)?.let { it + commitTitleFor(height) }

    /**
     * Staged's and Unstaged's heights while both are open and share [lists] dp, the handle between them left out
     * (fork-only). Each keeps at least [STATUS_MIN_LIST_HEIGHT], or half when there is no room for that.
     */
    fun splitLists(lists: Float): Pair<Float, Float> {
        val staged = if (lists >= 2 * STATUS_MIN_LIST_HEIGHT) {
            (lists * stagedShare).coerceIn(STATUS_MIN_LIST_HEIGHT, lists - STATUS_MIN_LIST_HEIGHT)
        } else {
            lists / 2
        }

        return staged to lists - staged
    }

    /** The sizes after dragging the handle between the lists [delta] dp down, in a pane [height] dp tall. */
    fun withListsDividerMoved(height: Float, delta: Float, stagedOnTop: Boolean): StatusSectionSizes {
        val heights = fitTo(height)

        return withListsDividerMovedIn(heights.staged + heights.unstaged, delta, stagedOnTop)
    }

    /**
     * The sizes after dragging the handle between the lists [delta] dp down, while they share [lists] dp, the handle
     * left out (fork-only).
     */
    fun withListsDividerMovedIn(lists: Float, delta: Float, stagedOnTop: Boolean): StatusSectionSizes {
        if (lists < 2 * STATUS_MIN_LIST_HEIGHT) return this

        val staged = (splitLists(lists).first + if (stagedOnTop) delta else -delta)
            .coerceIn(STATUS_MIN_LIST_HEIGHT, lists - STATUS_MIN_LIST_HEIGHT)

        return copy(stagedShare = staged / lists)
    }

    /**
     * The sizes after dragging the handle above the commit field [delta] dp down, in a pane [height] dp tall. The top
     * list keeps its height, so only the bottom list and the commit field change, as with any split pane. Once the
     * bottom list is at its minimum, the top list gives way. With a list folded (fork-only), the open one takes the rest.
     */
    fun withCommitDividerMoved(height: Float, delta: Float, stagedOnTop: Boolean): StatusSectionSizes {
        val current = commitFieldIn(height) ?: return this
        val available = availableFor(height, folds.openLists)
        val maxCommitField = available - folds.openLists * STATUS_MIN_LIST_HEIGHT

        if (maxCommitField < STATUS_MIN_COMMIT_FIELD_HEIGHT) return this

        val commitField = (current - delta).coerceIn(STATUS_MIN_COMMIT_FIELD_HEIGHT, maxCommitField)

        if (folds.openLists < 2) return copy(commitFieldHeight = commitField)

        val heights = fitTo(height)
        val lists = available - commitField
        val top = (if (stagedOnTop) heights.staged else heights.unstaged)
            .coerceIn(STATUS_MIN_LIST_HEIGHT, lists - STATUS_MIN_LIST_HEIGHT)
        val staged = if (stagedOnTop) top else lists - top

        return copy(stagedShare = staged / lists, commitFieldHeight = commitField)
    }

    /** These sizes with [section] folded, or opened again (fork-only). */
    fun withFoldToggled(section: StatusSection): StatusSectionSizes = copy(folds = folds.toggled(section))
}

/** The title row's height in a pane [height] dp tall: whole, unless the pane has no room for the dividers and it. */
private fun commitTitleFor(height: Float) =
    STATUS_COMMIT_TITLE_HEIGHT.coerceAtMost((height - 2 * SECTION_DIVIDER_HEIGHT).coerceAtLeast(0f))

/**
 * What the open lists and the commit field share in a pane [height] dp tall: what the handles, the title row and the
 * folded lists' headers leave. Two open lists have a handle between them, and the commit field one above it.
 */
private fun availableFor(height: Float, openLists: Int) = (
    height -
        (if (openLists == 2) 2 else 1) * SECTION_DIVIDER_HEIGHT -
        commitTitleFor(height) -
        (2 - openLists) * STATUS_FOLDED_HEADER_HEIGHT
    ).coerceAtLeast(0f)

/** In dp. The two dividers and these four add up to the pane's height. */
data class StatusSectionHeights(
    val staged: Float,
    val unstaged: Float,
    /** The message, its hint and the buttons, under the title row. */
    val commitField: Float,
    /** The title row above the commit field. */
    val commitTitle: Float = STATUS_COMMIT_TITLE_HEIGHT,
) {
    /** The whole commit section, the title row and what is under it. */
    val commitSection: Float get() = commitTitle + commitField
}
