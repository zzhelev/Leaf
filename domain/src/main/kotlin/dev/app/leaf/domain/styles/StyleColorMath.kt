// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A colour in OKLab: [l] from 0 (black) to 1 (white), and [a] and [b], its green–red and blue–yellow axes. */
data class Oklab(val l: Double, val a: Double, val b: Double) {
    fun toOklch(): Oklch {
        val chroma = hypot(a, b)
        val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }

        return Oklch(l, chroma, hue)
    }
}

/** A colour in OKLCH: lightness, chroma (0 is grey) and hue in degrees. */
data class Oklch(val l: Double, val c: Double, val h: Double) {
    fun toOklab(): Oklab {
        val radians = Math.toRadians(h)
        return Oklab(l, c * cos(radians), c * sin(radians))
    }
}

/**
 * Colour arithmetic for styles (fork-only), on ARGB ints: OKLab and OKLCH (Björn Ottosson's), which keep lightness
 * apart from hue, and WCAG 2's relative luminance and contrast ratio, which Leaf's contrast checks use.
 */
object StyleColorMath {
    /** How far out of 0..1 a linear channel may be and still count as inside sRGB, for rounding in the conversions. */
    private const val GAMUT_EPSILON = 1e-7

    /** The lightness that [movedForContrast] moves a colour towards: black's side, or white's. */
    const val DARKER = 0.0
    const val LIGHTER = 1.0

    fun alpha(argb: Int): Int = argb ushr 24

    fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0xFFFFFF)

    fun toOklab(argb: Int): Oklab {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)

        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)

        return Oklab(
            l = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun toOklch(argb: Int): Oklch = toOklab(argb).toOklch()

    /** The linear sRGB channels of [lab], which fall outside 0..1 when sRGB can't show it. */
    private fun linearRgb(lab: Oklab): DoubleArray {
        val l = (lab.l + 0.3963377774 * lab.a + 0.2158037573 * lab.b).pow(3)
        val m = (lab.l - 0.1055613458 * lab.a - 0.0638541728 * lab.b).pow(3)
        val s = (lab.l - 0.0894841775 * lab.a - 1.2914855480 * lab.b).pow(3)

        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    fun isInGamut(lab: Oklab): Boolean =
        linearRgb(lab).all { it >= -GAMUT_EPSILON && it <= 1 + GAMUT_EPSILON }

    /** [lab] as ARGB with [alpha], each channel rounded and clipped to sRGB. */
    fun fromOklab(lab: Oklab, alpha: Int = 0xFF): Int {
        val (r, g, b) = linearRgb(lab).map { channel(it) }
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * [lch] as ARGB, with its chroma lowered, keeping lightness and hue, until sRGB can show it, as CSS Color 4 maps
     * colours into a gamut. Lightness is clamped to 0..1.
     */
    fun fromOklch(lch: Oklch, alpha: Int = 0xFF): Int {
        val l = lch.l.coerceIn(0.0, 1.0)
        val wanted = Oklch(l, lch.c.coerceAtLeast(0.0), lch.h)

        if (isInGamut(wanted.toOklab())) return fromOklab(wanted.toOklab(), alpha)

        var low = 0.0
        var high = wanted.c
        repeat(40) {
            val middle = (low + high) / 2
            if (isInGamut(Oklch(l, middle, lch.h).toOklab())) low = middle else high = middle
        }

        return fromOklab(Oklch(l, low, lch.h).toOklab(), alpha)
    }

    /** WCAG 2's relative luminance of the colour, its alpha ignored. */
    fun relativeLuminance(argb: Int): Double =
        0.2126 * linear((argb shr 16) and 0xFF) + 0.7152 * linear((argb shr 8) and 0xFF) + 0.0722 * linear(argb and 0xFF)

    /** WCAG 2's contrast ratio of two opaque colours, from 1 to 21. */
    fun contrast(first: Int, second: Int): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)

        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** [top] laid over the opaque [bottom], as Compose draws it: per channel, in sRGB. */
    fun composite(top: Int, bottom: Int): Int {
        val a = alpha(top) / 255.0
        if (a >= 1.0) return top

        fun mix(shift: Int): Int {
            val over = (top shr shift) and 0xFF
            val under = (bottom shr shift) and 0xFF
            return (over * a + under * (1 - a)).roundToInt().coerceIn(0, 255)
        }

        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** [layers] laid over each other, the first on top; the last should be opaque. */
    fun composite(layers: List<Int>): Int = layers.reversed().reduce { under, over -> composite(over, under) }

    /**
     * [color] moved in lightness, keeping its hue and alpha and as much chroma as sRGB allows, by as little as makes
     * [ratio] of the result reach [minimum], or null when neither black's side nor white's can. [ratio] gives the
     * contrast that a candidate makes where it's used, so the colour may be text, an icon or a fill. [towards] keeps it
     * to one side: [LIGHTER] or [DARKER].
     */
    fun movedForContrast(color: Int, minimum: Double, towards: Double? = null, ratio: (Int) -> Double): Int? {
        if (ratio(color) >= minimum) return color

        val start = toOklch(color)
        val alpha = alpha(color)
        fun at(l: Double) = fromOklch(start.copy(l = l), alpha)

        val ends = if (towards == null) listOf(DARKER, LIGHTER) else listOf(towards)
        val candidates = ends.mapNotNull { end ->
            if (ratio(at(end)) < minimum) return@mapNotNull null

            // The lightness nearest the colour's own whose result holds the contrast. Contrast grows towards the end
            // that holds it, so a binary search finds where it starts to hold. The candidates are rounded to 8 bits
            // before they're measured, so the one found holds as it will be drawn
            var holds = end
            var fails = start.l
            repeat(40) {
                val middle = (holds + fails) / 2
                if (ratio(at(middle)) >= minimum) holds = middle else fails = middle
            }

            at(holds) to abs(holds - start.l)
        }

        return candidates.minByOrNull { it.second }?.first
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun channel(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val srgb = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return (srgb * 255).roundToInt().coerceIn(0, 255)
    }
}

/**
 * How a colour of a look follows a seed that changed (fork-only): each takes the look's colour, the seed the look is
 * built around, and the style's seed, and keeps the colour's alpha. The style derivation picks one per colour.
 */
object StyleShifts {
    private const val ACHROMATIC = 1e-4

    /**
     * For colours in the family of an accent, danger, warning or success seed: the look's lightness stays, which keeps
     * what makes the look readable, the hue turns as far as the seed's did, and the chroma scales as the seed's did,
     * down to grey for a grey seed.
     */
    fun followHue(color: Int, fromSeed: Int, toSeed: Int): Int {
        if (color == 0 || fromSeed == toSeed) return color

        val own = StyleColorMath.toOklch(color)
        val from = StyleColorMath.toOklch(fromSeed)
        val to = StyleColorMath.toOklch(toSeed)

        val chroma = if (from.c < ACHROMATIC) own.c else own.c * to.c / from.c
        val hue = if (from.c < ACHROMATIC || to.c < ACHROMATIC) own.h else (own.h + to.h - from.h).mod(360.0)

        return StyleColorMath.fromOklch(Oklch(own.l, chroma, hue), StyleColorMath.alpha(color))
    }

    /**
     * For surfaces: they move with the background seed in OKLab, by the same lightness and the same tint. A see-through
     * colour stays as it is, as it already lets the background through.
     */
    fun followBackground(color: Int, fromSeed: Int, toSeed: Int): Int =
        shiftedLab(color, fromSeed, toSeed, lightness = 1.0, tint = 1.0)

    /** For text on the background: its lightness stays, and it takes half the background's change of tint. */
    fun followBackgroundAsText(color: Int, fromSeed: Int, toSeed: Int): Int =
        shiftedLab(color, fromSeed, toSeed, lightness = 0.0, tint = 0.5)

    private fun shiftedLab(color: Int, fromSeed: Int, toSeed: Int, lightness: Double, tint: Double): Int {
        if (fromSeed == toSeed || StyleColorMath.alpha(color) != 0xFF) return color

        val own = StyleColorMath.toOklab(color)
        val from = StyleColorMath.toOklab(fromSeed)
        val to = StyleColorMath.toOklab(toSeed)

        val moved = Oklab(
            l = own.l + (to.l - from.l) * lightness,
            a = own.a + (to.a - from.a) * tint,
            b = own.b + (to.b - from.b) * tint,
        )

        return StyleColorMath.fromOklch(moved.toOklch())
    }
}
