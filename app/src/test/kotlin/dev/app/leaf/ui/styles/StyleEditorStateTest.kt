// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StyleEditorStateTest {
    private val solarizedBlue = 0xFF268BD2.toInt()
    private val tonalCopy = StyleEditorState.startingFrom(BuiltInStyles.TONAL, id = "", name = "Tonal copy", fromName = "Tonal")

    @Test
    fun `a style of the user's opens unchanged, and a new one counts as a change until saved`() {
        val teal = BuiltInStyles.QUIET.copy(id = "teal", name = "Teal")
        val editing = StyleEditorState.editing(teal)

        assertFalse(editing.hasChanges)
        assertFalse(editing.isNew)
        assertTrue(editing.renamed("Deep teal").hasChanges)

        assertTrue(tonalCopy.isNew)
        assertTrue(tonalCopy.hasChanges)
        assertEquals("Tonal copy", tonalCopy.draft.name)
        assertEquals("Tonal", tonalCopy.startedFrom)
        assertEquals(BuiltInStyles.TONAL.light, tonalCopy.draft.light)
    }

    @Test
    fun `Accent, Danger, Warning and Success set both sides, and the background one side at a time`() {
        val accent = tonalCopy.withSeed(StyleSeed.ACCENT, solarizedBlue)

        assertEquals(solarizedBlue, accent.draft.light.seeds.accent)
        assertEquals(solarizedBlue, accent.draft.dark.seeds.accent)

        val background = tonalCopy.withSeed(StyleSeed.BACKGROUND, 0xFFFDF6E3.toInt(), isLight = true)
        assertEquals(0xFFFDF6E3.toInt(), background.draft.light.seeds.background)
        assertEquals(BuiltInStyles.TONAL.dark.seeds.background, background.draft.dark.seeds.background)

        val dark = tonalCopy.withSeed(StyleSeed.BACKGROUND, 0xFF002B36.toInt(), isLight = false)
        assertEquals(BuiltInStyles.TONAL.light.seeds.background, dark.draft.light.seeds.background)
        assertEquals(0xFF002B36.toInt(), dark.draft.dark.seeds.background)
    }

    @Test
    fun `a seed is always opaque`() {
        assertEquals(0xFF268BD2.toInt(), tonalCopy.withSeed(StyleSeed.ACCENT, 0x40268BD2).draft.light.seeds.accent)
    }

    @Test
    fun `a colour set by hand is on one side, and working it out again takes it away`() {
        val set = tonalCopy.withColourSetByHand(StyleSlot.TEXT, isLight = true, 0xFF586E75.toInt())

        assertTrue(set.isSetByHand(StyleSlot.TEXT, isLight = true))
        assertFalse(set.isSetByHand(StyleSlot.TEXT, isLight = false))
        assertEquals(mapOf(StyleSlot.TEXT to 0xFF586E75.toInt()), set.draft.light.setByHand)

        val workedOut = set.withWorkedOutColour(StyleSlot.TEXT, isLight = true)
        assertFalse(workedOut.isSetByHand(StyleSlot.TEXT, isLight = true))
        assertEquals(tonalCopy.draft, workedOut.draft)
    }

    @Test
    fun `revert goes back to the style as saved, and closes the picker`() {
        val teal = BuiltInStyles.QUIET.copy(id = "teal", name = "Teal")
        val changed = StyleEditorState.editing(teal)
            .renamed("Other")
            .withLook(AccentStyle.CLASSIC)
            .withSeed(StyleSeed.DANGER, 0xFF00FF00.toInt())
            .withPicker(ColourTarget.Seed(StyleSeed.DANGER))

        val reverted = changed.reverted()

        assertEquals(teal, reverted.draft)
        assertEquals(null, reverted.picker)
        assertFalse(reverted.hasChanges)
    }

    @Test
    fun `the Advanced table has every colour once`() {
        val slots = ADVANCED_GROUPS.flatMap { group -> group.rows.flatMap { row -> row.slots.map { it.first } } }

        assertEquals(StyleSlot.entries.toSet(), slots.toSet())
        assertEquals(StyleSlot.entries.size, slots.size)
        StyleSlot.entries.forEach { assertTrue(rowOf(it).first.slots.any { (slot, _) -> slot == it }) }
        // A row of one colour names no part, a row of several names each
        for (row in ADVANCED_GROUPS.flatMap { it.rows }) {
            if (row.slots.size == 1) assertEquals(null, row.slots.single().second) else assertTrue(row.slots.all { it.second != null })
        }
    }

    @Test
    fun `the palette has 12 hues by 6 shades, a row of greys from white, and every colour is opaque`() {
        assertEquals(7, PALETTE.size)
        PALETTE.forEach { assertEquals(12, it.size) }
        assertEquals(0xFFFFFFFF.toInt(), PALETTE.last().first())
        PALETTE.flatten().forEach { assertEquals(0xFF, StyleColorMath.alpha(it)) }

        // A shade has one lightness in every hue
        for (row in PALETTE.dropLast(1)) {
            val lightness = row.map { StyleColorMath.toOklch(it).l }
            assertTrue(lightness.max() - lightness.min() < 0.02, "$lightness")
        }
    }
}
