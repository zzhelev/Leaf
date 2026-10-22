// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.models.ui.AccentStyle

/** The colours that Leaf works a style's other colours out from, one set per side (fork-only). [key] names it in files. */
enum class StyleSeed(val key: String) {
    BACKGROUND("background"),

    /** Commit, Stage, links. */
    ACCENT("accent"),

    /** Discard, Abort, Delete, removed lines. */
    DANGER("danger"),

    /** Banners, notices, conflicts. */
    WARNING("warning"),

    /** The restored strip, added lines. */
    SUCCESS("success");

    companion object {
        fun fromKey(key: String): StyleSeed? = entries.firstOrNull { it.key == key }
    }
}

/** One side's seed colours, as ARGB. */
data class StyleSeeds(
    val background: Int,
    val accent: Int,
    val danger: Int,
    val warning: Int,
    val success: Int,
) {
    operator fun get(seed: StyleSeed): Int = when (seed) {
        StyleSeed.BACKGROUND -> background
        StyleSeed.ACCENT -> accent
        StyleSeed.DANGER -> danger
        StyleSeed.WARNING -> warning
        StyleSeed.SUCCESS -> success
    }

    fun with(seed: StyleSeed, argb: Int): StyleSeeds = when (seed) {
        StyleSeed.BACKGROUND -> copy(background = argb)
        StyleSeed.ACCENT -> copy(accent = argb)
        StyleSeed.DANGER -> copy(danger = argb)
        StyleSeed.WARNING -> copy(warning = argb)
        StyleSeed.SUCCESS -> copy(success = argb)
    }
}

/**
 * One side of a style, light or dark: its [seeds], and the colours that were [setByHand], as ARGB, which Leaf uses as
 * they are, instead of working them out.
 */
data class StyleSide(
    val seeds: StyleSeeds,
    val setByHand: Map<StyleSlot, Int> = emptyMap(),
)

/**
 * A style (fork-only, Settings → Appearance → Style): every colour Leaf draws with, for the light and the dark theme.
 * [look] is how buttons and notices are drawn, the template that the other colours are worked out from. [id] is the
 * built-in style's (see [BuiltInStyles]) or the name of the style's file without `.json`; it isn't written in the file.
 */
data class StyleDefinition(
    val id: String,
    val name: String,
    val look: AccentStyle,
    val light: StyleSide,
    val dark: StyleSide,
) {
    fun side(isLight: Boolean): StyleSide = if (isLight) light else dark

    fun withSide(isLight: Boolean, side: StyleSide): StyleDefinition = if (isLight) copy(light = side) else copy(dark = side)

    val isBuiltIn: Boolean get() = BuiltInStyles.isBuiltIn(id)
}
