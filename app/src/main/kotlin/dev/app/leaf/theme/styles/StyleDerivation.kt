// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme.styles

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSeeds
import dev.app.leaf.domain.styles.StyleShifts
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.domain.styles.StyleSlot.*

/** WCAG's threshold for text, and for the labels of buttons. */
const val TEXT_CONTRAST = 4.5

/** WCAG's threshold for an icon that is all there is to tell what it means. */
const val ICON_CONTRAST = 3.0

/** How a colour of a look follows the style's seeds (fork-only). */
enum class Follow {
    /** It is the seed. */
    SEED,

    /** It keeps the look's lightness and takes the seed's hue and chroma ([StyleShifts.followHue]). */
    HUE,

    /** It moves with the background seed ([StyleShifts.followBackground]). */
    BACKGROUND,

    /** Text on the background: lightness kept, half the background's tint ([StyleShifts.followBackgroundAsText]). */
    TEXT,

    /** It stays the look's, whatever the seeds. */
    FIXED,
}

data class SlotRule(val follow: Follow, val seed: StyleSeed?)

private val fixed = SlotRule(Follow.FIXED, null)
private val onBackground = SlotRule(Follow.BACKGROUND, StyleSeed.BACKGROUND)
private val asText = SlotRule(Follow.TEXT, StyleSeed.BACKGROUND)
private fun hue(seed: StyleSeed) = SlotRule(Follow.HUE, seed)

/**
 * Which seed [slot] follows in [look], and how. Classic fills its buttons and strips and puts white or near-black on
 * them, which stay; Tonal tints everything in a seed's family; Quiet keeps buttons on the background, with only their
 * text, or a strip's fill, in the seed's colour.
 */
fun slotRule(slot: StyleSlot, look: AccentStyle, isLight: Boolean): SlotRule {
    val classic = look == AccentStyle.CLASSIC
    val tonal = look == AccentStyle.TONAL
    val quiet = look == AccentStyle.QUIET

    return when (slot) {
        BACKGROUND -> SlotRule(Follow.SEED, StyleSeed.BACKGROUND)
        PANELS, BOXES, HEADERS, SCROLLBAR -> onBackground
        SCROLLBAR_HOVER -> if (isLight) hue(StyleSeed.ACCENT) else onBackground
        TEXT, SECONDARY_TEXT -> asText
        DIALOG_OVERLAY, HIGHLIGHT, ON_HIGHLIGHT, ON_ERROR, MAIN_ACTION_TEXT, LANE_3, LANE_6 -> fixed

        SELECTED_ROW, LINKS, MODIFIED_FILE, KEYWORD, LANE_1, MAIN_ACTION_FILL -> hue(StyleSeed.ACCENT)
        ERROR, DELETED_FILE, REMOVED_LINE, REMOVED_WORDS, LANE_2 -> hue(StyleSeed.DANGER)
        CONFLICTED_FILE, ANNOTATION, LANE_4, WARNING_ICON, WARNING_STRIP_FILL -> hue(StyleSeed.WARNING)
        ADDED_FILE, ADDED_LINE, ADDED_WORDS, COMMENT, LANE_5, SUCCESS_STRIP_FILL -> hue(StyleSeed.SUCCESS)

        ACTION_FILL -> if (quiet) onBackground else hue(StyleSeed.ACCENT)
        ACTION_TEXT -> when {
            classic -> fixed
            tonal -> hue(StyleSeed.ACCENT)
            else -> asText
        }
        ACTION_EDGE -> if (tonal) hue(StyleSeed.ACCENT) else fixed

        NEUTRAL_FILL -> if (classic) hue(StyleSeed.DANGER) else onBackground
        NEUTRAL_TEXT -> if (classic) fixed else asText
        NEUTRAL_EDGE -> fixed

        DESTRUCTIVE_FILL -> if (quiet) onBackground else hue(StyleSeed.DANGER)
        DESTRUCTIVE_TEXT -> if (classic) fixed else hue(StyleSeed.DANGER)
        DESTRUCTIVE_EDGE -> if (tonal) hue(StyleSeed.DANGER) else fixed

        WARNING_STRIP_TEXT -> stripText(look, StyleSeed.WARNING)
        WARNING_STRIP_ICON, WARNING_STRIP_EDGE -> if (classic) fixed else hue(StyleSeed.WARNING)
        WARNING_STRIP_BUTTON_FILL -> stripButtonFill(look, StyleSeed.WARNING)
        WARNING_STRIP_BUTTON_TEXT -> stripText(look, StyleSeed.WARNING)
        WARNING_STRIP_BUTTON_EDGE -> if (tonal) hue(StyleSeed.WARNING) else fixed

        SUCCESS_STRIP_TEXT, SUCCESS_STRIP_ICON -> stripText(look, StyleSeed.SUCCESS)
        SUCCESS_STRIP_EDGE -> fixed
        SUCCESS_STRIP_BUTTON_FILL -> stripButtonFill(look, StyleSeed.SUCCESS)
        SUCCESS_STRIP_BUTTON_TEXT -> stripText(look, StyleSeed.SUCCESS)
        SUCCESS_STRIP_BUTTON_EDGE -> if (tonal) hue(StyleSeed.SUCCESS) else fixed
    }
}

