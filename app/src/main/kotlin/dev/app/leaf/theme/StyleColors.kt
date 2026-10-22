// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.foundation.border
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.AccentStyle

/**
 * The colours of one kind of button (fork-only): its fill, its text and icons, and a 1 dp line inside its edge, which
 * is transparent where the style has none.
 */
@Immutable
data class RoleColors(
    val background: Color,
    val content: Color,
    val border: Color = Color.Transparent,
)

/**
 * A strip across a view, or a notice inside one (fork-only): its fill, its text, its icon, a 1 dp line along its edge
 * (transparent for none), and the buttons on it that aren't destructive.
 */
@Immutable
data class StripColors(
    val background: Color,
    val content: Color,
    val icon: Color,
    val border: Color,
    val button: RoleColors,
)

/**
 * What buttons and notices are drawn with, by what they do rather than by colour (fork-only). Each look ([AccentStyle])
 * has its own values, which a style's colours are worked out from (`docs/fork/custom-styles.md`).
 */
@Immutable
data class StyleColors(
    /** Commit, Merge, Continue, and a dialog's confirm button: the one filled button of a pane, in every style. */
    val mainAction: RoleColors,
    /** Stage a file, a hunk, a line or all of them, and other buttons that move work on and lose nothing. */
    val action: RoleColors,
    /** Unstage, which loses nothing. Classic has it red, as Leaf always had. */
    val neutral: RoleColors,
    /** Discard, Abort, Delete. */
    val destructive: RoleColors,
    /** The operation banner, the index banner, the notices and the resolver's counter: stop and look. */
    val warningStrip: StripColors,
    /** File history's strip after a version was restored. */
    val successStrip: StripColors,
    /** A conflicted file's icon, and the icons of the hints that say what blocks a button, on the plain background. */
    val warningIcon: Color,
)

/** The muted blue of the tonal and quiet styles: the main action, and `primary` everywhere else. */
val MUTED_PRIMARY_LIGHT = Color(0xFF376AAA)
val MUTED_PRIMARY_DARK = Color(0xFF3464A0)

/** The near-black text and buttons that Classic puts on its amber and green strips, in both themes. */
private val NEAR_BLACK = Color(0xFF1E1E1E)

/**
 * The scheme that a built-in theme draws with in [style]: in Tonal and Quiet, `primary` (buttons' fill, toasts,
 * switches) is the muted blue, and so is the light theme's `primaryVariant` (links, the tab underline, text fields),
 * which is the same blue as its `primary`. The dark theme's `primaryVariant` is a pale blue for text on the dark
 * background, which the muted blue doesn't hold, so it stays. Classic is the scheme as it is.
 */
fun ColorsScheme.styled(style: AccentStyle): ColorsScheme = when (style) {
    AccentStyle.CLASSIC -> this
    AccentStyle.TONAL, AccentStyle.QUIET -> if (isLight) {
        copy(primary = MUTED_PRIMARY_LIGHT, primaryVariant = MUTED_PRIMARY_LIGHT)
    } else {
        copy(primary = MUTED_PRIMARY_DARK)
    }
}

/** The roles of [style] in a built-in theme, [scheme] being [styled] already. */
fun styleColorsFor(style: AccentStyle, scheme: ColorsScheme): StyleColors = when (style) {
    AccentStyle.CLASSIC -> classicStyleColors(scheme)
    AccentStyle.TONAL -> if (scheme.isLight) tonalLight else tonalDark
    AccentStyle.QUIET -> if (scheme.isLight) quietLight else quietDark
}

/** The roles as Leaf drew them before the styles: from the scheme's own colours. */
fun classicStyleColors(scheme: ColorsScheme): StyleColors = classicStyleColors(
    primary = scheme.primary,
    onPrimary = scheme.onPrimary,
    error = scheme.error,
    onError = scheme.onError,
    conflict = scheme.conflictingFile,
    added = scheme.addFile,
)

private fun classicStyleColors(
    primary: Color,
    onPrimary: Color,
    error: Color,
    onError: Color,
    conflict: Color,
    added: Color,
): StyleColors {
    val onStrip = RoleColors(NEAR_BLACK, Color.White)

    return StyleColors(
        mainAction = RoleColors(primary, onPrimary),
        action = RoleColors(primary, onPrimary),
        neutral = RoleColors(error, onError),
        destructive = RoleColors(error, onError),
        warningStrip = StripColors(conflict, NEAR_BLACK, NEAR_BLACK, Color.Transparent, onStrip),
        successStrip = StripColors(added, NEAR_BLACK, NEAR_BLACK, Color.Transparent, onStrip),
        warningIcon = conflict,
    )
}

// The values below are options 1d (Tonal) and 1f (Quiet) of the "Leaf Colours" board, chosen on 2026-10-21

