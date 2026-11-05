// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.foundation.background
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A header's fill, the headers' colour, and a line in the separators' colour along its bottom when that fill is the
 * panels' colour, as in Tonal and Quiet, where only the line sets the header apart (`docs/fork/window-chrome.md`,
 * section 4). Classic's headers have a fill of their own, and no line.
 */
@Composable
fun Modifier.headerBackground(): Modifier {
    val colors = MaterialTheme.colors
    val line = if (colors.tertiarySurface == colors.surface) MaterialTheme.frameColors.separator else Color.Transparent

    return background(colors.tertiarySurface).bottomEdge(line)
}
