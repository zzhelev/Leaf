// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.highlighting.SyntaxCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CodeColoursTest {

    private fun hex(table: Map<StyleSlot, Int?>) =
        StyleSlot.SYNTAX.map { table.getValue(it)?.let(StyleColorHex::format) ?: "text" }

    @Test
    fun `each syntax category has its slot, in the same order`() {
        assertEquals(SyntaxCategory.entries.size, StyleSlot.SYNTAX.size)
        for (category in SyntaxCategory.entries) assertEquals(category.name, StyleSlot.of(category).name)
        assertEquals(StyleSlot.SYNTAX, StyleSlot.entries.filter { it.group == StyleSlotGroup.SYNTAX })
    }

    @Test
    fun `Leaf has no table, and VS Code's and IntelliJ's are the published ones`() {
        assertNull(CodeColours.LEAF.published(isLight = true))
        assertNull(CodeColours.LEAF.published(isLight = false))
        // Section 4.5 of the plan, row by row: keyword, annotation, comment, string, number, type, function, tag,
        // attribute
        assertEquals(
            listOf("#0000FF", "#267F99", "#008000", "#A31515", "#098658", "#267F99", "#795E26", "#800000", "#E50000"),
            hex(CodeColours.VS_CODE.published(isLight = true)!!),
        )
        assertEquals(
            listOf("#569CD6", "#4EC9B0", "#6A9955", "#CE9178", "#B5CEA8", "#4EC9B0", "#DCDCAA", "#569CD6", "#9CDCFE"),
            hex(CodeColours.VS_CODE.published(isLight = false)!!),
        )
        assertEquals(
            listOf("#0033B3", "#9E880D", "#8C8C8C", "#067D17", "#1750EB", "text", "#00627A", "#0033B3", "#871094"),
            hex(CodeColours.INTELLIJ.published(isLight = true)!!),
        )
        assertEquals(
            listOf("#CF8E6D", "#B3AE60", "#7A7E85", "#6AAB73", "#2AACB8", "text", "#56A8F5", "#D5B778", "#C77DBB"),
            hex(CodeColours.INTELLIJ.published(isLight = false)!!),
        )
    }

    @Test
    fun `a choice is read from its key, and anything else is none`() {
        for (choice in CodeColours.entries) assertEquals(choice, CodeColours.fromKey(choice.key))
        for (unknown in listOf(null, "", "VSCODE", "vs code", "sublime")) {
            assertNull(CodeColours.fromKey(unknown), "$unknown")
        }
        assertEquals(CodeColours.LEAF, CodeColours.DEFAULT)
    }
}
