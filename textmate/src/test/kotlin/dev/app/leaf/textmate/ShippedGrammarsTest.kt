// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.app.leaf.textmate

import dev.textmate.grammar.raw.GrammarReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShippedGrammarsTest {

    @Test
    fun `every grammar in the index is a file whose scope is the index's`() {
        assertTrue(ShippedGrammars.all.size > 60, "${ShippedGrammars.all.size} grammars")
        for (entry in ShippedGrammars.all) {
            val text = javaClass.classLoader.getResourceAsStream("grammars/${entry.file}")!!.use { it.readBytes() }
            assertEquals(entry.scopeName, GrammarReader.readGrammar(text.decodeToString()).scopeName, entry.name)
        }
    }

    @Test
    fun `every grammar loads, with what it embeds, and tokenizes a line`() {
        val names = ShippedGrammars.all.map { it.name }.toSet()
        for (entry in ShippedGrammars.all) {
            assertTrue(names.containsAll(entry.embedded), "${entry.name} embeds ${entry.embedded - names}")
            val grammar = ShippedGrammars.loadGrammar(ShippedGrammars.newRegistry(), entry.scopeName)
            assertNotNull(grammar, entry.name)
            val tokens = grammar!!.tokenizeLine("x = \"y\" // z").tokens
            assertEquals(entry.scopeName, tokens.first().scopes.first(), entry.name)
        }
    }

    @Test
    fun `a grammar is read once and shared`() {
        assertSame(ShippedGrammars.rawGrammar("source.kotlin"), ShippedGrammars.rawGrammar("source.kotlin"))
        assertNull(ShippedGrammars.rawGrammar("source.nothing"))
        assertNull(ShippedGrammars.loadGrammar(ShippedGrammars.newRegistry(), "source.nothing"))
    }

    @Test
    fun `injections are found by the scopes they name`() {
        assertEquals(
            setOf("markdown.vue.codeblock", "vue.directives", "vue.interpolations").sorted(),
            ShippedGrammars.injectionsInto("text.html.markdown").sorted(),
        )
        assertEquals(emptyList<String>(), ShippedGrammars.injectionsInto("source.kotlin"))
    }

    @Test
    fun `the notice covers every shipped grammar`() {
        val notice = javaClass.classLoader.getResourceAsStream("grammars/NOTICE")!!.use { it.readBytes().decodeToString() }
        val covered = Regex("""^Files:\s+(.*)$""", RegexOption.MULTILINE).findAll(notice)
            .flatMap { it.groupValues[1].split(Regex(""",\s*""")) }.toSet()
        assertEquals(ShippedGrammars.all.map { it.file }.toSet(), covered)
    }
}
