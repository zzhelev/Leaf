// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.Theme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What `AppTheme` draws with the System theme, as the system's light or dark setting changes (fork-only). */
class AppThemeSystemTest {
    /** What the theme gives inside `AppTheme`: the Material colours and the ones from the app's own palette. */
    private data class Drawn(val isLight: Boolean, val background: Color, val selected: Color, val addFile: Color)

    private class Harness(selected: Theme, systemIsDark: Boolean) {
        var systemIsDark by mutableStateOf(systemIsDark)
        var selected by mutableStateOf(selected)
        var drawn: Drawn? = null

        val scene = ImageComposeScene(width = 80, height = 40, density = Density(1f)) {
            AppTheme(selectedTheme = this@Harness.selected, systemIsDark = this@Harness.systemIsDark) {
                drawn = Drawn(
                    isLight = MaterialTheme.colors.isLight,
                    background = MaterialTheme.colors.background,
                    selected = MaterialTheme.colors.backgroundSelected,
                    addFile = MaterialTheme.colors.addFile,
                )
            }
        }

        fun frame(): Drawn {
            scene.render()
            return checkNotNull(drawn)
        }
    }

    private fun palette(scheme: ColorsScheme) =
        Drawn(scheme.isLight, scheme.background, scheme.backgroundSelected, scheme.addFile)

    private val light = palette(lightTheme)
    private val dark = palette(darkTheme)

    private fun <T> withHarness(selected: Theme, systemIsDark: Boolean, test: Harness.() -> T): T {
        val harness = Harness(selected, systemIsDark)

        try {
            return harness.test()
        } finally {
            harness.scene.close()
        }
    }

    @Test
    fun `System draws the palette of the system's setting`() {
        withHarness(Theme.System, systemIsDark = true) { assertEquals(dark, frame()) }
        withHarness(Theme.System, systemIsDark = false) { assertEquals(light, frame()) }
    }

    @Test
    fun `System switches palette as the system's setting changes, and back`() {
        withHarness(Theme.System, systemIsDark = false) {
            assertEquals(light, frame())

            systemIsDark = true
            assertEquals(dark, frame())

            systemIsDark = false
            assertEquals(light, frame())
        }
    }

    @Test
    fun `the palette that the rest of the app reads follows too, not only the Material colours`() {
        withHarness(Theme.System, systemIsDark = false) {
            assertEquals(lightTheme.backgroundSelected, frame().selected)

            systemIsDark = true
            assertEquals(darkTheme.backgroundSelected, frame().selected)
        }
    }

    @Test
    fun `Light and Dark ignore the system's setting`() {
        for (systemIsDark in listOf(true, false)) {
            withHarness(Theme.Light, systemIsDark) { assertEquals(light, frame()) }
            withHarness(Theme.Dark, systemIsDark) { assertEquals(dark, frame()) }
        }
    }

    @Test
    fun `changing the choice to System in the settings takes the system's setting at once`() {
        withHarness(Theme.Light, systemIsDark = true) {
            assertEquals(light, frame())

            selected = Theme.System
            assertEquals(dark, frame())

            selected = Theme.Light
            assertEquals(light, frame())
        }
    }

    @Test
    fun `the drop-down offers every theme once, System included, and Custom no more`() {
        assertEquals(listOf(Theme.Light, Theme.Dark, Theme.System), themeLists.map { it.value })
        assertEquals(Theme.entries.toSet(), themeLists.map { it.value }.toSet())
        assertEquals(Theme.entries.size, themeLists.size)
        assertEquals("System", themeLists.single { it.value == Theme.System }.optionName)
    }
}
