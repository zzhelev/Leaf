// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.find.FindMatch
import dev.app.leaf.domain.find.FindResult
import dev.app.leaf.domain.find.FindRow
import dev.app.leaf.domain.find.findMatches
import dev.app.leaf.domain.find.firstMatchFrom
import dev.app.leaf.domain.find.matchKeptFrom
import dev.app.leaf.domain.find.steppedMatch
import dev.app.leaf.domain.log.SearchStep

/** The find strip's two fields, and what it shows: find, or go to line. */
enum class FindField { FIND, LINE }

/** Asks the strip to put the focus in [field], each request with a number of its own. */
data class FindFocusRequest(val field: FindField, val number: Int)

/** Asks the view to scroll [row] into sight, each request with a number of its own; [isMatch] for the current match. */
data class FindRevealRequest(val row: Int, val number: Int, val isMatch: Boolean)

/**
 * The find strip of one view, the diff pane, file history or blame (fork-only, upstream issues 275 and 312): whether it
 * shows, what is typed in its fields, what find found in the view's rows and which match is the current one, the row
 * that go to line went to, and every line selected with Select all.
 */
@Stable
class FindState {
    var isOpen by mutableStateOf(false)
        private set

    /** What the strip shows: find, or go to line, as the key that opened it last asked. */
    var mode by mutableStateOf(FindField.FIND)
        private set
    var query by mutableStateOf(TextFieldValue(""))
    var matchCase by mutableStateOf(false)
    var lineText by mutableStateOf(TextFieldValue(""))

    var result by mutableStateOf(FindResult.NONE)
        private set

    /** The current match's index in [result], -1 for none. */
    var current by mutableIntStateOf(-1)
        private set

    /** The row that go to line went to, marked until find moves on or the strip closes. */
    var goToRow by mutableStateOf<Int?>(null)
        private set

    var focusRequest by mutableStateOf<FindFocusRequest?>(null)
        private set

    var revealRequest by mutableStateOf<FindRevealRequest?>(null)
        private set

    /** The current match waits to be scrolled into sight sideways, in lines that don't wrap. */
    var sidewaysRevealPending by mutableStateOf(false)
        private set

    /** The view's lines are all selected (Select all), on [selectedCell]: 0, or the old (0) or the new (1) side. */
    var selectedCell by mutableStateOf<Int?>(null)
        private set

    /** The split diff's side that the last press was on, which Select all selects. The new side at first. */
    var pressedCell by mutableIntStateOf(1)

    /** A line to go to once the view shows the whole file. */
    var pendingLine: Int? = null

    /** The strip's fields have the focus, which keep Select all and Copy for their own text. */
    var isBarFocused = false

    /** The lines that the view shows, which Select all selects and Copy copies; null while it shows none. */
    var selectableText: FindableText? = null

    // What the last find was asked for, to tell a new query from a changed text
    private var lastQuery: String? = null
    private var lastMatchCase = false
    private var lastSource: Any? = null
    private var handledReveal = 0
    private var handledFocus = 0

    val currentMatch: FindMatch? get() = result.matches.getOrNull(current)

    /**
     * Opens the strip on [field]'s mode, or switches it there, with the focus in that field. Going to a line puts find's
     * matches away, as the strip no longer shows them; back on find, they are found again from where the list is.
     */
    fun open(field: FindField) {
        if (field == FindField.LINE) clearMatches()

        mode = field
        isOpen = true
        focusRequest = FindFocusRequest(field, (focusRequest?.number ?: 0) + 1)
    }

    fun close() {
        isOpen = false
        clearMatches()
        goToRow = null
        pendingLine = null
    }

    private fun clearMatches() {
        result = FindResult.NONE
        current = -1
        lastQuery = null
        sidewaysRevealPending = false
    }

    fun step(step: SearchStep) {
        if (result.matches.isEmpty()) return

        current = steppedMatch(current, result.matches.size, step)
        revealCurrent()
    }

    /** Find goes to [row], which go to line found, and marks it. */
    fun goTo(row: Int) {
        goToRow = row
        revealRequest = FindRevealRequest(row, nextRevealNumber(), isMatch = false)
    }

    /**
     * Finds the query in [rows], which come from [source] (the file shown): the matches of another query start at the
     * first one at or below [topRow], where the reader is, and are scrolled to; the same query in a changed text keeps
     * its place, and in another file starts from [topRow] without scrolling.
     */
    fun find(rows: List<FindRow>, source: Any?, topRow: Int) {
        apply(findMatches(rows, query.text, matchCase), source, topRow)
    }

    /** [find]'s second half, for a result found elsewhere (off the UI thread). */
    fun apply(found: FindResult, source: Any?, topRow: Int) {
        val queryText = query.text
        val isNewQuery = queryText != lastQuery || matchCase != lastMatchCase
        val isNewSource = source != lastSource
        val previous = currentMatch

        lastQuery = queryText
        lastMatchCase = matchCase
        lastSource = source
        result = found

        current = when {
            isNewQuery && previous != null && !isNewSource -> matchKeptFrom(previous, found.matches)
            isNewQuery || isNewSource -> firstMatchFrom(found.matches, topRow)
            else -> matchKeptFrom(previous, found.matches)
        }

        if (isNewQuery && currentMatch != null) revealCurrent()
    }

    /** Select all, on [pressedCell]; any press, or another key, ends it. */
    fun selectAll(isSplit: Boolean) {
        selectedCell = if (isSplit) pressedCell else 0
    }

    fun clearSelection() {
        selectedCell = null
    }

    /**
     * Whether [request] is new, which the strip then puts the focus for: a strip drawn again, after a file without lines,
     * leaves the focus where it is.
     */
    fun takeFocusRequest(request: FindFocusRequest): Boolean {
        if (request.number <= handledFocus) return false

        handledFocus = request.number
        return true
    }

    /** Whether [request] is new, which the view then scrolls to: a view composed again sees the last one again. */
    fun takeReveal(request: FindRevealRequest): Boolean {
        if (request.number <= handledReveal) return false

        handledReveal = request.number
        return true
    }

    fun sidewaysRevealed() {
        sidewaysRevealPending = false
    }

    /** Selects the text of [field], as Ctrl+F (⌘F) does on a field that has some, to type over it. */
    fun selectFieldText(field: FindField) {
        when (field) {
            FindField.FIND -> query = query.copy(selection = TextRange(0, query.text.length))
            FindField.LINE -> lineText = lineText.copy(selection = TextRange(0, lineText.text.length))
        }
    }

    private fun revealCurrent() {
        val match = currentMatch ?: return

        goToRow = null
        sidewaysRevealPending = true
        revealRequest = FindRevealRequest(match.row, nextRevealNumber(), isMatch = true)
    }

    private fun nextRevealNumber() = (revealRequest?.number ?: 0) + 1
}
