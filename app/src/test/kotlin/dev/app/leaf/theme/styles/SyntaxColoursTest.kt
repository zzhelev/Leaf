// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme.styles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styled
import dev.app.leaf.theme.syntaxTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/** The nine colours of code, in every built-in style (fork-only). */
class SyntaxColoursTest {
    private val sides = listOf(true, false)

    private fun Color.hex() = StyleColorHex.format(toArgb())

    private fun syntaxOf(style: StyleDefinition, isLight: Boolean) =
        resolveStyleSide(style, isLight).palette.let { palette -> StyleSlot.SYNTAX.map { palette[it].hex() } }

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
}
