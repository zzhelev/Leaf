// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.app.leaf.theme.backgroundSelected

/** How far a selected row's highlight is from the pane's sides, and from the rows above and below it. */
val SELECTION_INSET = 4.dp
private val SELECTION_GAP = 1.dp
private val SELECTION_CORNER = 6.dp

/**
 * The highlight of a selected row in the branches pane (mockup A of the window chrome review,
 * `docs/fork/window-chrome.md`, section 4): rounded, and [SELECTION_INSET] in from the row's sides. The log keeps its
 * full-width rows.
 */
@Composable
fun Modifier.insetSelection(isSelected: Boolean): Modifier {
    if (!isSelected) return this

    val color = MaterialTheme.colors.backgroundSelected

    return drawBehind {
        val inset = SELECTION_INSET.toPx()
        val gap = SELECTION_GAP.toPx()
        val corner = SELECTION_CORNER.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(inset, gap),
            size = Size(size.width - 2 * inset, size.height - 2 * gap),
            cornerRadius = CornerRadius(corner),
        )
    }
}