private fun stripText(look: AccentStyle, seed: StyleSeed) = when (look) {
    AccentStyle.CLASSIC -> fixed
    AccentStyle.TONAL -> hue(seed)
    AccentStyle.QUIET -> asText
}

private fun stripButtonFill(look: AccentStyle, seed: StyleSeed) = when (look) {
    AccentStyle.CLASSIC -> fixed
    AccentStyle.TONAL -> hue(seed)
    AccentStyle.QUIET -> onBackground
}

/**
 * A pair that has to hold [minimum] contrast: [foreground] drawn over [layers], the first on top, the last opaque.
 * When it doesn't, [movers] are tried in turn: the first that can move far enough, and wasn't set by hand, moves.
 */
@Immutable
data class ContrastCheck(
    val foreground: StyleSlot,
    val layers: List<StyleSlot>,
    val minimum: Double,
    val movers: List<StyleSlot>,
)

/**
 * The checks of a style in [look] (fork-only): those of `StyleColorsContrastTest`, which the built-in looks have held
 * since 2026-10-21, and text and secondary text on every surface and on the diff's lines. Strips come first, as the
 * destructive button is checked on the warning strip.
 */
fun contrastChecks(look: AccentStyle): List<ContrastCheck> = buildList {
    val filledButtons = look == AccentStyle.CLASSIC

    fun strip(fill: StyleSlot, text: StyleSlot, buttonFill: StyleSlot, buttonText: StyleSlot) {
        add(ContrastCheck(text, listOf(fill), TEXT_CONTRAST, listOf(text, fill)))
        val buttonMovers = if (filledButtons) listOf(buttonFill, buttonText) else listOf(buttonText, buttonFill)
        add(ContrastCheck(buttonText, listOf(buttonFill, fill), TEXT_CONTRAST, buttonMovers))
    }
    strip(WARNING_STRIP_FILL, WARNING_STRIP_TEXT, WARNING_STRIP_BUTTON_FILL, WARNING_STRIP_BUTTON_TEXT)
    strip(SUCCESS_STRIP_FILL, SUCCESS_STRIP_TEXT, SUCCESS_STRIP_BUTTON_FILL, SUCCESS_STRIP_BUTTON_TEXT)

    // The text moves first; when it can't, as when it was set by hand, the surface under it moves instead (the
    // background never does)
    fun text(text: StyleSlot, layers: List<StyleSlot>) =
        add(ContrastCheck(text, layers, TEXT_CONTRAST, listOf(text, layers.first())))

    val surfaces = listOf(listOf(BACKGROUND), listOf(PANELS), listOf(BOXES), listOf(HEADERS), listOf(SELECTED_ROW, BACKGROUND))
    for (layers in surfaces) {
        text(TEXT, layers)
        text(SECONDARY_TEXT, layers)
    }
    text(TEXT, listOf(ADDED_LINE, BACKGROUND))
    text(TEXT, listOf(REMOVED_LINE, BACKGROUND))

    fun button(fill: StyleSlot, text: StyleSlot, filled: Boolean, on: List<StyleSlot>) {
        val movers = if (filled) listOf(fill, text) else listOf(text, fill)
        for (surface in on) add(ContrastCheck(text, listOf(fill, surface), TEXT_CONTRAST, movers))
    }
    // The main action and the toasts are filled in every look, with white text
    button(MAIN_ACTION_FILL, MAIN_ACTION_TEXT, filled = true, on = listOf(BACKGROUND, PANELS))
    button(ACTION_FILL, ACTION_TEXT, filledButtons, on = listOf(BACKGROUND, PANELS, HEADERS, BOXES))
    button(NEUTRAL_FILL, NEUTRAL_TEXT, filledButtons, on = listOf(BACKGROUND, PANELS, HEADERS, BOXES))
    button(DESTRUCTIVE_FILL, DESTRUCTIVE_TEXT, filledButtons, on = listOf(BACKGROUND, PANELS, BOXES, WARNING_STRIP_FILL))

    // Classic keeps Leaf's amber icon, 1.75 : 1 on white, which the other looks are there to fix
    if (look != AccentStyle.CLASSIC) add(ContrastCheck(WARNING_ICON, listOf(BACKGROUND), ICON_CONTRAST, listOf(WARNING_ICON)))

    add(ContrastCheck(LINKS, listOf(BACKGROUND), TEXT_CONTRAST, listOf(LINKS)))
}

