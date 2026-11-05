// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A line of Inter, the app's font, is about this many times its font size. */
private const val LINE_HEIGHT_PER_FONT_SIZE = 1.25f

/**
 * Fork-only: how much taller a line of [fontSize] text is at the text size than at the default (Settings → Appearance →
 * Text size), which a box that holds text at a fixed height adds to that height. Smaller text gives nothing back: the
 * box keeps its height, as its icons and buttons do.
 */
fun textGrowth(fontScale: Float, fontSize: TextUnit = 13.sp, lines: Int = 1): Dp =
    ((fontScale - 1f).coerceAtLeast(0f) * fontSize.value * LINE_HEIGHT_PER_FONT_SIZE * lines).dp

/** [textGrowth] at the text size that this composition has. */
@Composable
@ReadOnlyComposable
fun textGrowth(fontSize: TextUnit = 13.sp, lines: Int = 1): Dp =
    textGrowth(LocalDensity.current.fontScale, fontSize, lines)

/** This height, grown by what the text size adds to [lines] lines of [fontSize] text. */
@Composable
@ReadOnlyComposable
fun Dp.withText(fontSize: TextUnit = 13.sp, lines: Int = 1): Dp = this + textGrowth(fontSize, lines)

/**
 * This width, wider by what the text size adds to [textPart] of it, the part that its text takes at the default size.
 * Smaller text gives nothing back.
 */
@Composable
@ReadOnlyComposable
fun Dp.widenedForText(textPart: Dp = this): Dp =
    this + textPart * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
