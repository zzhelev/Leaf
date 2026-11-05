// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.app.leaf.domain.models.ui.AccentStyle

/**
 * The colours of the window's frame (fork-only, docs/fork/window-chrome.md): the title bar with the tabs, the toolbar,
 * and the lines between the panes.
 */
@Immutable
data class FrameColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    /** Icons that carry the frame's colour, as the repository's. */
    val icon: Color,
    /** The selected tab, and the frame's buttons and fields. */
    val control: Color,
    val controlEdge: Color,
    val separator: Color,
)

private val frameControlEdgeLight = Color(0xFFD3D7DD)
private val frameControlEdgeDark = Color(0xFF3A3D43)
private val separatorLight = Color(0xFFE3E6EA)
private val separatorDark = Color(0xFF2B2D31)

/**
 * [look]'s frame, [scheme] being styled already. Classic keeps the colours that its toolbar and tabs had: the panels'
 * grey, with the background for the selected tab. Tonal and Quiet have mockup A's (the "Leaf window chrome review"
 * board): a frame a step darker than the panels in light and a step lighter in dark, with white or near-black controls.
 */
fun frameColorsFor(look: AccentStyle, scheme: ColorsScheme): FrameColors = when (look) {
    AccentStyle.CLASSIC -> FrameColors(
        background = scheme.surface,
        text = scheme.onBackground,
        secondaryText = scheme.onBackgroundSecondary,
        icon = scheme.primaryVariant,
        control = scheme.background,
        controlEdge = if (scheme.isLight) frameControlEdgeLight else frameControlEdgeDark,
        separator = if (scheme.isLight) separatorLight else separatorDark,
    )

    AccentStyle.TONAL, AccentStyle.QUIET -> if (scheme.isLight) {
        FrameColors(
            background = Color(0xFFF1F2F4),
            text = scheme.onBackground,
            secondaryText = Color(0xFF5B616B),
            icon = scheme.primaryVariant,
            control = Color(0xFFFFFFFF),
            controlEdge = frameControlEdgeLight,
            separator = separatorLight,
        )
    } else {
        FrameColors(
            background = Color(0xFF1D1E21),
            text = scheme.onBackground,
            secondaryText = Color(0xFFA3A8B0),
            icon = scheme.primaryVariant,
            control = Color(0xFF2A2C30),
            controlEdge = frameControlEdgeDark,
            separator = separatorDark,
        )
    }
}

/** Provided by `AppTheme`. Null outside it, where [frameColors] falls back to Classic's, from the Material colours. */
internal val LocalFrameColors = staticCompositionLocalOf<FrameColors?> { null }

val MaterialTheme.frameColors: FrameColors
    @Composable
    get() = LocalFrameColors.current ?: FrameColors(
        background = colors.surface,
        text = colors.onBackground,
        secondaryText = colors.onBackgroundSecondary,
        icon = colors.primaryVariant,
        control = colors.background,
        controlEdge = if (colors.isLight) frameControlEdgeLight else frameControlEdgeDark,
        separator = if (colors.isLight) separatorLight else separatorDark,
    )
