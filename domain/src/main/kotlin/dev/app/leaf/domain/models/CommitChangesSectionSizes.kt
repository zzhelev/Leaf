// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** The smallest Files changed list, in dp: the header, the column header of Split columns and one row. */
const val COMMIT_CHANGES_MIN_FILES_HEIGHT = 100f

/** The smallest commit message box, in dp: one line of text and its padding. */
const val COMMIT_CHANGES_MIN_MESSAGE_HEIGHT = 40f

const val COMMIT_CHANGES_DEFAULT_MESSAGE_HEIGHT = 120f

/**
 * The size the user gave the commit message in the Files changed pane. It is kept as asked, and [fitTo] fits it to the
 * pane's height, so a shorter window shrinks the message and a taller one brings it back.
 *
 * Heights given to [fitTo] and [withDividerMoved] are what the files list, the divider and the message share: the pane
 * without the author footer below the message.
 */
data class CommitChangesSectionSizes(
    /** In dp. */
    val messageHeight: Float = COMMIT_CHANGES_DEFAULT_MESSAGE_HEIGHT,
) {
    /** These sizes, with a damaged or impossible value replaced by its default. */
    fun orDefaults() = CommitChangesSectionSizes(
        messageHeight = messageHeight.takeIf { it.isFinite() && it > 0f } ?: COMMIT_CHANGES_DEFAULT_MESSAGE_HEIGHT,
    )

    /**
     * The sections' heights in [height] dp, the divider included. The files list keeps at least
     * [COMMIT_CHANGES_MIN_FILES_HEIGHT] and the message at least [COMMIT_CHANGES_MIN_MESSAGE_HEIGHT]. Without room for
     * both, the message keeps its minimum and the files list gets what's left.
     */
    fun fitTo(height: Float): CommitChangesSectionHeights {
        val available = (height - SECTION_DIVIDER_HEIGHT).coerceAtLeast(0f)
        val message = messageHeight
            .coerceAtMost(available - COMMIT_CHANGES_MIN_FILES_HEIGHT)
            .coerceAtLeast(COMMIT_CHANGES_MIN_MESSAGE_HEIGHT)
            .coerceAtMost(available)

        return CommitChangesSectionHeights(files = available - message, message = message)
    }

    /** The sizes after dragging the divider above the message [delta] dp down, in [height] dp. */
    fun withDividerMoved(height: Float, delta: Float): CommitChangesSectionSizes {
        val maxMessage = height - SECTION_DIVIDER_HEIGHT - COMMIT_CHANGES_MIN_FILES_HEIGHT

        if (maxMessage < COMMIT_CHANGES_MIN_MESSAGE_HEIGHT) return this

        val message = (fitTo(height).message - delta).coerceIn(COMMIT_CHANGES_MIN_MESSAGE_HEIGHT, maxMessage)

        return copy(messageHeight = message)
    }
}

/** In dp. */
data class CommitChangesSectionHeights(
    val files: Float,
    val message: Float,
)
