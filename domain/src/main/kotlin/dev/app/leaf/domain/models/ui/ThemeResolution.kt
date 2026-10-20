// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

/**
 * The theme to draw with: [Theme.System] becomes [Theme.Dark] or [Theme.Light] by whether the operating system is set
 * to dark, and the other themes stay as they are. The result is never [Theme.System].
 *
 * A system whose setting can't be read counts as light, as `isSystemInDarkTheme()` does.
 */
fun Theme.resolved(systemIsDark: Boolean): Theme = when (this) {
    Theme.System -> if (systemIsDark) Theme.Dark else Theme.Light
    Theme.Light, Theme.Dark, Theme.Custom -> this
}
