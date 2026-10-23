// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

/**
 * A range of commits selected in the log (fork-only, step A2 of `docs/fork/squash-and-rebase.md`): Shift-click and
 * Shift+↑↓ take the rows between [anchor] and [lead], as the log shows them. Any rows can be selected, a stash's and
 * other branches' too: which of them a rewrite can join is decided when its menu opens. The uncommitted changes' row is
 * no commit, so it can't be.
 *
 * No range (both null, or both the same commit) means the log's own selection, one commit, is what shows.
 *
 * @param anchor where the range starts: the commit that was selected when it began, which the log keeps selected.
 * @param lead the other end, which Shift-click sets and Shift+↑↓ moves.
 */
data class LogSelection(
    val anchor: String? = null,
    val lead: String? = null,
) {
    /** The rows from [anchor] to [lead], in [rows]' order (the log's, newest first). Empty when it isn't a range. */
    fun commitsIn(rows: List<String>): List<String> {
        if (anchor == null || lead == null || anchor == lead) return emptyList()

        val start = rows.indexOf(anchor)
        val end = rows.indexOf(lead)

        if (start == -1 || end == -1) return emptyList()

        return rows.subList(minOf(start, end), maxOf(start, end) + 1)
    }

    /**
     * Shift-click on [commit]: the range from the anchor, or from [selected], the commit that the log has selected, when
     * there's none yet. Without either, or on the anchor itself, there's no range.
     */
    fun extendedTo(commit: String, selected: String?): LogSelection {
        val start = anchor ?: selected ?: return LogSelection()

        return if (start == commit) LogSelection() else LogSelection(start, commit)
    }

    /**
     * Shift+↑ or Shift+↓ ([step] -1 or 1): the lead moves a row in [rows], from the anchor, or from [selected] when
     * there's no range yet. Null when it can't: at the log's ends, or without a start that the log has.
     */
    fun stepped(step: Int, rows: List<String>, selected: String?): LogSelection? {
        val start = anchor ?: selected ?: return null
        val from = rows.indexOf(lead ?: start)

        if (from == -1 || start !in rows) return null

        val lead = rows.getOrNull(from + step) ?: return null

        return if (lead == start) LogSelection() else LogSelection(start, lead)
    }

    /** What a refresh of the log leaves: no range once either end isn't in it any more. */
    fun prunedTo(rows: Collection<String>): LogSelection =
        if (anchor != null && (anchor !in rows || lead !in rows)) LogSelection() else this
}