/** A colour that Leaf moved so that [check] holds: from what the seeds gave, [from], to [to], which makes [ratio]. */
@Immutable
data class StyleAdjustment(val slot: StyleSlot, val from: Color, val to: Color, val check: ContrastCheck, val ratio: Double)

/** A check that doesn't hold, which only colours set by hand leave. */
@Immutable
data class ContrastShortfall(val check: ContrastCheck, val ratio: Double)

/** One side of a style, worked out (fork-only). */
@Immutable
data class ResolvedStyleSide(
    val palette: StylePalette,
    val adjustments: List<StyleAdjustment>,
    val shortfalls: List<ContrastShortfall>,
)

/** The contrast that [check] has in [palette], a candidate [replacement] in place of one slot's colour. */
fun StylePalette.contrastOf(check: ContrastCheck, replacement: Pair<StyleSlot, Int>? = null): Double {
    fun argb(slot: StyleSlot) = if (replacement?.first == slot) replacement.second else this[slot].toArgb()

    val under = StyleColorMath.composite(check.layers.map(::argb))
    val over = StyleColorMath.composite(argb(check.foreground), under)

    return StyleColorMath.contrast(over, under)
}

/**
 * The colours of [definition]'s light or dark side (fork-only, `docs/fork/custom-styles.md`):
 * 1. a colour set by hand is used as it is;
 * 2. any other starts as its look's ([StylePalette.template]) and follows its seed ([slotRule]). A colour whose seed is
 *    the look's own stays the look's exactly, so a built-in style gives its look's colours;
 * 3. then each [contrastChecks] that fails moves the first of its movers that can hold it and wasn't set by hand, the
 *    background aside, by as little lightness as it takes. The looks hold every check, so they never move.
 */
fun resolveStyleSide(definition: StyleDefinition, isLight: Boolean): ResolvedStyleSide {
    val look = definition.look
    val template = StylePalette.template(look, isLight)
    val templateSeeds = BuiltInStyles.templateSeeds(look, isLight)
    val side = definition.side(isLight)
    val seeds = side.seeds.with(StyleSeed.BACKGROUND, fittedBackground(template, templateSeeds, side.seeds.background, isLight))

    val colors = StyleSlot.entries.associateWith { slot ->
        side.setByHand[slot]?.let { Color(it) }
            ?: followed(template[slot], slotRule(slot, look, isLight), templateSeeds, seeds)
    }
    var palette = StylePalette(isLight, colors)

    val checks = contrastChecks(look)
    val firstColors = mutableMapOf<StyleSlot, Color>()
    val lastChecks = mutableMapOf<StyleSlot, Pair<ContrastCheck, Double>>()


    fun movable(slot: StyleSlot) = slot !in side.setByHand && slot != BACKGROUND

    // A colour moved for one check can make another fail, so go round until nothing moves
    for (round in 1..MAX_ROUNDS) {
        var moved = false

        for (check in checks) {
            if (palette.contrastOf(check) >= check.minimum) continue

            for (mover in check.movers.filter(::movable)) {
                val before = palette[mover].toArgb()
                val after = StyleColorMath.movedForContrast(before, check.minimum, palette.awayFromForeground(check, mover)) { candidate ->
                    palette.contrastOf(check, mover to candidate)
                } ?: continue

                firstColors.putIfAbsent(mover, palette[mover])
                palette = palette.with(mover, Color(after))
                lastChecks[mover] = check to palette.contrastOf(check)
                moved = true
                break
            }
        }

        if (!moved) break
    }

    // The background was fitted before anything else, so that text could read on every surface: say for which
    if (seeds.background != side.seeds.background && BACKGROUND !in side.setByHand) {
        val onSurfaces = BACKGROUND_SURFACES.map { ContrastCheck(TEXT, listOf(it), TEXT_CONTRAST, listOf(BACKGROUND)) }
        val worst = onSurfaces.minBy { palette.contrastOf(it) }

        firstColors[BACKGROUND] = Color(side.seeds.background)
        lastChecks[BACKGROUND] = worst to palette.contrastOf(worst)
    }

    val adjustments = firstColors.map { (slot, from) ->
        val (check, ratio) = lastChecks.getValue(slot)
        StyleAdjustment(slot, from, palette[slot], check, ratio)
    }
    val shortfalls = checks.mapNotNull { check ->
        palette.contrastOf(check).takeIf { it < check.minimum }?.let { ContrastShortfall(check, it) }
    }

    return ResolvedStyleSide(palette, adjustments, shortfalls)
}

