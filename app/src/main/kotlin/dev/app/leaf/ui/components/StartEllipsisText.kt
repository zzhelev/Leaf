// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

private const val ELLIPSIS = "…"

/**
 * A path on one line that drops its start when it doesn't fit, so the folder nearest the file stays visible:
 * `…/screen/settings/memory`. It cuts at a `/` when it can, and inside the last folder name only when that alone is
 * too long.
 */
@Composable
fun StartEllipsisPathText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val maxWidth = constraints.maxWidth
        val shownText = remember(text, style, maxWidth) { fitFromEnd(text, maxWidth, textMeasurer, style) }

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

private fun fitFromEnd(text: String, maxWidth: Int, textMeasurer: TextMeasurer, style: TextStyle): String {
    fun fits(candidate: String) =
        textMeasurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= maxWidth

    if (maxWidth == Constraints.Infinity || fits(text)) return text

    var slash = text.indexOf('/')

    while (slash != -1) {
        val candidate = ELLIPSIS + text.substring(slash)

        if (fits(candidate)) return candidate

        slash = text.indexOf('/', slash + 1)
    }

    // Even the last folder is too long: keep as many of its last characters as fit
    var low = 0
    var high = text.length

    while (low < high) {
        val middle = (low + high) / 2

        if (fits(ELLIPSIS + text.substring(middle))) high = middle else low = middle + 1
    }

    return ELLIPSIS + text.substring(low)
}
