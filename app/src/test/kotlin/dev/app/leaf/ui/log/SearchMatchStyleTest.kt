// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.ColorsScheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.lightTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

private const val WIDTH = 100
private const val HEIGHT = 40

/** The dim of the rows that don't match the log search, and the outline of the one that is focused (fork-only). */
@OptIn(ExperimentalComposeUiApi::class)
class SearchMatchStyleTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun bitmap(theme: Theme, content: @Composable () -> Unit): Bitmap {
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme(theme) { content() }
        }

        scene!!.render(System.nanoTime())

        return Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
    }

    /** A block of the text colour on the background, as a row's text is, dimmed as the row would be. */
    private fun dimmed(theme: Theme, matchesSearchFilter: Boolean?): Int = bitmap(theme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dimmedWhenNotMatching(matchesSearchFilter)
                    .background(MaterialTheme.colors.onBackground)
            )
        }
    }.getColor(WIDTH / 2, HEIGHT / 2)

    private fun blended(scheme: ColorsScheme, alpha: Float): Int {
        fun channel(foreground: Float, background: Float) = ((foreground * alpha + background * (1 - alpha)) * 255).roundToInt()

        return Color(
            channel(scheme.onBackground.red, scheme.background.red),
            channel(scheme.onBackground.green, scheme.background.green),
            channel(scheme.onBackground.blue, scheme.background.blue),
        ).toArgb()
    }

    private fun assertSameColor(expected: Int, actual: Int, message: String) {
        for (shift in listOf(16, 8, 0)) {
            val difference = abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))

            assertTrue(difference <= 2, "$message: expected #${expected.toUInt().toString(16)}, was #${actual.toUInt().toString(16)}")
        }
    }

    @Test
    fun `a non-matching row is left at 45 percent in the dark theme`() {
        assertSameColor(blended(darkTheme, 0.45f), dimmed(Theme.Dark, matchesSearchFilter = false), "dark")
    }

    @Test
    fun `a non-matching row is left at 55 percent in the light theme`() {
        assertSameColor(blended(lightTheme, 0.55f), dimmed(Theme.Light, matchesSearchFilter = false), "light")
    }

    @Test
    fun `a matching row and a row of a log that isn't searched are not dimmed`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            assertSameColor(scheme.onBackground.toArgb(), dimmed(theme, matchesSearchFilter = true), "$theme match")
            assertSameColor(scheme.onBackground.toArgb(), dimmed(theme, matchesSearchFilter = null), "$theme not searched")
        }
    }

    @Test
    fun `the dim is stronger than the old text colour in both themes`() {
        // The old dim was the text colour onBackgroundSecondary: 11.8:1 in the dark theme, 7.1:1 in the light one
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val old = scheme.onBackgroundSecondary.toArgb()
            val new = dimmed(theme, matchesSearchFilter = false)
            val background = scheme.background.toArgb()

            assertTrue(
                distance(new, background) < distance(old, background),
                "$theme: the dimmed text must be closer to the background than the old dim",
            )
        }
    }

    private fun distance(first: Int, second: Int): Int =
        listOf(16, 8, 0).sumOf { abs(((first shr it) and 0xFF) - ((second shr it) and 0xFF)) }

    /** A row-sized box with the outline, over [fill] when there is a selection. */
    private fun outlined(theme: Theme, isFocusedMatch: Boolean, fill: Boolean = false): Bitmap = bitmap(theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .then(if (fill) Modifier.background(MaterialTheme.colors.backgroundSelected) else Modifier)
                .outlinedWhenFocusedMatch(isFocusedMatch)
        )
    }

    @Test
    fun `the focused match has an outline in the colour of the matches' bar, in both themes`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val bitmap = outlined(theme, isFocusedMatch = true)

            assertSameColor(scheme.secondary.toArgb(), bitmap.getColor(1, HEIGHT / 2), "$theme left edge")
            assertSameColor(scheme.secondary.toArgb(), bitmap.getColor(WIDTH - 2, HEIGHT / 2), "$theme right edge")
            assertSameColor(scheme.secondary.toArgb(), bitmap.getColor(WIDTH / 2, 1), "$theme top edge")
            assertSameColor(scheme.secondary.toArgb(), bitmap.getColor(WIDTH / 2, HEIGHT - 2), "$theme bottom edge")
            assertEquals(scheme.background.toArgb(), bitmap.getColor(WIDTH / 2, HEIGHT / 2), "$theme inside")
        }
    }

    @Test
    fun `a row that isn't the focused match has no outline`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val bitmap = outlined(theme, isFocusedMatch = false)

            assertEquals(scheme.background.toArgb(), bitmap.getColor(1, HEIGHT / 2), "$theme left edge")
            assertEquals(scheme.background.toArgb(), bitmap.getColor(WIDTH / 2, 1), "$theme top edge")
        }
    }

    @Test
    fun `the outline sits on the selection's fill without hiding it`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val plain = outlined(theme, isFocusedMatch = false, fill = true).getColor(WIDTH / 2, HEIGHT / 2)
            val bitmap = outlined(theme, isFocusedMatch = true, fill = true)

            assertSameColor(scheme.secondary.toArgb(), bitmap.getColor(1, HEIGHT / 2), "$theme edge")
            assertEquals(plain, bitmap.getColor(WIDTH / 2, HEIGHT / 2), "$theme the fill inside is the selection's")
            assertTrue(plain != scheme.background.toArgb(), "$theme the selection's fill differs from the background")
        }
    }
}