private const val MAX_ROUNDS = 4

/**
 * Which way [mover] may go for [check]: a fill or a surface under the foreground moves only away from it, so that a light
 * panel under grey text gets lighter, never dark. The foreground itself may go either way.
 */
private fun StylePalette.awayFromForeground(check: ContrastCheck, mover: StyleSlot): Double? {
    if (mover !in check.layers) return null

    val under = StyleColorMath.composite(check.layers.map { this[it].toArgb() })
    val over = StyleColorMath.composite(this[check.foreground].toArgb(), under)
    val isLighter = StyleColorMath.relativeLuminance(under) >= StyleColorMath.relativeLuminance(over)

    return if (isLighter) StyleColorMath.LIGHTER else StyleColorMath.DARKER
}

/** The surfaces that follow the background, which text is on. */
private val BACKGROUND_SURFACES = listOf(BACKGROUND, PANELS, BOXES, HEADERS)

/**
 * [background], or as near to it as leaves room for text on every surface that follows it, and on the see-through
 * buttons on those: on the light side black must hold 4.5 : 1 on all of them, on the dark side white. A mid-tone background leaves no text colour that reads on
 * both it and the darker or lighter surfaces next to it, so it becomes lighter on the light side, darker on the dark
 * side, keeping its hue.
 */
private fun fittedBackground(template: StylePalette, templateSeeds: StyleSeeds, background: Int, isLight: Boolean): Int {
    val text = if (isLight) BLACK else WHITE

    // The see-through buttons of the dark looks (white at 8%) lighten the surface they're on
    val seeThroughFills = listOf(ACTION_FILL, NEUTRAL_FILL, DESTRUCTIVE_FILL)
        .map { template[it].toArgb() }
        .filter { StyleColorMath.alpha(it) in 1..0xFE }

    fun holds(candidate: Int) = BACKGROUND_SURFACES.all { surface ->
        val color = StyleShifts.followBackground(template[surface].toArgb(), templateSeeds.background, candidate)

        (listOf(color) + seeThroughFills.map { StyleColorMath.composite(it, color) }).all {
            StyleColorMath.contrast(text, it) >= TEXT_CONTRAST + MARGIN
        }
    }

    return StyleColorMath.movedForContrast(background, minimum = 1.0) { if (holds(it)) 1.0 else 0.0 } ?: background
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

// Room for rounding and for the text's own tint, which keeps it a little off black or white
private const val MARGIN = 0.3

private fun followed(color: Color, rule: SlotRule, from: StyleSeeds, to: StyleSeeds): Color {
    val seed = rule.seed ?: return color
    val fromSeed = from[seed]
    val toSeed = to[seed]

    // The look's own colour, untouched: a see-through one keeps its exact alpha, which ARGB would round
    if (fromSeed == toSeed) return color

    val argb = color.toArgb()
    val shifted = when (rule.follow) {
        Follow.SEED -> toSeed
        Follow.HUE -> StyleShifts.followHue(argb, fromSeed, toSeed)
        Follow.BACKGROUND -> StyleShifts.followBackground(argb, fromSeed, toSeed)
        Follow.TEXT -> StyleShifts.followBackgroundAsText(argb, fromSeed, toSeed)
        Follow.FIXED -> argb
    }

    return if (shifted == argb) color else Color(shifted)
}
