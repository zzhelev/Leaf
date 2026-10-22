// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.models.ui.resolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ThemeResolutionTest {
    @Test
    fun `System is dark when the system is dark and light when it isn't`() {
        assertEquals(Theme.Dark, Theme.System.resolved(systemIsDark = true))
        assertEquals(Theme.Light, Theme.System.resolved(systemIsDark = false))
    }

    @Test
    fun `the other themes ignore the system`() {
        for (systemIsDark in listOf(true, false)) {
            assertEquals(Theme.Light, Theme.Light.resolved(systemIsDark))
            assertEquals(Theme.Dark, Theme.Dark.resolved(systemIsDark))
        }
    }

    @Test
    fun `no theme resolves to System`() {
        for (theme in Theme.entries) {
            for (systemIsDark in listOf(true, false)) {
                assertNotEquals(Theme.System, theme.resolved(systemIsDark), "$theme, system dark: $systemIsDark")
            }
        }
    }

    @Test
    fun `the themes are Light, Dark and System, as Custom gave way to styles`() {
        assertEquals(listOf(Theme.Light, Theme.Dark, Theme.System), Theme.entries)
    }
}
