// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme.styles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.CodeColours
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styled
import dev.app.leaf.theme.syntaxTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** The nine colours of code, in every built-in style and with each Code colours choice (fork-only). */
class SyntaxColoursTest {
    private val sides = listOf(true, false)

    private fun Color.hex() = StyleColorHex.format(toArgb())

    private fun syntaxOf(style: StyleDefinition, isLight: Boolean, choice: CodeColours = CodeColours.LEAF) =
        resolveStyleSide(style, isLight, choice).palette.let { palette -> StyleSlot.SYNTAX.map { palette[it].hex() } }

    private fun distance(first: Color, second: Color): Double {
        val a = StyleColorMath.toOklab(first.toArgb())
        val b = StyleColorMath.toOklab(second.toArgb())
        return hypot(hypot(a.l - b.l, a.a - b.a), a.b - b.b)
    }

    // Keyword, annotation, comment, string, number, type, function, tag and attribute: palette 1a of the "Syntax
    // Colours" board, which Classic, Tonal and Quiet share
    private val template = mapOf(
        true to
            listOf("#1A69B5", "#756700", "#027835", "#A44C1D", "#A73B6D", "#7652AC", "#06717A", "#1A69B5", "#7652AC"),
        false to
            listOf("#A3CAF1", "#C9BC6F", "#86CE99", "#F2AB83", "#F3A4C2", "#C7AFF5", "#62CFCE", "#A3CAF1", "#C7AFF5"),
    )

    @Test
    fun `Classic, Tonal and Quiet draw code in palette 1a, the scheme's three colours included`() {
        for (style in listOf(BuiltInStyles.CLASSIC, BuiltInStyles.TONAL, BuiltInStyles.QUIET)) {
            for (isLight in sides) {
                assertEquals(template.getValue(isLight), syntaxOf(style, isLight), "${style.id} $isLight")

                val scheme = (if (isLight) lightTheme else darkTheme).styled(style.look)
                val palette = resolveStyleSide(style, isLight).palette
                assertEquals(syntaxTemplate(scheme), palette.toSyntaxColors(), "${style.id} $isLight")
                assertEquals(scheme.diffKeyword, palette.toSyntaxColors().keyword)
            }
        }
    }

    @Test
    fun `Solarized and Teal draw code in their own colours`() {
        assertEquals(
            listOf("#036FAF", "#866502", "#586E75", "#007770", "#C42576", "#5E62B4", "#BE3F00", "#036FAF", "#5E62B4"),
            syntaxOf(BuiltInStyles.SOLARIZED, isLight = true),
        )
        assertEquals(
            listOf("#45A5EE", "#C49823", "#91A2A4", "#3DAFA6", "#FF6AAB", "#9097EE", "#FB7748", "#45A5EE", "#9097EE"),
            syntaxOf(BuiltInStyles.SOLARIZED, isLight = false),
        )
        // The keyword and tag follow Teal's accent, the annotation and comment its warning and success seeds. On the
        // light side the keyword takes a shade more to hold 4.5 : 1 on the removed lines
        assertEquals(
            listOf("#007473", "#6E6A00", "#307443", "#A44C1D", "#A73B6D", "#7652AC", "#1A69B5", "#007473", "#7652AC"),
            syntaxOf(BuiltInStyles.TEAL, isLight = true),
        )
        assertEquals(
            listOf("#9DD1CD", "#D0BA5B", "#94CAA2", "#F2AB83", "#F3A4C2", "#C7AFF5", "#97BBFE", "#9DD1CD", "#C7AFF5"),
            syntaxOf(BuiltInStyles.TEAL, isLight = false),
        )
    }

    @Test
    fun `Teal's function is as far from its keyword as Tonal's, where the template's cyan would be closer`() {
        for (isLight in sides) {
            val teal = resolveStyleSide(BuiltInStyles.TEAL, isLight).palette
            val tonal = resolveStyleSide(BuiltInStyles.TONAL, isLight).palette
            val side = BuiltInStyles.TEAL.side(isLight)
            val withoutFunction = side.copy(setByHand = side.setByHand - StyleSlot.FUNCTION)
            val following = BuiltInStyles.TEAL.withSide(isLight, withoutFunction)
            val template = resolveStyleSide(following, isLight).palette

            fun apart(palette: StylePalette) = distance(palette[StyleSlot.FUNCTION], palette[StyleSlot.KEYWORD])
            // On the light side the template's function is 0.017 from Teal's keyword, as the plan says
            assertTrue(apart(template) < apart(tonal), "$isLight ${apart(template)} ${apart(tonal)}")
            assertTrue(apart(teal) >= apart(tonal), "$isLight ${apart(teal)} ${apart(tonal)}")
        }
    }

