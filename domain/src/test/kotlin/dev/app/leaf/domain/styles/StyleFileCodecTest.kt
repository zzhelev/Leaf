// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.models.ui.AccentStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StyleFileCodecTest {
    private fun read(text: String, id: String = "teal"): StyleFileRead.Read =
        assertInstanceOf(StyleFileRead.Read::class.java, StyleFileCodec.decode(text, id))

    private fun unreadable(text: String): StyleFileRead.Unreadable =
        assertInstanceOf(StyleFileRead.Unreadable::class.java, StyleFileCodec.decode(text, "x"))

    private val teal = StyleDefinition(
        id = "teal",
        name = "Teal",
        look = AccentStyle.QUIET,
        light = StyleSide(
            StyleSeeds(
                background = 0xFFFFFFFF.toInt(),
                accent = 0xFF007475.toInt(),
                danger = 0xFFB4405A.toInt(),
                warning = 0xFFE0A03A.toInt(),
                success = 0xFF3F8F52.toInt(),
            ),
            // Not in the slots' order, which the file keeps
            setByHand = mapOf(StyleSlot.ADDED_LINE to 0xAAD7EBD0.toInt(), StyleSlot.TEXT to 0xFF1D2B2B.toInt()),
        ),
        dark = StyleSide(
            StyleSeeds(
                background = 0xFF0F0F0F.toInt(),
                accent = 0xFF007475.toInt(),
                danger = 0xFFB4405A.toInt(),
                warning = 0xFFE0A03A.toInt(),
                success = 0xFF3F8F52.toInt(),
            ),
        ),
    )

    @Test
    fun `a style's file is the format's version, the name, the look and both sides, with no id`() {
        val expected = """
            {
              "leafStyle": 1,
              "name": "Teal",
              "look": "quiet",
              "light": {
                "seeds": {
                  "background": "#FFFFFF",
                  "accent": "#007475",
                  "danger": "#B4405A",
                  "warning": "#E0A03A",
                  "success": "#3F8F52"
                },
                "setByHand": {
                  "text": "#1D2B2B",
                  "addedLine": "#D7EBD0AA"
                }
              },
              "dark": {
                "seeds": {
                  "background": "#0F0F0F",
                  "accent": "#007475",
                  "danger": "#B4405A",
                  "warning": "#E0A03A",
                  "success": "#3F8F52"
                },
                "setByHand": {}
              }
            }

        """.trimIndent()

        assertEquals(expected, StyleFileCodec.encode(teal))
    }

    @Test
    fun `a file reads back as the style it was written from, with no problems`() {
        val result = read(StyleFileCodec.encode(teal))

        assertEquals(teal, result.definition)
        assertEquals(emptyList<String>(), result.problems)
    }

    @Test
    fun `random styles read back as they were written, colours set by hand and see-through ones included`() {
        val random = Random(20261022)

        repeat(500) { round ->
            fun opaque() = random.nextInt() or 0xFF000000.toInt()
            fun side() = StyleSide(
                StyleSeeds(opaque(), opaque(), opaque(), opaque(), opaque()),
                StyleSlot.entries.filter { random.nextInt(4) == 0 }.associateWith { random.nextInt() },
            )

            val definition = StyleDefinition(
                id = "style-$round",
                name = "Style ${random.nextInt()} \"quoted\" ✓",
                look = AccentStyle.entries.random(random),
                light = side(),
                dark = side(),
            )
            val result = read(StyleFileCodec.encode(definition), id = definition.id)

            assertEquals(definition, result.definition)
            assertEquals(emptyList<String>(), result.problems)
        }
    }

    @Test
    fun `the id is the file's, whatever the file says`() {
        assertEquals("other", read(StyleFileCodec.encode(teal), id = "other").definition.id)
    }

    @Test
    fun `every slot and seed has its own key, and the keys stay as files have them`() {
        assertEquals(StyleSlot.entries.size, StyleSlot.entries.map { it.key }.toSet().size)
        assertEquals(StyleSeed.entries.size, StyleSeed.entries.map { it.key }.toSet().size)
        StyleSlot.entries.forEach { assertEquals(it, StyleSlot.fromKey(it.key)) }

        // A key that changes makes every file that sets that colour lose it
        val keys = listOf(
            "background", "panels", "boxes", "headers", "selectedRow", "dialogOverlay", "scrollbar", "scrollbarHover",
            "text", "secondaryText", "links", "highlight", "onHighlight", "error", "onError",
            "addedFile", "modifiedFile", "deletedFile", "conflictedFile",
            "addedLine", "addedWords", "removedLine", "removedWords", "keyword", "annotation", "comment",
            "lane1", "lane2", "lane3", "lane4", "lane5", "lane6",
            "mainAction.fill", "mainAction.text",
            "action.fill", "action.text", "action.edge",
            "neutral.fill", "neutral.text", "neutral.edge",
            "destructive.fill", "destructive.text", "destructive.edge",
            "warningStrip.fill", "warningStrip.text", "warningStrip.icon", "warningStrip.edge",
            "warningStrip.button.fill", "warningStrip.button.text", "warningStrip.button.edge",
            "successStrip.fill", "successStrip.text", "successStrip.icon", "successStrip.edge",
            "successStrip.button.fill", "successStrip.button.text", "successStrip.button.edge",
            "warningIcon",
        )
        assertEquals(keys, StyleSlot.entries.map { it.key })
        assertEquals(listOf("background", "accent", "danger", "warning", "success"), StyleSeed.entries.map { it.key })
        assertEquals(StyleSlot.entries.filter { it.group == StyleSlotGroup.GRAPH_LANES }, StyleSlot.LANES)
    }

    @Test
    fun `colours are #RRGGBB, or #RRGGBBAA when see-through`() {
        assertEquals("#0070D8", StyleColorHex.format(0xFF0070D8.toInt()))
        assertEquals("#D7EBD0AA", StyleColorHex.format(0xAAD7EBD0.toInt()))
        assertEquals("#00000000", StyleColorHex.format(0))

        assertEquals(0xFF0070D8.toInt(), StyleColorHex.parse("#0070d8"))
        assertEquals(0xAAD7EBD0.toInt(), StyleColorHex.parse(" #d7ebd0aa "))

        for (text in listOf("", "#", "#FFF", "FFFFFF", "#FFFFFFF", "#GGGGGG", "#FFFFFF FF", "0xFFFFFFFF", "#FFFFFFFFFF")) {
            assertNull(StyleColorHex.parse(text), text)
        }

        val random = Random(7)
        repeat(10_000) {
            val argb = random.nextInt()
            assertEquals(argb, StyleColorHex.parse(StyleColorHex.format(argb)))
        }
    }

    @Test
    fun `text that isn't JSON can't be read`() {
        assertEquals(UnreadableStyle.NOT_JSON, unreadable("{ \"leafStyle\": 1, ").reason)
        assertEquals(UnreadableStyle.NOT_JSON, unreadable("Solarized").reason)
        assertEquals(UnreadableStyle.NOT_JSON, unreadable("").reason)
    }

    @Test
    fun `JSON without a version can't be read, the old custom themes included`() {
        // The README's example of a custom theme, from before 2026-10-22
        val oldCustomTheme = """{ "primary": "FF456b00", "background": "FFe7f2d3", "isLight": true }"""

        for (text in listOf(
            oldCustomTheme,
            "[1, 2]",
            "\"leafStyle\"",
            """{ "name": "Teal" }""",
            """{ "leafStyle": "1", "name": "Teal" }""",
            """{ "leafStyle": 0, "name": "Teal" }""",
            """{ "leafStyle": 1.5, "name": "Teal" }""",
        )) {
            assertEquals(UnreadableStyle.NOT_A_STYLE, unreadable(text).reason, text)
        }
    }

    @Test
    fun `a style needs only its version, and then has the look's colours and its id as its name`() {
        val result = read("""{ "leafStyle": 1 }""", id = "bare")

        assertEquals("bare", result.definition.name)
        assertEquals(AccentStyle.TONAL, result.definition.look)
        assertEquals(BuiltInStyles.TONAL.light, result.definition.light)
        assertEquals(BuiltInStyles.TONAL.dark, result.definition.dark)
        assertTrue(result.problems.isNotEmpty())
    }

    @Test
    fun `an unknown look reads as Tonal, and a blank name as the id`() {
        val result = read("""{ "leafStyle": 1, "name": "  ", "look": "loud" }""")

        assertEquals(AccentStyle.TONAL, result.definition.look)
        assertEquals("teal", result.definition.name)
        assertTrue(result.problems.any { "loud" in it }, "${result.problems}")
    }

    @Test
    fun `a missing or broken seed is the look's, and a see-through one is made opaque`() {
        val result = read(
            """
            {
              "leafStyle": 1,
              "name": "Partly",
              "look": "classic",
              "light": { "seeds": { "accent": "#007475", "danger": "red", "warning": "#E0A03A80", "shade": "#000000" } }
            }
            """.trimIndent()
        )
        val classic = BuiltInStyles.templateSeeds(AccentStyle.CLASSIC, isLight = true)
        val seeds = result.definition.light.seeds

        assertEquals(0xFF007475.toInt(), seeds.accent)
        assertEquals(classic.danger, seeds.danger)
        assertEquals(0xFFE0A03A.toInt(), seeds.warning)
        assertEquals(classic.background, seeds.background)
        assertEquals(classic.success, seeds.success)
        assertEquals(BuiltInStyles.templateSeeds(AccentStyle.CLASSIC, isLight = false), result.definition.dark.seeds)

        val problems = result.problems.joinToString("\n")
        for (word in listOf("danger", "see-through", "shade", "background", "success", "dark")) {
            assertTrue(word in problems, "$word in\n$problems")
        }
    }

    @Test
    fun `unknown and broken colours set by hand are left out, the others kept`() {
        val result = read(
            """
            {
              "leafStyle": 1,
              "name": "Partly",
              "look": "tonal",
              "light": {
                "seeds": { "background": "#FDF6E3", "accent": "#268BD2", "danger": "#DC322F", "warning": "#B58900", "success": "#859900" },
                "setByHand": { "text": "#586E75", "glow": "#FFFFFF", "keyword": 12, "comment": "#9" }
              },
              "dark": {
                "seeds": { "background": "#002B36", "accent": "#268BD2", "danger": "#DC322F", "warning": "#B58900", "success": "#859900" },
                "setByHand": "all of them"
              }
            }
            """.trimIndent()
        )

        assertEquals(mapOf(StyleSlot.TEXT to 0xFF586E75.toInt()), result.definition.light.setByHand)
        assertEquals(emptyMap<StyleSlot, Int>(), result.definition.dark.setByHand)
        assertEquals(0xFF002B36.toInt(), result.definition.dark.seeds.background)

        val problems = result.problems.joinToString("\n")
        for (word in listOf("glow", "keyword", "comment", "aren't a JSON object")) {
            assertTrue(word in problems, "$word in\n$problems")
        }
    }

    @Test
    fun `a newer format is read as this one, and says so`() {
        val text = StyleFileCodec.encode(teal).replace("\"leafStyle\": 1", "\"leafStyle\": 3")
        val result = read(text)

        assertEquals(teal, result.definition)
        assertTrue(result.problems.single().contains("newer"), "${result.problems}")
    }
}
