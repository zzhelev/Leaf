// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * The log's optional columns, after Graph and Message, which are always shown. Widths are in dp. The settings file
 * stores each by its [storedName], which stays when an entry is renamed.
 */
enum class LogColumn(val defaultWidth: Float, val storedName: String) {
    Author(150f, "Author"),

    /** When the change was written: git's author date, which an amend, a rebase or a cherry-pick keeps. */
    AuthorDate(110f, "AuthorDate"),
    Committer(150f, "Committer"),

    /** When this version of the commit was made: git's commit date, by which the log orders its rows. */
    CommitDate(110f, "Date"),
    Commit(80f, "Commit"),
    ;

    val isDate: Boolean get() = this == AuthorDate || this == CommitDate
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

    /** Whether a date column is shown, which the time applies to. */
    val showsDate: Boolean get() = columns.any { it.column.isDate && it.isVisible }

    /** Turning the time on widens both date columns to fit it, unless they're already wider. */
    fun withDateShowingTime(showTime: Boolean): LogColumnsSettings {
        val settings = copy(dateShowsTime = showTime)

        return if (showTime) {
            settings.copy(
                columns = settings.columns.map { entry ->
                    if (entry.column.isDate && entry.width < DATE_WITH_TIME_WIDTH) {
                        entry.copy(width = DATE_WITH_TIME_WIDTH)
                    } else {
                        entry
                    }
                },
            )
        } else {
            settings
        }
    }

    fun withGraphMaxWidth(width: Float): LogColumnsSettings =
        copy(graphMaxWidth = clampGraphWidth(width, DEFAULT_GRAPH_MAX_WIDTH))

    /**
     * Fork-only: the columns as wide as their text needs at a bigger text size, each width times [fontScale] when it is
     * over 1. The widths that are saved, and that the dividers set, are the default text size's.
     */
    fun scaledForText(fontScale: Float): LogColumnsSettings =
        if (fontScale <= 1f) this else copy(columns = columns.map { it.copy(width = it.width * fontScale) })

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
            LogColumnEntry(column, isVisible = column == LogColumn.CommitDate, width = column.defaultWidth)
        }

        /** The order in which columns give way when the log is too narrow for all of them. */
        val HIDE_ORDER = listOf(
            LogColumn.Committer,
            LogColumn.Commit,
            LogColumn.AuthorDate,
            LogColumn.Author,
            LogColumn.CommitDate,
        )

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

/** The graph column's width: as wide as its lanes ([lanesWidth]), up to [maxWidth], and never below [minWidth]. */
fun graphColumnWidth(lanesWidth: Float, maxWidth: Float, minWidth: Float = LogColumnsSettings.MIN_GRAPH_WIDTH): Float =
    minOf(lanesWidth, maxWidth).coerceAtLeast(minWidth)

/**
 * Fork-only: the graph column's narrowest, which leaves its "Graph" header room at a bigger text size: 16 dp before it,
 * and about 40 dp for the word at the default size, times [fontScale]. [LogColumnsSettings.MIN_GRAPH_WIDTH] at the
 * default size and below.
 */
fun minGraphWidth(fontScale: Float): Float = maxOf(LogColumnsSettings.MIN_GRAPH_WIDTH, 16f + 40f * fontScale)

/**
 * The graph's widest size after dragging its divider by [delta] from [shownWidth], which [graphColumnWidth] gave. The
 * column can't get wider than its lanes or narrower than the minimum. At the lanes' width, a wider [maxWidth], which
 * may have been set in a busier repository, is kept.
 */
fun draggedGraphMaxWidth(
    maxWidth: Float,
    shownWidth: Float,
    lanesWidth: Float,
    delta: Float,
    minWidth: Float = LogColumnsSettings.MIN_GRAPH_WIDTH,
): Float {
    val fullWidth = maxOf(lanesWidth, minWidth)
    val newWidth = (shownWidth + delta).coerceIn(minWidth, fullWidth)

    return if (newWidth == fullWidth) maxOf(maxWidth, fullWidth) else newWidth
}

/**
 * Whether someone other than the author committed this commit, as after another person's rebase, cherry-pick or
 * amend, or a merge on GitHub's website, which records GitHub as the committer.
 */
val Commit.isCommittedBySomeoneElse: Boolean
    get() = !committer.isSamePersonAs(author)

/**
 * Whether the commit's committer or commit date differs from its author or author date, as after an amend, a rebase or
 * a cherry-pick. Dates are compared to the minute, as they're shown.
 */
val Commit.isCommittedApartFromAuthoring: Boolean
    get() = isCommittedApart(author, authorDate, committer, date)

/** [Commit.isCommittedApartFromAuthoring] for a commit's identities and dates, in epoch seconds. */
fun isCommittedApart(author: Identity, authorDate: Long, committer: Identity, committerDate: Long): Boolean =
    !committer.isSamePersonAs(author) || Math.floorDiv(committerDate, 60L) != Math.floorDiv(authorDate, 60L)

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
