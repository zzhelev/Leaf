// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The commits selected in the log with Shift (fork-only, step A2 of `docs/fork/squash-and-rebase.md`), which the rows
 * draw as selected and whose menu is the range's. Empty while there's no range. Given by `Log`, so that upstream's
 * composables between it and the rows don't change.
 */
val LocalSelectedCommits = compositionLocalOf { emptySet<String>() }

/**
 * How far Shift+↑ (-1) and Shift+↓ (1) move a range's lead in the log, or null for any other key. With Ctrl, Cmd or Alt
 * too, they're other shortcuts.
 */
fun KeyEvent.logSelectionStep(): Int? {
    if (type != KeyEventType.KeyDown || !isShiftPressed || isAltPressed || isCtrlPressed || isMetaPressed) return null

    return when (key) {
        Key.DirectionUp -> -1
        Key.DirectionDown -> 1
        else -> null
    }
}
