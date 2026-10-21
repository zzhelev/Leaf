// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.commitmessage.CoAuthor

/**
 * What the commit field needs for its co-author suggestions (fork-only).
 *
 * @property candidates the people to suggest, from the history the log has loaded ([coAuthorCandidates])
 * @property insertion the `Co-authored-by` line that "Add co-author" put in the message, while it may still be taken out
 * @property dismissals how many times Esc closed the suggestions: the field hides them until its text changes
 */
@Immutable
data class CoAuthorState(
    val candidates: List<CoAuthor> = emptyList(),
    val insertion: CoAuthorInsertion? = null,
    val dismissals: Int = 0,
)

/**
 * The empty `Co-authored-by` line that "Add co-author" added (fork-only): the message [before] it, and the message
 * [after] it. While the message is still [after], leaving the line puts [before] back.
 */
data class CoAuthorInsertion(val id: Long, val before: TextFieldValue, val after: String)

/** What the commit field tells the view model about co-authors (fork-only). */
sealed interface CoAuthorAction {
    /** The menu's "Add co-author": adds an empty `Co-authored-by` line where git puts trailers. */
    data object AddLine : CoAuthorAction

    /** The suggestions showed or hid, so that Esc closes them first. */
    data class SuggestionsShown(val shown: Boolean) : CoAuthorAction

    /** The field lost the focus. */
    data object FieldLeft : CoAuthorAction
}
