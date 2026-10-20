// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.pollSystemTheme
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs

/**
 * Makes `isSystemInDarkTheme()` follow the operating system's light or dark setting on Linux too, which the System
 * theme needs to switch while Leaf is open.
 *
 * Compose Desktop 1.12.1 learns of a change only by asking the system once a second (`pollSystemTheme`), and ships
 * that on for macOS and Windows and off for Linux, where it then reads the setting once at startup. Call it before the
 * first window. It changes nothing on macOS and Windows, which keep Compose's default.
 *
 * Not tried on a Linux desktop: it depends on what skiko's Linux detection reads, and it reports a setting it can't
 * read as light.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun followSystemThemeOnLinux(os: OS = currentOs) {
    if (os == OS.LINUX) {
        ComposeUiFlags.pollSystemTheme = true
    }
}
