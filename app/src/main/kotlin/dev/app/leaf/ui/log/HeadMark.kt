// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The lane's width, which is as wide as a node's cell: a bigger ring would be cut to it. */
internal val HEAD_RING_SIZE = 14.dp
internal val HEAD_RING_WIDTH = 2.dp

/**
 * The ring around the node of the commit that the tab's worktree has checked out (fork-only, upstream issue 255): a ring
 * in the graph, where one looks for HEAD, and the subject in bold ([boldWhenHead]), where one reads the list. Chosen
 * from a ring, a ring with bold, bold alone and a row tint. A tint can't tell itself from the selection, which is pale
 * blue in the light theme and covers it in the dark one, and a ring in the lane's colour blends into the node.
 *
 * The ring is in the text colour, 14 dp wide with a 2 dp stroke, so its inside is the 10 dp node's size, and it needs no
 * room of its own. Neither mark touches what the row already says: the selection fills the row, a
 * search match has its bar and outline in the message cell, and the dim of a row that doesn't match leaves the graph
 * alone.
 */
@Composable
fun HeadRing(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(HEAD_RING_SIZE)
            .border(HEAD_RING_WIDTH, MaterialTheme.colors.onBackground, CircleShape),
    )
}

/** The subject's style on HEAD's row: [this] in bold. */
fun TextStyle.boldWhenHead(isHead: Boolean): TextStyle = if (isHead) copy(fontWeight = FontWeight.Bold) else this
