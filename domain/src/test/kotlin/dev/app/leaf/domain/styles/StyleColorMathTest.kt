// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class StyleColorMathTest {
    private fun hex(text: String) = checkNotNull(StyleColorHex.parse(text))

    private fun hueDistance(a: Double, b: Double) = abs((a - b + 540) % 360 - 180)

    @Test
    fun `OKLab gives Ottosson's values for white, black and Leaf's muted blue`() {
        val white = StyleColorMath.toOklch(hex("#FFFFFF"))
        val black = StyleColorMath.toOklch(hex("#000000"))
        val blue = StyleColorMath.toOklch(hex("#376AAA"))

        assertEquals(1.0, white.l, 1e-6)
        assertEquals(0.0, white.c, 1e-4)
        assertEquals(0.0, black.l, 1e-6)
        assertEquals(0.520, blue.l, 1e-3)
        assertEquals(0.116, blue.c, 1e-3)
        assertEquals(255.2, blue.h, 0.1)
    }

    @Test
    fun `every 8-bit colour comes back from OKLab as it was, its alpha too`() {
        val random = Random(1)
        val samples = (0 until 100_000).map { random.nextInt() } +
            listOf(0, -1, 0xFF000000.toInt(), 0x00FFFFFF, 0xFF0F0F0F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())

        for (argb in samples) {
            val back = StyleColorMath.fromOklab(StyleColorMath.toOklab(argb), StyleColorMath.alpha(argb))
            assertEquals(StyleColorHex.format(argb), StyleColorHex.format(back))
        }
    }

    @Test
    fun `a colour beyond sRGB loses chroma, not lightness or hue`() {
        val random = Random(2)

        repeat(2_000) {
            val wanted = Oklch(random.nextDouble(0.1, 0.95), random.nextDouble(0.0, 0.5), random.nextDouble(0.0, 360.0))
            val argb = StyleColorMath.fromOklch(wanted)
            val got = StyleColorMath.toOklch(argb)

            assertEquals(wanted.l, got.l, 0.01, "$wanted → ${StyleColorHex.format(argb)}")
            assertTrue(got.c <= wanted.c + 0.005, "$wanted → $got")
            // 8 bits are too coarse to keep the hue of a nearly grey colour to the degree
            if (got.c > 0.06) assertTrue(hueDistance(wanted.h, got.h) < 3.0, "$wanted → $got")
        }

        // In sRGB already: kept as it is
        assertEquals(hex("#376AAA"), StyleColorMath.fromOklch(StyleColorMath.toOklch(hex("#376AAA"))))
    }

    @Test
    fun `contrast and luminance are WCAG's`() {
        assertEquals(21.0, StyleColorMath.contrast(hex("#000000"), hex("#FFFFFF")), 1e-9)
        assertEquals(1.0, StyleColorMath.contrast(hex("#376AAA"), hex("#376AAA")), 1e-9)
        assertEquals(5.52, StyleColorMath.contrast(hex("#FFFFFF"), hex("#376AAA")), 0.01)
        assertEquals(3.68, StyleColorMath.contrast(hex("#268BD2"), hex("#FFFFFF")), 0.01)
        // The order doesn't matter
        assertEquals(
            StyleColorMath.contrast(hex("#586E75"), hex("#FDF6E3")),
            StyleColorMath.contrast(hex("#FDF6E3"), hex("#586E75")),
        )
    }

    @Test
    fun `a see-through colour is laid over the one under it per channel, as Compose draws it`() {
        assertEquals(hex("#376AAA"), StyleColorMath.composite(hex("#376AAA"), hex("#FFFFFF")))
        assertEquals(hex("#808080"), StyleColorMath.composite(hex("#FFFFFF80"), hex("#000000")))
        assertEquals(hex("#FFFFFF"), StyleColorMath.composite(0, hex("#FFFFFF")))
        // Leaf's dark neutral button: white at 8% on #0F0F0F
        assertEquals(hex("#222222"), StyleColorMath.composite(hex("#FFFFFF14"), hex("#0F0F0F")))
        // Layers, the first on top
        assertEquals(
            StyleColorMath.composite(hex("#FF000080"), StyleColorMath.composite(hex("#0000FF80"), hex("#FFFFFF"))),
            StyleColorMath.composite(listOf(hex("#FF000080"), hex("#0000FF80"), hex("#FFFFFF"))),
        )
    }

    @Test
    fun `moving for contrast keeps a colour that holds, and moves one that doesn't just far enough`() {
        val white = hex("#FFFFFF")
        val ratioOnWhite = { argb: Int -> StyleColorMath.contrast(argb, white) }

        assertEquals(hex("#376AAA"), StyleColorMath.movedForContrast(hex("#376AAA"), 4.5, ratio = ratioOnWhite))

        val solarizedBlue = hex("#268BD2")
        val moved = checkNotNull(StyleColorMath.movedForContrast(solarizedBlue, 4.5, ratio = ratioOnWhite))
        val before = StyleColorMath.toOklch(solarizedBlue)
        val after = StyleColorMath.toOklch(moved)

        assertTrue(ratioOnWhite(moved) >= 4.5)
        assertTrue(ratioOnWhite(moved) < 4.7, "${ratioOnWhite(moved)}: further than it had to")
        assertTrue(after.l < before.l, "darker on white")
        assertTrue(hueDistance(before.h, after.h) < 3.0, "$before → $after")
    }

    @Test
    fun `moving for contrast goes to the side with less to go, and gives up when neither side can`() {
        val grey = hex("#777777")
        // Mid grey: 4.5 : 1 against #777777 takes going to black's side or to white's
        val ratioOnGrey = { argb: Int -> StyleColorMath.contrast(argb, grey) }

        val lightGrey = checkNotNull(StyleColorMath.movedForContrast(hex("#999999"), 3.0, ratio = ratioOnGrey))
        assertTrue(StyleColorMath.relativeLuminance(lightGrey) > StyleColorMath.relativeLuminance(grey))

        val darkGrey = checkNotNull(StyleColorMath.movedForContrast(hex("#666666"), 3.0, ratio = ratioOnGrey))
        assertTrue(StyleColorMath.relativeLuminance(darkGrey) < StyleColorMath.relativeLuminance(grey))

        assertNull(StyleColorMath.movedForContrast(grey, 22.0, ratio = ratioOnGrey))
    }

    @Test
    fun `moving for contrast towards one side stays on it, even when the other is nearer`() {
        val grey = hex("#777777")
        val ratioOnGrey = { argb: Int -> StyleColorMath.contrast(argb, grey) }
        val luminance = StyleColorMath::relativeLuminance

        // #999999 is nearer white's side, but is sent to black's
        val darker = checkNotNull(StyleColorMath.movedForContrast(hex("#999999"), 3.0, StyleColorMath.DARKER, ratio = ratioOnGrey))
        assertTrue(luminance(darker) < luminance(grey))
        assertTrue(ratioOnGrey(darker) >= 3.0)

        val lighter = checkNotNull(StyleColorMath.movedForContrast(hex("#666666"), 3.0, StyleColorMath.LIGHTER, ratio = ratioOnGrey))
        assertTrue(luminance(lighter) > luminance(grey))

        // White's side can't reach 4.5 : 1 against #808080, so a colour kept to it doesn't move at all
        val ratioOnMid = { argb: Int -> StyleColorMath.contrast(argb, hex("#808080")) }
        assertNull(StyleColorMath.movedForContrast(hex("#F8F8F8"), 4.5, StyleColorMath.LIGHTER, ratio = ratioOnMid))
        assertTrue(StyleColorMath.movedForContrast(hex("#F8F8F8"), 4.5, ratio = ratioOnMid) != null)
    }

    @Test
    fun `moving keeps alpha, and holds for random colours on random backgrounds`() {
        val random = Random(3)

        repeat(1_000) {
            val background = random.nextInt() or 0xFF000000.toInt()
            val color = random.nextInt() or 0xFF000000.toInt()
            val ratio = { argb: Int -> StyleColorMath.contrast(argb, background) }
            val moved = StyleColorMath.movedForContrast(color, 4.5, ratio = ratio)

            // Black or white holds 4.5 : 1 on any colour
            checkNotNull(moved)
            assertTrue(ratio(moved) >= 4.5, "${StyleColorHex.format(color)} on ${StyleColorHex.format(background)}")
        }

        val seeThrough = hex("#268BD280")
        val moved = checkNotNull(StyleColorMath.movedForContrast(seeThrough, 2.0) { StyleColorMath.contrast(it, hex("#FFFFFF")) })
        assertEquals(0x80, StyleColorMath.alpha(moved))
    }

    @Test
    fun `following a seed's hue keeps the colour's lightness and turns it as far as the seed turned`() {
        val tonalAccent = hex("#376AAA")
        val solarizedBlue = hex("#268BD2")
        val tonalAction = hex("#DDEBFE")

        // The same seed: the colour, exactly
        assertEquals(tonalAction, StyleShifts.followHue(tonalAction, tonalAccent, tonalAccent))

        val followed = StyleColorMath.toOklch(StyleShifts.followHue(tonalAction, tonalAccent, solarizedBlue))
        val own = StyleColorMath.toOklch(tonalAction)
        val turn = StyleColorMath.toOklch(solarizedBlue).h - StyleColorMath.toOklch(tonalAccent).h

        assertEquals(own.l, followed.l, 0.01)
        assertEquals(own.h + turn, followed.h, 3.0)

        // The main action of Solarized in Tonal: Tonal's lightness, Solarized's blue
        val main = StyleColorMath.toOklch(StyleShifts.followHue(tonalAccent, tonalAccent, solarizedBlue))
        assertEquals(0.520, main.l, 0.01)
        assertEquals(244.9, main.h, 2.0)

        // A grey seed makes the family grey
        assertTrue(StyleColorMath.toOklch(StyleShifts.followHue(tonalAction, tonalAccent, hex("#808080"))).c < 0.003)
        // See-through stays see-through, and transparent stays transparent
        assertEquals(0x47, StyleColorMath.alpha(StyleShifts.followHue(hex("#25569147"), tonalAccent, solarizedBlue)))
        assertEquals(0, StyleShifts.followHue(0, tonalAccent, solarizedBlue))
    }

    @Test
    fun `surfaces move with the background, and text keeps its lightness`() {
        val white = hex("#FFFFFF")
        val cream = hex("#FDF6E3")
        val panel = hex("#E9ECEF")

        assertEquals(panel, StyleShifts.followBackground(panel, white, white))

        val moved = StyleColorMath.toOklab(StyleShifts.followBackground(panel, white, cream))
        val creamLab = StyleColorMath.toOklab(cream)
        val panelLab = StyleColorMath.toOklab(panel)

        assertEquals(panelLab.l + creamLab.l - 1.0, moved.l, 0.005)
        assertEquals(panelLab.b + creamLab.b, moved.b, 0.005)

        // The background itself becomes the seed
        assertEquals(cream, StyleShifts.followBackground(white, white, cream))

        val text = hex("#212934")
        val movedText = StyleColorMath.toOklab(StyleShifts.followBackgroundAsText(text, white, cream))
        assertEquals(StyleColorMath.toOklab(text).l, movedText.l, 0.005)
        assertEquals(StyleColorMath.toOklab(text).b + creamLab.b / 2, movedText.b, 0.005)

        // A see-through colour already shows the background
        assertEquals(hex("#FFFFFF14"), StyleShifts.followBackground(hex("#FFFFFF14"), white, cream))
        assertNotEquals(panel, StyleShifts.followBackground(panel, white, hex("#0F0F0F")))
    }
}
