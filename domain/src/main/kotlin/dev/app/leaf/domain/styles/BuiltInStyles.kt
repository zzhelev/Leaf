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
 * it. Solarized and Teal are worked out in the Tonal look from their own seeds, with some colours set by hand.
 */
object BuiltInStyles {
    const val TONAL_ID = "tonal"
    const val CLASSIC_ID = "classic"
    const val QUIET_ID = "quiet"
    const val SOLARIZED_ID = "solarized"
    const val TEAL_ID = "teal"

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

    // Ethan Schoonover's Solarized (ethanschoonover.com/solarized): base3 and base03 as the backgrounds, and its blue,
    // red, yellow and green as the seeds
    private val SOLARIZED_LIGHT_SEEDS = StyleSeeds(
        background = 0xFFFDF6E3.toInt(),
        accent = 0xFF268BD2.toInt(),
        danger = 0xFFDC322F.toInt(),
        warning = 0xFFB58900.toInt(),
        success = 0xFF859900.toInt(),
    )

    private val SOLARIZED_DARK_SEEDS = SOLARIZED_LIGHT_SEEDS.copy(background = 0xFF002B36.toInt())

    // Teal: the looks' white and near-black backgrounds, a teal accent, a raspberry danger, an amber warning and a green
    // success
    private val TEAL_LIGHT_SEEDS = StyleSeeds(
        background = 0xFFFFFFFF.toInt(),
        accent = 0xFF007475.toInt(),
        danger = 0xFFB4405A.toInt(),
        warning = 0xFFE0A03A.toInt(),
        success = 0xFF3F8F52.toInt(),
    )

    private val TEAL_DARK_SEEDS = TEAL_LIGHT_SEEDS.copy(background = 0xFF0F0F0F.toInt())

    /**
     * Solarized's own values where they say what a colour is and hold Leaf's checks: its emphasised content tone as
     * text (base01, base1), its body tone for comments (base00, base0), and its accents for the keyword, annotations
     * and the lanes. On the dark side its highlight tone base02 is the panels. On the light side base2 isn't: base01
     * holds 4.39 : 1 on it, so the panels are worked out. Secondary text is the "Style Library" board's: a shade of
     * base01 on the light side, and on the dark side base0 lifted until it holds 4.5 : 1 on base02 (base0 itself
     * holds 4.11).
     */
    private fun solarizedByHand(isLight: Boolean): Map<StyleSlot, Int> = buildMap {
        if (isLight) {
            put(StyleSlot.TEXT, 0xFF586E75.toInt())
            put(StyleSlot.SECONDARY_TEXT, 0xFF516770.toInt())
            put(StyleSlot.COMMENT, 0xFF657B83.toInt())
        } else {
            put(StyleSlot.PANELS, 0xFF073642.toInt())
            put(StyleSlot.TEXT, 0xFF93A1A1.toInt())
            put(StyleSlot.SECONDARY_TEXT, 0xFF8A9C9E.toInt())
            put(StyleSlot.COMMENT, 0xFF839496.toInt())
        }
        put(StyleSlot.KEYWORD, 0xFF268BD2.toInt())
        put(StyleSlot.ANNOTATION, 0xFFB58900.toInt())

        // Blue, red, cyan, orange, green and magenta, the hues of Leaf's own lanes
        val lanes = listOf(0xFF268BD2, 0xFFDC322F, 0xFF2AA198, 0xFFCB4B16, 0xFF859900, 0xFFD33682)
        StyleSlot.LANES.zip(lanes).forEach { (slot, argb) -> put(slot, argb.toInt()) }
    }

    /**
     * Teal's colours set by hand: a text tinted with its hue on the light side, and on the dark side the headers and
     * boxes, which follow no seed and would stay Tonal's navy. They're Tonal's turned to Teal's hue: #0A335C at the most
     * chroma sRGB has at its lightness, and #1A2B3D. Lanes 2 to 6 are set apart from lane 1's teal and from each other:
     * following the seeds, lane 2 would be a rose next to lane 6's pink.
     */
    private fun tealByHand(isLight: Boolean): Map<StyleSlot, Int> = buildMap {
        if (isLight) {
            put(StyleSlot.TEXT, 0xFF1D2B2B.toInt())
        } else {
            put(StyleSlot.HEADERS, 0xFF003B3A.toInt())
            put(StyleSlot.BOXES, 0xFF152F2D.toInt())
        }

        // Orange, blue, pink, green and violet, in the lightness of Tonal's lanes
        val lanes = listOf(0xFFC87D52, 0xFF5B97D3, 0xFFC4759C, 0xFF60A56B, 0xFF9D82CC)
        StyleSlot.LANES.drop(1).zip(lanes).forEach { (slot, argb) -> put(slot, argb.toInt()) }
    }

    /** The seeds that [look]'s light or dark colours are built around. */
    fun templateSeeds(look: AccentStyle, isLight: Boolean): StyleSeeds = when (look) {
        AccentStyle.CLASSIC -> if (isLight) CLASSIC_LIGHT_SEEDS else CLASSIC_DARK_SEEDS
        AccentStyle.TONAL, AccentStyle.QUIET -> if (isLight) TONAL_LIGHT_SEEDS else TONAL_DARK_SEEDS
    }

    val TONAL = builtIn(TONAL_ID, "Tonal", AccentStyle.TONAL)
    val CLASSIC = builtIn(CLASSIC_ID, "Classic", AccentStyle.CLASSIC)
    val QUIET = builtIn(QUIET_ID, "Quiet", AccentStyle.QUIET)

    /** Solarized in the Tonal look: its seeds, and its published values set by hand where they hold Leaf's checks. */
    val SOLARIZED = StyleDefinition(
        id = SOLARIZED_ID,
        name = "Solarized",
        look = AccentStyle.TONAL,
        light = StyleSide(SOLARIZED_LIGHT_SEEDS, solarizedByHand(isLight = true)),
        dark = StyleSide(SOLARIZED_DARK_SEEDS, solarizedByHand(isLight = false)),
    )

    /** Teal in the Tonal look: its seeds, a teal-tinted text, and its dark headers and lanes set by hand. */
    val TEAL = StyleDefinition(
        id = TEAL_ID,
        name = "Teal",
        look = AccentStyle.TONAL,
        light = StyleSide(TEAL_LIGHT_SEEDS, tealByHand(isLight = true)),
        dark = StyleSide(TEAL_DARK_SEEDS, tealByHand(isLight = false)),
    )

    /** In the order Settings lists them. */
    val ALL: List<StyleDefinition> = listOf(TONAL, CLASSIC, QUIET, SOLARIZED, TEAL)

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
