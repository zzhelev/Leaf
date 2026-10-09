// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** The log's optional columns, after Graph and Message, which are always shown. Widths are in dp. */
enum class LogColumn(val defaultWidth: Float) {
    Author(150f),
    Committer(150f),
    Date(110f),
    Commit(80f),
}

data class LogColumnEntry(
    val column: LogColumn,
    val isVisible: Boolean,
    val width: Float,
)

/**
 * Which optional log columns are shown, in which order and how wide, plus the graph column's widest size. Shared by
 * every tab. [columns] has every [LogColumn] exactly once, in display order: build changed settings through the
 * functions below, which keep that true.
 */
data class LogColumnsSettings(
    val columns: List<LogColumnEntry> = DEFAULT_COLUMNS,
    val dateShowsTime: Boolean = false,
    /** The graph column is as wide as its lanes, up to this width. */
    val graphMaxWidth: Float = DEFAULT_GRAPH_MAX_WIDTH,
) {
    fun entry(column: LogColumn): LogColumnEntry = columns.first { it.column == column }

    fun isVisible(column: LogColumn): Boolean = entry(column).isVisible

    fun toggled(column: LogColumn): LogColumnsSettings = update(column) { it.copy(isVisible = !it.isVisible) }

    fun resized(column: LogColumn, width: Float): LogColumnsSettings =
        update(column) { it.copy(width = clampColumnWidth(width, column.defaultWidth)) }

    /** Turning the time on widens the date column to fit it, unless it's already wider. */
    fun withDateShowingTime(showTime: Boolean): LogColumnsSettings {
        val settings = copy(dateShowsTime = showTime)

        return if (showTime && entry(LogColumn.Date).width < DATE_WITH_TIME_WIDTH) {
            settings.resized(LogColumn.Date, DATE_WITH_TIME_WIDTH)
        } else {
            settings
        }
    }

    fun withGraphMaxWidth(width: Float): LogColumnsSettings =
        copy(graphMaxWidth = clampGraphWidth(width, DEFAULT_GRAPH_MAX_WIDTH))

    /**
     * The visible columns that fit in [availableWidth] next to a message at least [messageMinWidth] wide, each column
     * taking [spacing] more for its divider. Columns that don't fit are left out in [HIDE_ORDER], whatever their
     * position.
     */
    fun fitting(
        availableWidth: Float,
        messageMinWidth: Float = MESSAGE_MIN_WIDTH,
        spacing: Float = 0f,
    ): FittedLogColumns {
        val shown = columns.filter { it.isVisible }.toMutableList()
        val leftOut = mutableSetOf<LogColumn>()

        for (column in HIDE_ORDER) {
            val needed = messageMinWidth + shown.sumOf { (it.width + spacing).toDouble() }

            if (needed <= availableWidth) break

            if (shown.removeIf { it.column == column }) {
                leftOut += column
            }
        }

        return FittedLogColumns(shown, leftOut)
    }

    /**
     * The widest [column] can get while the other columns that [fitting] shows still fit, so that widening one column
     * doesn't push another out.
     */
    fun maxWidthFor(
        column: LogColumn,
        availableWidth: Float,
        messageMinWidth: Float = MESSAGE_MIN_WIDTH,
        spacing: Float = 0f,
    ): Float {
        val others = fitting(availableWidth, messageMinWidth, spacing).shown.filter { it.column != column }
        val othersWidth = others.sumOf { (it.width + spacing).toDouble() }.toFloat()

        return (availableWidth - messageMinWidth - othersWidth - spacing).coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)
    }

    private fun update(column: LogColumn, change: (LogColumnEntry) -> LogColumnEntry) = copy(
        columns = columns.map { if (it.column == column) change(it) else it },
    )

    companion object {
        val DEFAULT_COLUMNS = LogColumn.entries.map { column ->
            LogColumnEntry(column, isVisible = column == LogColumn.Date, width = column.defaultWidth)
        }

        /** The order in which columns give way when the log is too narrow for all of them. */
        val HIDE_ORDER = listOf(LogColumn.Committer, LogColumn.Commit, LogColumn.Author, LogColumn.Date)

        const val DATE_WITH_TIME_WIDTH = 180f
        const val MIN_COLUMN_WIDTH = 48f
        const val MAX_COLUMN_WIDTH = 600f
        const val MESSAGE_MIN_WIDTH = 200f

        const val DEFAULT_GRAPH_MAX_WIDTH = 120f

        /** Below this, the "Graph" header would be cut. */
        const val MIN_GRAPH_WIDTH = 56f
        const val MAX_GRAPH_WIDTH = 2000f

        fun clampColumnWidth(width: Float, default: Float): Float =
            if (width.isFinite()) width.coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH) else default

        fun clampGraphWidth(width: Float, default: Float): Float =
            if (width.isFinite()) width.coerceIn(MIN_GRAPH_WIDTH, MAX_GRAPH_WIDTH) else default
    }
}

/** The columns a log row shows, in display order, and the visible ones that didn't fit. */
data class FittedLogColumns(
    val shown: List<LogColumnEntry>,
    val leftOut: Set<LogColumn>,
)

/** The graph column's width: as wide as its lanes ([lanesWidth]), up to [maxWidth], and never below the minimum. */
fun graphColumnWidth(lanesWidth: Float, maxWidth: Float): Float =
    minOf(lanesWidth, maxWidth).coerceAtLeast(LogColumnsSettings.MIN_GRAPH_WIDTH)

/**
 * The graph's widest size after dragging its divider by [delta] from [shownWidth], which [graphColumnWidth] gave. The
 * column can't get wider than its lanes or narrower than the minimum. At the lanes' width, a wider [maxWidth], which
 * may have been set in a busier repository, is kept.
 */
fun draggedGraphMaxWidth(maxWidth: Float, shownWidth: Float, lanesWidth: Float, delta: Float): Float {
    val fullWidth = maxOf(lanesWidth, LogColumnsSettings.MIN_GRAPH_WIDTH)
    val newWidth = (shownWidth + delta).coerceIn(LogColumnsSettings.MIN_GRAPH_WIDTH, fullWidth)

    return if (newWidth == fullWidth) maxOf(maxWidth, fullWidth) else newWidth
}

/**
 * Whether someone other than the author committed this commit, as after another person's rebase, cherry-pick or
 * amend, or a merge on GitHub's website, which records GitHub as the committer.
 */
val Commit.isCommittedBySomeoneElse: Boolean
    get() = !committer.isSamePersonAs(author)

/**
 * Whether two identities are the same person: the same email, ignoring case, or the same name when either has no
 * email. A name spelled differently with the same email is the same person.
 */
fun Identity.isSamePersonAs(other: Identity): Boolean {
    val email = email?.trim().orEmpty()
    val otherEmail = other.email?.trim().orEmpty()

    return if (email.isNotEmpty() && otherEmail.isNotEmpty()) {
        email.equals(otherEmail, ignoreCase = true)
    } else {
        name.orEmpty().trim() == other.name.orEmpty().trim()
    }
}
