// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A side of a comparison (fork-only, `docs/fork/compare.md`). */
enum class CompareLetter(val fill: Color, val lightOutline: Color) {
    /** Amber: Leaf's dark theme's secondary colour, which no graph lane uses. */
    A(fill = Color(0xFFE9C754), lightOutline = Color(0xFF8A6D12)),

    /** Light blue: the dark theme's primary variant. */
    B(fill = Color(0xFF9FD1FF), lightOutline = Color(0xFF2F6FA8)),
}

/**
 * The near-black of the letters, 11 : 1 on either fill. The fills are fixed, as the avatars' are, rather than theme
 * colours: the theme's colour scheme is upstream's, and custom themes read it from their files.
 */
val COMPARE_LETTER_COLOR = Color(0xFF0E1621)

/**
 * A side's letter on its colour, wherever the comparison names a side: the log, the pane, the full view, the diff. On a
 * light theme the fills stand out from the white and grey only 1.2 to 1.6 : 1, so an outline of the same hue, at least
 * 3 : 1 on white, a header and a selected row, draws the badge.
 */
@Composable
fun CompareBadge(letter: CompareLetter, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .size(16.dp)
            .clip(shape)
            .background(letter.fill)
            .run { if (MaterialTheme.colors.isLight) border(1.dp, letter.lightOutline, shape) else this },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.name,
            color = COMPARE_LETTER_COLOR,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}