    // As Leaf draws VS Code's and IntelliJ's colours in Tonal, which the board's turn 2 shows within a unit of rounding
    private val inTonal = mapOf(
        (CodeColours.VS_CODE to true) to
            listOf("#0000FF", "#10708A", "#007900", "#A31515", "#00774D", "#10708A", "#795E26", "#800000", "#D20000"),
        (CodeColours.VS_CODE to false) to
            listOf("#7AC1FD", "#53CEB4", "#96C881", "#EAAC92", "#B5CEA8", "#53CEB4", "#DCDCAA", "#7AC1FD", "#9CDCFE"),
        (CodeColours.INTELLIJ to true) to
            listOf("#0033B3", "#786700", "#686768", "#007913", "#1750EB", "#212934", "#00627A", "#0033B3", "#871094"),
        (CodeColours.INTELLIJ to false) to
            listOf("#EEAB89", "#C2BD6E", "#B6BBC2", "#87CA90", "#52CBD7", "#FFFFFF", "#7FBFFF", "#D5B778", "#EEA1E0"),
    )

    @Test
    fun `VS Code's and IntelliJ's colours in Tonal are the published ones, moved only where they fall short`() {
        for ((key, colours) in inTonal) {
            val (choice, isLight) = key
            assertEquals(colours, syntaxOf(BuiltInStyles.TONAL, isLight, choice), "$choice $isLight")

            // A published colour that holds every check is drawn as published
            val published = choice.published(isLight)!!
            val palette = resolveStyleSide(BuiltInStyles.TONAL, isLight, choice).palette
            for ((slot, argb) in published) {
                if (argb == null) continue
                val holds = contrastChecks(AccentStyle.TONAL).filter { it.foreground == slot }
                    .all { palette.with(slot, Color(argb)).contrastOf(it) >= it.minimum }
                if (holds) assertEquals(StyleColorHex.format(argb), palette[slot].hex(), "$choice $isLight $slot")
            }
        }
    }

    @Test
    fun `each choice in every built-in style holds every check, keeps each colour's hue, and changes only code`() {
        for (choice in listOf(CodeColours.VS_CODE, CodeColours.INTELLIJ)) {
            for (style in BuiltInStyles.ALL) {
                for (isLight in sides) {
                    val what = "$choice ${style.id} $isLight"
                    val leaf = resolveStyleSide(style, isLight).palette
                    val resolved = resolveStyleSide(style, isLight, choice)
                    val palette = resolved.palette

                    assertEquals(emptyList<ContrastShortfall>(), resolved.shortfalls, what)
                    for (slot in StyleSlot.entries - StyleSlot.SYNTAX.toSet()) {
                        assertEquals(leaf[slot], palette[slot], "$what $slot")
                    }

                    for ((slot, argb) in choice.published(isLight)!!) {
                        if (argb == null) {
                            // IntelliJ leaves types in the text colour
                            assertEquals(palette[StyleSlot.TEXT], palette[slot], "$what $slot")
                            continue
                        }
                        val published = StyleColorMath.toOklch(argb)
                        val drawn = StyleColorMath.toOklch(palette[slot].toArgb())
                        if (published.c < 0.03) continue
                        val turn = abs(published.h - drawn.h).let { minOf(it, 360 - it) }
                        assertTrue(turn < 4.0, "$what $slot: ${StyleColorHex.format(argb)} -> ${palette[slot].hex()}")
                    }
                }
            }
        }
    }

    @Test
    fun `a choice other than Leaf replaces code colours set by hand`() {
        val solarized = resolveStyleSide(BuiltInStyles.SOLARIZED, isLight = true, CodeColours.VS_CODE).palette
        assertEquals("#0000FF", solarized[StyleSlot.KEYWORD].hex())
        assertEquals("#795E26", solarized[StyleSlot.FUNCTION].hex())
        // IntelliJ's type is Solarized's text, set by hand
        val intellij = resolveStyleSide(BuiltInStyles.SOLARIZED, isLight = true, CodeColours.INTELLIJ).palette
        assertEquals("#586E75", intellij[StyleSlot.TYPE].hex())
    }

    @Test
    fun `random seeds with each choice hold every check, and IntelliJ's types stay the text`() {
        val random = Random(41)
        fun randomColour() = (0xFF shl 24) or random.nextInt(0x1000000)

        repeat(300) { round ->
            for (look in AccentStyle.entries) {
                for (isLight in sides) {
                    val seeds = BuiltInStyles.templateSeeds(look, isLight)
                        .with(StyleSeed.BACKGROUND, randomColour())
                        .with(StyleSeed.ACCENT, randomColour())
                        .with(StyleSeed.WARNING, randomColour())
                        .with(StyleSeed.SUCCESS, randomColour())
                    val style = StyleDefinition("random", "Random", look, StyleSide(seeds), StyleSide(seeds))
                    for (choice in CodeColours.entries) {
                        val resolved = resolveStyleSide(style, isLight, choice)
                        val what = "$round $look $isLight $choice"
                        assertEquals(emptyList<ContrastShortfall>(), resolved.shortfalls, what)
                        // IntelliJ's types are the text, wherever the checks took it
                        if (choice == CodeColours.INTELLIJ) {
                            assertEquals(resolved.palette[StyleSlot.TEXT], resolved.palette[StyleSlot.TYPE], what)
                        }
                    }
                }
            }
        }
    }
}
