// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.models.ui.AccentStyle

/**
 * The styles that come with Leaf (fork-only). They can be duplicated, not changed. Their ids are what `accent_style`
 * stored before styles could be made, so a choice made then still reads.
 *
 * Classic, Tonal and Quiet set nothing by hand: their seeds are the ones their look's colours are built around, so
 * Leaf's working out gives the look's colours exactly, and a copy whose Accent changes moves every colour that follows
 * it.
 */
object BuiltInStyles {
    const val TONAL_ID = "tonal"
    const val CLASSIC_ID = "classic"
    const val QUIET_ID = "quiet"

    /** The style for no choice, and for a choice that can't be read. */
    const val DEFAULT_ID = TONAL_ID

    // The seeds come first, as the styles below are made from them when the object starts. Classic's are Leaf's look
    // until 2026-10-21: its blue, its red, the conflict amber and the added files' green
    private val CLASSIC_LIGHT_SEEDS = StyleSeeds(
        background = 0xFFFFFFFF.toInt(),
        accent = 0xFF0070D8.toInt(),
        danger = 0xFFC93838.toInt(),
        warning = 0xFFFFB638.toInt(),
        success = 0xFF32A852.toInt(),
    )

    private val CLASSIC_DARK_SEEDS = CLASSIC_LIGHT_SEEDS.copy(
        background = 0xFF0F0F0F.toInt(),
        accent = 0xFF0169CB.toInt(),
    )

    // Tonal and Quiet: the muted blue of their main action and the amber of their warning icon
    private val TONAL_LIGHT_SEEDS = CLASSIC_LIGHT_SEEDS.copy(
        accent = 0xFF376AAA.toInt(),
        warning = 0xFFC68128.toInt(),
    )

    private val TONAL_DARK_SEEDS = CLASSIC_DARK_SEEDS.copy(
        accent = 0xFF3464A0.toInt(),
        warning = 0xFFE6B463.toInt(),
    )

    /** The seeds that [look]'s light or dark colours are built around. */
    fun templateSeeds(look: AccentStyle, isLight: Boolean): StyleSeeds = when (look) {
        AccentStyle.CLASSIC -> if (isLight) CLASSIC_LIGHT_SEEDS else CLASSIC_DARK_SEEDS
        AccentStyle.TONAL, AccentStyle.QUIET -> if (isLight) TONAL_LIGHT_SEEDS else TONAL_DARK_SEEDS
    }

    val TONAL = builtIn(TONAL_ID, "Tonal", AccentStyle.TONAL)
    val CLASSIC = builtIn(CLASSIC_ID, "Classic", AccentStyle.CLASSIC)
    val QUIET = builtIn(QUIET_ID, "Quiet", AccentStyle.QUIET)

    /** In the order Settings lists them. */
    val ALL: List<StyleDefinition> = listOf(TONAL, CLASSIC, QUIET)

    val DEFAULT: StyleDefinition get() = TONAL

    fun byId(id: String?): StyleDefinition? = ALL.firstOrNull { it.id == id }

    fun isBuiltIn(id: String): Boolean = ALL.any { it.id == id }

    /** The built-in style whose look is [look], for code that only knows a look. */
    fun ofLook(look: AccentStyle): StyleDefinition = when (look) {
        AccentStyle.TONAL -> TONAL
        AccentStyle.CLASSIC -> CLASSIC
        AccentStyle.QUIET -> QUIET
    }

    private fun builtIn(id: String, name: String, look: AccentStyle) = StyleDefinition(
        id = id,
        name = name,
        look = look,
        light = StyleSide(templateSeeds(look, isLight = true)),
        dark = StyleSide(templateSeeds(look, isLight = false)),
    )
}
