// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Keeps the pointer in this element's bounds from what is drawn under it.
 *
 * Compose hit-tests the sibling below when nothing in the top one handles the pointer there. The diff pane is drawn
 * over the log, so a click on an empty part of its header selected a log row under it, and hovering showed that row's
 * tooltips. This takes part in the hit test without consuming anything, so what is inside gets its events as before.
 */
fun Modifier.coveringWhatIsBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
        }
    }
}
