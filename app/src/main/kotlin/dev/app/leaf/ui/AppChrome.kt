// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the selected tab can hide of the window around it: the tab bar, which `App` draws above the tab, while the
 * conflict resolver is in Focus. Only the selected tab's content is composed, so the one that hid it shows it again
 * when it leaves the composition.
 */
@Stable
class AppChrome {
    var tabBarHidden by mutableStateOf(false)
}

val LocalAppChrome = staticCompositionLocalOf { AppChrome() }