private val tonalLight = StyleColors(
    mainAction = RoleColors(MUTED_PRIMARY_LIGHT, Color.White),
    action = RoleColors(Color(0xFFDDEBFE), Color(0xFF255691), Color(0xFF255691).copy(alpha = 0.28f)),
    neutral = RoleColors(Color.White, Color(0xFF212934), Color.Black.copy(alpha = 0.18f)),
    destructive = RoleColors(Color(0xFFFCE1DF), Color(0xFF963635), Color(0xFF963635).copy(alpha = 0.28f)),
    warningStrip = StripColors(
        background = Color(0xFFFEEFCF),
        content = Color(0xFF643D17),
        icon = Color(0xFFC68128),
        border = Color(0xFFEDC889),
        button = RoleColors(Color(0xFFF7D499), Color(0xFF573312), Color(0xFF573312).copy(alpha = 0.25f)),
    ),
    successStrip = StripColors(
        background = Color(0xFFDBF3DB),
        content = Color(0xFF245427),
        icon = Color(0xFF245427),
        border = Color.Transparent,
        button = RoleColors(Color(0xFFB6E2B6), Color(0xFF214E24), Color(0xFF214E24).copy(alpha = 0.25f)),
    ),
    warningIcon = Color(0xFFC68128),
)

private val tonalDark = StyleColors(
    mainAction = RoleColors(MUTED_PRIMARY_DARK, Color.White),
    action = RoleColors(Color(0xFF23364F), Color(0xFFAFD1FC), Color(0xFFAFD1FC).copy(alpha = 0.24f)),
    neutral = RoleColors(Color.White.copy(alpha = 0.08f), Color.White, Color.White.copy(alpha = 0.18f)),
    destructive = RoleColors(Color(0xFF4C2B2A), Color(0xFFF7B3B0), Color(0xFFF7B3B0).copy(alpha = 0.24f)),
    warningStrip = StripColors(
        background = Color(0xFF3E2D15),
        content = Color(0xFFF0DCB1),
        icon = Color(0xFFE6B463),
        border = Color(0xFF6C4F25),
        button = RoleColors(Color(0xFF65461D), Color(0xFFFAEED1), Color(0xFFFAEED1).copy(alpha = 0.2f)),
    ),
    successStrip = StripColors(
        background = Color(0xFF213622),
        content = Color(0xFFBBE4BC),
        icon = Color(0xFFBBE4BC),
        border = Color.Transparent,
        button = RoleColors(Color(0xFF365637), Color(0xFFE1F5E0), Color(0xFFE1F5E0).copy(alpha = 0.2f)),
    ),
    warningIcon = Color(0xFFE6B463),
)

private val quietNeutralLight = RoleColors(Color.White, Color(0xFF212934), Color.Black.copy(alpha = 0.18f))

private val quietLight = StyleColors(
    mainAction = RoleColors(MUTED_PRIMARY_LIGHT, Color.White),
    action = quietNeutralLight,
    neutral = quietNeutralLight,
    destructive = quietNeutralLight.copy(content = Color(0xFFA43B3A)),
    warningStrip = StripColors(
        background = Color(0xFFFDF2DD),
        content = Color(0xFF262F38),
        icon = Color(0xFFC68128),
        border = Color(0xFFEDC889),
        button = quietNeutralLight,
    ),
    successStrip = StripColors(
        background = Color(0xFFEAF8E9),
        content = Color(0xFF212934),
        icon = Color(0xFF212934),
        border = Color.Transparent,
        button = quietNeutralLight,
    ),
    warningIcon = Color(0xFFC68128),
)

private val quietNeutralDark = RoleColors(Color.White.copy(alpha = 0.08f), Color.White, Color.White.copy(alpha = 0.18f))

private val quietDark = StyleColors(
    mainAction = RoleColors(MUTED_PRIMARY_DARK, Color.White),
    action = quietNeutralDark,
    neutral = quietNeutralDark,
    destructive = quietNeutralDark.copy(content = Color(0xFFED8B86)),
    warningStrip = StripColors(
        background = Color(0xFF2F2416),
        content = Color.White,
        icon = Color(0xFFE6B463),
        border = Color(0xFF6C4F25),
        button = quietNeutralDark,
    ),
    successStrip = StripColors(
        background = Color(0xFF1D2A1D),
        content = Color.White,
        icon = Color.White,
        border = Color.Transparent,
        button = quietNeutralDark,
    ),
    warningIcon = Color(0xFFE6B463),
)

/** Provided by `AppTheme`. Null outside it, where [styleColors] falls back to the Material colours, laid out as Classic. */
internal val LocalStyleColors = staticCompositionLocalOf<StyleColors?> { null }

val MaterialTheme.styleColors: StyleColors
    @Composable
    get() = LocalStyleColors.current ?: classicStyleColors(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        error = colors.error,
        onError = colors.onError,
        conflict = colors.conflictFile,
        added = colors.addFile,
    )

/** A 1 dp line inside the edge of a button or a notice, or nothing for a transparent [color]. */
fun Modifier.roleBorder(color: Color, shape: Shape): Modifier =
    if (color.alpha > 0f) border(1.dp, color, shape) else this

/** A 1 dp line along the bottom of a strip, inside it, or nothing for a transparent [color]. */
fun Modifier.bottomEdge(color: Color): Modifier =
    if (color.alpha > 0f) {
        drawBehind {
            val height = 1.dp.toPx()
            drawRect(color, topLeft = Offset(0f, size.height - height), size = Size(size.width, height))
        }
    } else {
        this
    }
