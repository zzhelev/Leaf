// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import dev.app.leaf.domain.window.SavedWindow
import dev.app.leaf.domain.window.savedWindowOf
import java.util.prefs.Preferences

// Fork-only: where the window was when Leaf last ran, next to the pane widths in the prefs node
internal const val PREF_WINDOW_MODE = "windowPlacement"
internal const val PREF_WINDOW_X = "windowX"
internal const val PREF_WINDOW_Y = "windowY"
internal const val PREF_WINDOW_WIDTH = "windowWidth"
internal const val PREF_WINDOW_HEIGHT = "windowHeight"

/** The window that [writeSavedWindow] kept, or null; values that can't be read are left out (see [savedWindowOf]). */
fun Preferences.readSavedWindow(): SavedWindow? = savedWindowOf(
    mode = get(PREF_WINDOW_MODE, null),
    x = intOrNull(PREF_WINDOW_X),
    y = intOrNull(PREF_WINDOW_Y),
    width = intOrNull(PREF_WINDOW_WIDTH),
    height = intOrNull(PREF_WINDOW_HEIGHT),
)

/** Keeps [window], or takes the keys out for null. Bounds that a window doesn't have yet are taken out. */
fun Preferences.writeSavedWindow(window: SavedWindow?) {
    if (window == null) {
        remove(PREF_WINDOW_MODE)
    } else {
        put(PREF_WINDOW_MODE, window.mode.storedName)
    }

    val floating = window?.floating

    if (floating == null) {
        remove(PREF_WINDOW_X)
        remove(PREF_WINDOW_Y)
        remove(PREF_WINDOW_WIDTH)
        remove(PREF_WINDOW_HEIGHT)
    } else {
        putInt(PREF_WINDOW_X, floating.x)
        putInt(PREF_WINDOW_Y, floating.y)
        putInt(PREF_WINDOW_WIDTH, floating.width)
        putInt(PREF_WINDOW_HEIGHT, floating.height)
    }
}

private fun Preferences.intOrNull(key: String): Int? = get(key, null)?.trim()?.toIntOrNull()
