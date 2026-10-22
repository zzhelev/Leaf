// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme.styles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.Oklch
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSeeds
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styleColorsFor
import dev.app.leaf.theme.styled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StyleDerivationTest {
    private val sides = listOf(true, false)

    private fun hex(text: String) = checkNotNull(StyleColorHex.parse(text))

    private fun Color.hex() = StyleColorHex.format(toArgb())

    private fun style(look: AccentStyle, light: StyleSide, dark: StyleSide = light) =
        StyleDefinition("test", "Test", look, light, dark)

    private fun seedsOf(look: AccentStyle, isLight: Boolean) = BuiltInStyles.templateSeeds(look, isLight)

    @Test
    fun `Classic, Tonal and Quiet give their look's colours exactly, lanes included, and move nothing`() {
        for (style in BuiltInStyles.ALL) {
            for (isLight in sides) {
                val resolved = resolveStyleSide(style, isLight)
                val scheme = (if (isLight) lightTheme else darkTheme).styled(style.look)

                assertEquals(scheme, resolved.palette.toColorsScheme(), "${style.id} $isLight")
                assertEquals(styleColorsFor(style.look, scheme), resolved.palette.toStyleColors(), "${style.id} $isLight")
                val lanes = if (style.look == AccentStyle.CLASSIC) CLASSIC_LANES else MUTED_LANES
                assertEquals(lanes, resolved.palette.lanes, "${style.id} $isLight")
                assertEquals(emptyList<StyleAdjustment>(), resolved.adjustments, "${style.id} $isLight")
                assertEquals(emptyList<ContrastShortfall>(), resolved.shortfalls, "${style.id} $isLight")
            }
        }
    }

    @Test
    fun `the grey lane stays the nine-digit value Leaf has drawn it with`() {
        assertEquals(Color(0xFE78909C), CLASSIC_LANES[2])
    }

    @Test
    fun `Tonal and Quiet have the board's muted lanes, Classic Leaf's old ones`() {
        assertEquals(
            listOf(0xFF5B97D3, 0xFFCD7670, 0xFF7D97A4, 0xFFC87D52, 0xFF60A56B, 0xFFC4759C).map { Color(it) },
            MUTED_LANES,
        )
        assertEquals(MUTED_LANES, lanesOf(AccentStyle.TONAL))
        assertEquals(MUTED_LANES, lanesOf(AccentStyle.QUIET))
        assertEquals(CLASSIC_LANES, lanesOf(AccentStyle.CLASSIC))
    }

    @Test
    fun `every look's own colours hold every check, which is why a built-in style never moves`() {
        for (look in AccentStyle.entries) {
            for (isLight in sides) {
                val template = StylePalette.template(look, isLight)

                for (check in contrastChecks(look)) {
                    val ratio = template.contrastOf(check)
                    assertTrue(ratio >= check.minimum, "$look $isLight $check: $ratio")
                }
            }
        }
    }

    @Test
    fun `the background is its seed, and a colour that follows a seed is the seed in its look`() {
        for (look in AccentStyle.entries) {
            for (isLight in sides) {
                val template = StylePalette.template(look, isLight)
                val seeds = seedsOf(look, isLight)

                for (slot in StyleSlot.entries.filter { slotRule(it, look, isLight).follow == Follow.SEED }) {
                    val seed = checkNotNull(slotRule(slot, look, isLight).seed)
                    assertEquals(seeds[seed], template[slot].toArgb(), "$look $isLight $slot")
                }
            }
        }
    }

    @Test
    fun `changing one seed moves only the colours that follow it, unless a check moves another`() {
        for (look in AccentStyle.entries) {
            for (isLight in sides) {
                for (seed in StyleSeed.entries.filter { it != StyleSeed.BACKGROUND }) {
                    val seeds = seedsOf(look, isLight)
                    // The same lightness and chroma, a quarter turn of hue
                    val own = StyleColorMath.toOklch(seeds[seed])
                    val turned = StyleColorMath.fromOklch(own.copy(h = (own.h + 90) % 360))
                    val resolved = resolveStyleSide(style(look, StyleSide(seeds.with(seed, turned))), isLight)
                    val template = StylePalette.template(look, isLight)
                    val moved = resolved.adjustments.map { it.slot }.toSet()

                    for (slot in StyleSlot.entries) {
                        val follows = slotRule(slot, look, isLight).seed == seed
                        if (!follows && slot !in moved) {
                            assertEquals(template[slot], resolved.palette[slot], "$look $isLight $seed $slot")
                        }
                    }

                    val followers = StyleSlot.entries.filter {
                        slotRule(it, look, isLight).seed == seed && template[it].alpha > 0f
                    }
                    assertTrue(followers.any { resolved.palette[it] != template[it] }, "$look $isLight $seed")
                }
            }
        }
    }

    @Test
    fun `Solarized's blue in Tonal fills buttons in Tonal's lightness, readable under white`() {
        val seeds = seedsOf(AccentStyle.TONAL, isLight = true).with(StyleSeed.ACCENT, hex("#268BD2"))
        val resolved = resolveStyleSide(style(AccentStyle.TONAL, StyleSide(seeds)), isLight = true)
        val main = resolved.palette[StyleSlot.MAIN_ACTION_FILL].toArgb()
        val lch = StyleColorMath.toOklch(main)

        assertEquals(0.520, lch.l, 0.02, StyleColorHex.format(main))
        assertEquals(244.9, lch.h, 2.0, StyleColorHex.format(main))
        assertTrue(StyleColorMath.contrast(main, hex("#FFFFFF")) >= 4.5)
        // The design's #196EA9, near enough
        assertTrue(StyleColorMath.contrast(main, hex("#196EA9")) < 1.1, StyleColorHex.format(main))
    }

    @Test
    fun `random seeds in every look and side hold every check`() {
        val random = Random(20261022)

        for (look in AccentStyle.entries) {
            for (isLight in sides) {
                repeat(2_000) {
                    fun any() = random.nextInt() or 0xFF000000.toInt()
                    // A background of the side's kind, or any colour at all now and then
                    val background = if (random.nextInt(5) == 0) {
                        any()
                    } else {
                        val l = if (isLight) random.nextDouble(0.85, 1.0) else random.nextDouble(0.1, 0.35)
                        StyleColorMath.fromOklch(Oklch(l, random.nextDouble(0.0, 0.06), random.nextDouble(360.0)))
                    }
                    val seeds = StyleSeeds(background, any(), any(), any(), any())
                    val resolved = resolveStyleSide(style(look, StyleSide(seeds)), isLight)

                    assertEquals(
                        emptyList<ContrastShortfall>(),
                        resolved.shortfalls,
                        "$look $isLight ${StyleSeed.entries.map { StyleColorHex.format(seeds[it]) }}",
                    )
                    for (adjustment in resolved.adjustments) {
                        assertTrue(adjustment.ratio >= adjustment.check.minimum, "$adjustment")
                    }
                    assertHoldsTheSpec(resolved.palette, look, "$look $isLight ${StyleSeed.entries.map { StyleColorHex.format(seeds[it]) }}")
                }
            }
        }
    }

    /**
     * Written from `StyleColorsContrastTest` and the plan, not from [contrastChecks], so that a check missing from the
     * derivation's list fails here.
     */
    private fun assertHoldsTheSpec(palette: StylePalette, look: AccentStyle, what: String) {
        fun argb(slot: StyleSlot) = palette[slot].toArgb()
        fun ratio(text: StyleSlot, vararg under: StyleSlot): Double {
            val ground = StyleColorMath.composite(under.map(::argb))
            return StyleColorMath.contrast(StyleColorMath.composite(argb(text), ground), ground)
        }

        val pairs = listOf(
            "text" to ratio(StyleSlot.TEXT, StyleSlot.BACKGROUND),
            "text on headers" to ratio(StyleSlot.TEXT, StyleSlot.HEADERS),
            "secondary text" to ratio(StyleSlot.SECONDARY_TEXT, StyleSlot.BACKGROUND),
            "links" to ratio(StyleSlot.LINKS, StyleSlot.BACKGROUND),
            "main action" to ratio(StyleSlot.MAIN_ACTION_TEXT, StyleSlot.MAIN_ACTION_FILL, StyleSlot.BACKGROUND),
            "action" to ratio(StyleSlot.ACTION_TEXT, StyleSlot.ACTION_FILL, StyleSlot.HEADERS),
            "neutral" to ratio(StyleSlot.NEUTRAL_TEXT, StyleSlot.NEUTRAL_FILL, StyleSlot.BOXES),
            "destructive" to ratio(StyleSlot.DESTRUCTIVE_TEXT, StyleSlot.DESTRUCTIVE_FILL, StyleSlot.WARNING_STRIP_FILL),
            "warning strip" to ratio(StyleSlot.WARNING_STRIP_TEXT, StyleSlot.WARNING_STRIP_FILL),
            "warning strip's button" to ratio(
                StyleSlot.WARNING_STRIP_BUTTON_TEXT, StyleSlot.WARNING_STRIP_BUTTON_FILL, StyleSlot.WARNING_STRIP_FILL,
            ),
            "success strip" to ratio(StyleSlot.SUCCESS_STRIP_TEXT, StyleSlot.SUCCESS_STRIP_FILL),
            "text on added lines" to ratio(StyleSlot.TEXT, StyleSlot.ADDED_LINE, StyleSlot.BACKGROUND),
        )
        for ((name, ratio) in pairs) assertTrue(ratio >= TEXT_CONTRAST, "$what $name: $ratio")

        if (look != AccentStyle.CLASSIC) {
            val icon = ratio(StyleSlot.WARNING_ICON, StyleSlot.BACKGROUND)
            assertTrue(icon >= ICON_CONTRAST, "$what warning icon: $icon")
        }
    }

    @Test
    fun `a colour set by hand is used as given, never moved, and a check it fails is a shortfall`() {
        val seeds = seedsOf(AccentStyle.TONAL, isLight = true)
        // Pale text on white
        val side = StyleSide(seeds, mapOf(StyleSlot.TEXT to hex("#CCCCCC")))
        val resolved = resolveStyleSide(style(AccentStyle.TONAL, side), isLight = true)

        assertEquals("#CCCCCC", resolved.palette[StyleSlot.TEXT].hex())
        assertTrue(resolved.adjustments.none { it.slot == StyleSlot.TEXT })
        assertTrue(resolved.shortfalls.isNotEmpty())
        assertTrue(resolved.shortfalls.all { it.check.foreground == StyleSlot.TEXT && it.ratio < it.check.minimum })
    }

    @Test
    fun `when a fill set by hand makes its text fail, the text moves instead`() {
        val seeds = seedsOf(AccentStyle.TONAL, isLight = true)
        // A dark action fill under Tonal's dark blue text
        val side = StyleSide(seeds, mapOf(StyleSlot.ACTION_FILL to hex("#203040")))
        val resolved = resolveStyleSide(style(AccentStyle.TONAL, side), isLight = true)

        assertEquals("#203040", resolved.palette[StyleSlot.ACTION_FILL].hex())
        val adjustment = resolved.adjustments.single { it.slot == StyleSlot.ACTION_TEXT }
        assertEquals("#255691", adjustment.from.hex())
        assertTrue(adjustment.ratio >= TEXT_CONTRAST)
        assertEquals(emptyList<ContrastShortfall>(), resolved.shortfalls)
    }

    @Test
    fun `a filled button keeps white text and darkens its fill, and says how far`() {
        // A green accent in Classic, which fills buttons with it: green at Classic's blue's lightness is a little
        // brighter, 4.46 : 1 under white
        val seeds = seedsOf(AccentStyle.CLASSIC, isLight = true).with(StyleSeed.ACCENT, hex("#20C060"))
        val resolved = resolveStyleSide(style(AccentStyle.CLASSIC, StyleSide(seeds)), isLight = true)

        assertEquals(Color.White, resolved.palette[StyleSlot.MAIN_ACTION_TEXT])
        val main = resolved.palette[StyleSlot.MAIN_ACTION_FILL].toArgb()
        assertTrue(StyleColorMath.contrast(main, hex("#FFFFFF")) >= 4.5)

        val adjustment = resolved.adjustments.single { it.slot == StyleSlot.MAIN_ACTION_FILL }
        assertTrue(StyleColorMath.contrast(adjustment.from.toArgb(), hex("#FFFFFF")) < 4.5, adjustment.from.hex())
        assertEquals(main, adjustment.to.toArgb())
        assertEquals(emptyList<ContrastShortfall>(), resolved.shortfalls)
    }

    @Test
    fun `each seed moves the colours of its family, as the plan lists them`() {
        // Written out, rather than read from slotRule, so that a colour moved to another family fails here
        val families = mapOf(
            StyleSeed.BACKGROUND to setOf(
                StyleSlot.BACKGROUND, StyleSlot.PANELS, StyleSlot.BOXES, StyleSlot.HEADERS, StyleSlot.SCROLLBAR,
                StyleSlot.TEXT, StyleSlot.SECONDARY_TEXT, StyleSlot.NEUTRAL_FILL, StyleSlot.NEUTRAL_TEXT,
            ),
            StyleSeed.ACCENT to setOf(
                StyleSlot.MAIN_ACTION_FILL, StyleSlot.LINKS, StyleSlot.MODIFIED_FILE, StyleSlot.KEYWORD, StyleSlot.LANE_1,
                StyleSlot.SELECTED_ROW, StyleSlot.SCROLLBAR_HOVER, StyleSlot.ACTION_FILL, StyleSlot.ACTION_TEXT,
                StyleSlot.ACTION_EDGE,
            ),
            StyleSeed.DANGER to setOf(
                StyleSlot.ERROR, StyleSlot.DELETED_FILE, StyleSlot.REMOVED_LINE, StyleSlot.REMOVED_WORDS, StyleSlot.LANE_2,
                StyleSlot.DESTRUCTIVE_FILL, StyleSlot.DESTRUCTIVE_TEXT, StyleSlot.DESTRUCTIVE_EDGE,
            ),
            StyleSeed.WARNING to setOf(
                StyleSlot.CONFLICTED_FILE, StyleSlot.ANNOTATION, StyleSlot.LANE_4, StyleSlot.WARNING_ICON,
                StyleSlot.WARNING_STRIP_FILL, StyleSlot.WARNING_STRIP_TEXT, StyleSlot.WARNING_STRIP_ICON,
                StyleSlot.WARNING_STRIP_EDGE, StyleSlot.WARNING_STRIP_BUTTON_FILL, StyleSlot.WARNING_STRIP_BUTTON_TEXT,
                StyleSlot.WARNING_STRIP_BUTTON_EDGE,
            ),
            StyleSeed.SUCCESS to setOf(
                StyleSlot.ADDED_FILE, StyleSlot.ADDED_LINE, StyleSlot.ADDED_WORDS, StyleSlot.COMMENT, StyleSlot.LANE_5,
                StyleSlot.SUCCESS_STRIP_FILL, StyleSlot.SUCCESS_STRIP_TEXT, StyleSlot.SUCCESS_STRIP_ICON,
                StyleSlot.SUCCESS_STRIP_BUTTON_FILL, StyleSlot.SUCCESS_STRIP_BUTTON_TEXT,
                StyleSlot.SUCCESS_STRIP_BUTTON_EDGE,
            ),
        )

        // In Tonal's light side, where nearly every colour follows a seed
        for ((seed, family) in families) {
            val followers = StyleSlot.entries.filter { slotRule(it, AccentStyle.TONAL, isLight = true).seed == seed }.toSet()
            assertEquals(family, followers, "$seed")
        }
    }

    @Test
    fun `a style's two sides are worked out apart, each from its own seeds`() {
        val light = StyleSide(seedsOf(AccentStyle.TONAL, true).with(StyleSeed.BACKGROUND, hex("#FDF6E3")))
        val dark = StyleSide(seedsOf(AccentStyle.TONAL, false).with(StyleSeed.BACKGROUND, hex("#002B36")))
        val definition = style(AccentStyle.TONAL, light, dark)

        assertEquals("#FDF6E3", resolveStyleSide(definition, true).palette[StyleSlot.BACKGROUND].hex())
        assertEquals("#002B36", resolveStyleSide(definition, false).palette[StyleSlot.BACKGROUND].hex())
        assertTrue(resolveStyleSide(definition, true).palette.isLight)
        assertTrue(!resolveStyleSide(definition, false).palette.isLight)
        assertNotEquals(
            resolveStyleSide(definition, true).palette[StyleSlot.PANELS],
            StylePalette.template(AccentStyle.TONAL, true)[StyleSlot.PANELS],
        )
    }

    @Test
    fun `a palette gives back the scheme and roles it was made of`() {
        for (look in AccentStyle.entries) {
            for (isLight in sides) {
                val scheme = (if (isLight) lightTheme else darkTheme).styled(look)
                val roles = styleColorsFor(look, scheme)
                val palette = StylePalette.of(scheme, roles, lanesOf(look))

                assertEquals(scheme, palette.toColorsScheme())
                assertEquals(roles, palette.toStyleColors())
                assertEquals(lanesOf(look), palette.lanes)
                assertEquals(palette, StylePalette.template(look, isLight))
            }
        }
    }
}
