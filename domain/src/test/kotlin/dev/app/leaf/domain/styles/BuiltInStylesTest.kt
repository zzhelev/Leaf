// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.models.ui.AccentStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuiltInStylesTest {
    @Test
    fun `the built-in styles' ids are what the setting stored before styles could be made`() {
        // accent_style held the look's name in lower case from 2026-10-21
        for (look in AccentStyle.entries) {
            val style = BuiltInStyles.byId(look.name.lowercase())

            assertEquals(look, style?.look)
            assertEquals(style, BuiltInStyles.ofLook(look))
        }
        assertEquals("tonal", BuiltInStyles.DEFAULT_ID)
        assertEquals(BuiltInStyles.TONAL, BuiltInStyles.DEFAULT)
    }

    @Test
    fun `each built-in style is listed once, Tonal first, and is built in`() {
        assertEquals(BuiltInStyles.TONAL, BuiltInStyles.ALL.first())
        assertEquals(BuiltInStyles.ALL.size, BuiltInStyles.ALL.map { it.id }.toSet().size)
        BuiltInStyles.ALL.forEach { assertTrue(it.isBuiltIn, it.id) }

        assertFalse(BuiltInStyles.isBuiltIn("teal"))
        assertNull(BuiltInStyles.byId("teal"))
        assertNull(BuiltInStyles.byId(null))
    }

    @Test
    fun `Solarized comes last, in the Tonal look, with Solarized's seeds and published colours set by hand`() {
        val solarized = BuiltInStyles.SOLARIZED

        assertEquals(
            listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.QUIET, solarized),
            BuiltInStyles.ALL,
        )
        assertEquals(solarized, BuiltInStyles.byId("solarized"))
        assertEquals("Solarized", solarized.name)
        assertEquals(AccentStyle.TONAL, solarized.look)
        // Code that only knows a look still gets the look's own style
        assertEquals(BuiltInStyles.TONAL, BuiltInStyles.ofLook(AccentStyle.TONAL))

        fun hex(slots: Map<StyleSlot, Int>) = slots.map { (slot, argb) -> slot.key to StyleColorHex.format(argb) }.toMap()
        val seeds = { side: StyleSide -> StyleSeed.entries.associate { it.name to StyleColorHex.format(side.seeds[it]) } }

        assertEquals(
            mapOf("BACKGROUND" to "#FDF6E3", "ACCENT" to "#268BD2", "DANGER" to "#DC322F", "WARNING" to "#B58900", "SUCCESS" to "#859900"),
            seeds(solarized.light),
        )
        assertEquals("#002B36", StyleColorHex.format(solarized.dark.seeds.background))
        assertEquals(solarized.light.seeds.copy(background = solarized.dark.seeds.background), solarized.dark.seeds)

        val lanes = mapOf(
            "lane1" to "#268BD2", "lane2" to "#DC322F", "lane3" to "#2AA198",
            "lane4" to "#CB4B16", "lane5" to "#859900", "lane6" to "#D33682",
        )
        assertEquals(
            mapOf(
                "text" to "#586E75", "secondaryText" to "#516770", "comment" to "#657B83",
                "keyword" to "#268BD2", "annotation" to "#B58900",
            ) + lanes,
            hex(solarized.light.setByHand),
        )
        assertEquals(
            mapOf(
                "panels" to "#073642", "text" to "#93A1A1", "secondaryText" to "#8A9C9E", "comment" to "#839496",
                "keyword" to "#268BD2", "annotation" to "#B58900",
            ) + lanes,
            hex(solarized.dark.setByHand),
        )
    }

    @Test
    fun `Classic, Tonal and Quiet set nothing by hand, and their seeds are their look's`() {
        for (style in listOf(BuiltInStyles.CLASSIC, BuiltInStyles.TONAL, BuiltInStyles.QUIET)) {
            for (isLight in listOf(true, false)) {
                val side = style.side(isLight)

                assertEquals(emptyMap<StyleSlot, Int>(), side.setByHand, "${style.id} $isLight")
                assertEquals(BuiltInStyles.templateSeeds(style.look, isLight), side.seeds, "${style.id} $isLight")
            }
        }
    }

    @Test
    fun `the looks' seeds are the colours their buttons and notices are built around`() {
        val classicLight = BuiltInStyles.templateSeeds(AccentStyle.CLASSIC, isLight = true)
        val tonalDark = BuiltInStyles.templateSeeds(AccentStyle.TONAL, isLight = false)

        assertEquals(0xFFFFFFFF.toInt(), classicLight.background)
        assertEquals(0xFF0070D8.toInt(), classicLight.accent)
        assertEquals(0xFFFFB638.toInt(), classicLight.warning)
        assertEquals(0xFF0F0F0F.toInt(), tonalDark.background)
        assertEquals(0xFF3464A0.toInt(), tonalDark.accent)
        assertEquals(0xFFE6B463.toInt(), tonalDark.warning)
        assertEquals(
            BuiltInStyles.templateSeeds(AccentStyle.TONAL, isLight = true),
            BuiltInStyles.templateSeeds(AccentStyle.QUIET, isLight = true),
        )
    }

    @Test
    fun `a seed is read and replaced by its key`() {
        val seeds = BuiltInStyles.TONAL.light.seeds

        for (seed in StyleSeed.entries) {
            val changed = seeds.with(seed, 0x12345678)

            assertEquals(0x12345678, changed[seed])
            StyleSeed.entries.filter { it != seed }.forEach { assertEquals(seeds[it], changed[it], "$seed → $it") }
            assertEquals(seed, StyleSeed.fromKey(seed.key))
        }
    }
}
