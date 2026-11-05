// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

private const val ELLIPSIS = "…"

/**
 * [text] on one line, cut in the middle when it doesn't fit, so that both ends stay: branch names that agents make
 * often differ only at the end (`claude/fix-login-redirect-on-safari`, `…-on-firefox`). Compose's
 * `TextOverflow.MiddleEllipsis` cuts at the end on desktop (1.12.1). [onCut] says whether it was cut.
 */
@Composable
fun MiddleCutText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onCut: (Boolean) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val maxWidth = constraints.maxWidth
        val shownText = remember(text, style, maxWidth) {
            if (maxWidth == Constraints.Infinity) {
                text
            } else {
                middleCut(text) { candidate ->
                    textMeasurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= maxWidth
                }
            }
        }

        SideEffect { onCut(shownText != text) }

        Text(
            text = shownText,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * [text] as it is when it [fits], or else as many of its characters as fit, half from its start and half from its end,
 * with "…" between them. A character outside the BMP is never split.
 */
fun middleCut(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text

    var low = 0
    var high = text.length - 1

    while (low < high) {
        val kept = (low + high + 1) / 2

        if (fits(keepingEnds(text, kept))) low = kept else high = kept - 1
    }

    return keepingEnds(text, low)
}

/** [text] with [kept] of its characters, the extra one at its start, and "…" in place of the rest. */
internal fun keepingEnds(text: String, kept: Int): String {
    var headEnd = (kept + 1) / 2
    var tailStart = text.length - (kept - (kept + 1) / 2)

    if (headEnd > 0 && text[headEnd - 1].isHighSurrogate()) headEnd--
    if (tailStart < text.length && text[tailStart].isLowSurrogate()) tailStart++

    return text.substring(0, headEnd) + ELLIPSIS + text.substring(tailStart)
}
