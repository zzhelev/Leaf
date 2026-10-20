// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * How much of a row that doesn't match the log search is left (fork-only). The row's text read as
 * #CCCBCB against a match's white in the dark theme, which was too close, and the avatars, chips and date weren't
 * dimmed at all. The light theme gets more because its text is darker than white to start with: both leave the text at
 * about 4.5:1 (dark) and 3.5:1 (light) against the background.
 */
const val SEARCH_NON_MATCH_ALPHA_DARK = 0.45f
const val SEARCH_NON_MATCH_ALPHA_LIGHT = 0.55f

private val FOCUSED_MATCH_OUTLINE_WIDTH = 2.dp
private val FOCUSED_MATCH_OUTLINE_SHAPE = RoundedCornerShape(4.dp)

/**
 * Fades the whole row, message, avatars, chips and the other columns, when [matchesSearchFilter] is false. It is null
 * while nothing is searched for, and true for a match. The graph isn't part of it, so its lines stay continuous.
 */
@Composable
fun Modifier.dimmedWhenNotMatching(matchesSearchFilter: Boolean?): Modifier {
    if (matchesSearchFilter != false) return this

    val remaining = if (MaterialTheme.colors.isLight) SEARCH_NON_MATCH_ALPHA_LIGHT else SEARCH_NON_MATCH_ALPHA_DARK

    return alpha(remaining)
}

/**
 * Outlines the row of the search result that the counter points at, in the colour of the matches' bar. It sits on the
 * same row as the selection's fill without hiding it, and stays on screen when the next result is also visible, where
 * the counter is the only other sign of which one it is.
 */
@Composable
fun Modifier.outlinedWhenFocusedMatch(isFocusedMatch: Boolean): Modifier {
    if (!isFocusedMatch) return this

    return border(FOCUSED_MATCH_OUTLINE_WIDTH, MaterialTheme.colors.secondary, FOCUSED_MATCH_OUTLINE_SHAPE)
}
