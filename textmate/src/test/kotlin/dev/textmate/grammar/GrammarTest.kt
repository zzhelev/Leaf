package dev.textmate.grammar

import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.tokenize.INITIAL
import dev.textmate.regex.JoniOnigLib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GrammarTest {

    private lateinit var grammar: Grammar

    private fun loadGrammar(resourcePath: String): Grammar {
        val rawGrammar = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.use { stream -> GrammarReader.readGrammar(stream) }
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        return Grammar(rawGrammar.scopeName, rawGrammar, JoniOnigLib())
    }

    @BeforeEach
    fun setUp() {
        grammar = loadGrammar("grammars/JSON.tmLanguage.json")
    }

    @Test
    fun `tokenize empty string`() {
        val result = grammar.tokenizeLine("")
        assertEquals(1, result.tokens.size, "Should have exactly one token")
        val token = result.tokens[0]
        assertEquals(0, token.startIndex)
        assertTrue(token.scopes.contains("source.json"), "Token should include source.json")
    }

    @Test
    fun `tokenize JSON boolean`() {
        val result = grammar.tokenizeLine("true")
        assertEquals(1, result.tokens.size, "Should have exactly one token")
        val token = result.tokens[0]
        assertEquals(0, token.startIndex)
        assertEquals(4, token.endIndex)
        assertTrue(token.scopes.contains("source.json"))
        assertTrue(token.scopes.contains("constant.language.json"))
    }

    @Test
    fun `tokenize JSON number`() {
        val result = grammar.tokenizeLine("42")
        assertEquals(1, result.tokens.size, "Should have exactly one token")
        val token = result.tokens[0]
        assertEquals(0, token.startIndex)
        assertEquals(2, token.endIndex)
        assertTrue(token.scopes.contains("source.json"))
        assertTrue(token.scopes.contains("constant.numeric.json"))
    }

    @Test
    fun `tokenize JSON object`() {
        val result = grammar.tokenizeLine("""{"key": "value"}""")
        val hasStringScope = result.tokens.any { token ->
            token.scopes.contains("string.quoted.double.json")
        }
        assertTrue(hasStringScope, "JSON object should have string scopes")
    }

    @Test
    fun `tokens cover entire line`() {
        val line = """{"key": "value"}"""
        val result = grammar.tokenizeLine(line)
        val tokens = result.tokens

        assertEquals(0, tokens.first().startIndex, "First token should start at 0")

        // No gaps between tokens
        for (i in 1 until tokens.size) {
            assertEquals(
                tokens[i - 1].endIndex,
                tokens[i].startIndex,
                "Token $i should start where token ${i - 1} ends"
            )
        }

        assertEquals(line.length, tokens.last().endIndex, "Last token should end at line length")

        // No token should extend beyond the original line
        assertTrue(
            tokens.all { it.endIndex <= line.length },
            "No token should have endIndex > line.length"
        )
    }

    @Test
    fun `multiline state passing`() {
        val result1 = grammar.tokenizeLine("{")
        val result2 = grammar.tokenizeLine(""""key": "value"""", result1.ruleStack)

        val hasStringScope = result2.tokens.any { token ->
            token.scopes.contains("string.quoted.double.json")
        }
        assertTrue(hasStringScope, "Line 2 should have string scopes using prevState")
    }

    @Test
    fun `multiline array tokenization`() {
        val result1 = grammar.tokenizeLine("[")
        val result2 = grammar.tokenizeLine("1,", result1.ruleStack)

        val numericToken = result2.tokens.find { token ->
            token.scopes.contains("constant.numeric.json")
        }
        assertNotNull(numericToken, "Number inside array on line 2 should have numeric scope")
    }

    @Test
    fun `INITIAL state works like null`() {
        val resultNull = grammar.tokenizeLine("true", null)
        val resultInitial = grammar.tokenizeLine("true", INITIAL)

        assertEquals(
            resultNull.tokens,
            resultInitial.tokens,
            "INITIAL should produce same tokens as null"
        )
    }

    @Test
    fun `detailed scope check for curly brace`() {
        val result = grammar.tokenizeLine("{")
        assertEquals(1, result.tokens.size, "Should have exactly one token")
        val token = result.tokens[0]
        assertEquals(0, token.startIndex)
        assertEquals(1, token.endIndex)
        assertTrue(token.scopes.contains("punctuation.definition.dictionary.begin.json"))
        assertTrue(token.scopes.contains("meta.structure.dictionary.json"))
        assertTrue(token.scopes.contains("source.json"))
    }

    @Test
    fun `string literal produces begin content and end tokens`() {
        val result = grammar.tokenizeLine("\"hello\"")
        val tokens = result.tokens
        assertEquals(3, tokens.size, "String should produce 3 tokens")

        // Opening quote
        val begin = tokens[0]
        assertEquals(0, begin.startIndex)
        assertEquals(1, begin.endIndex)
        assertTrue(begin.scopes.contains("punctuation.definition.string.begin.json"))

        // String content
        val content = tokens[1]
        assertEquals(1, content.startIndex)
        assertEquals(6, content.endIndex)
        assertTrue(content.scopes.contains("string.quoted.double.json"))

        // Closing quote
        val end = tokens[2]
        assertEquals(6, end.startIndex)
        assertEquals(7, end.endIndex)
        assertTrue(end.scopes.contains("punctuation.definition.string.end.json"))
    }

    @Test
    fun `three-line tokenization with state passing`() {
        val r1 = grammar.tokenizeLine("{")
        val r2 = grammar.tokenizeLine("  \"key\": true", r1.ruleStack)
        val r3 = grammar.tokenizeLine("}", r2.ruleStack)

        // Line 2 should have property-name and boolean scopes
        assertTrue(r2.tokens.any { it.scopes.contains("support.type.property-name.json") })
        assertTrue(r2.tokens.any { it.scopes.contains("constant.language.json") })

        // Line 3 tokens should cover the full line
        assertEquals(0, r3.tokens.first().startIndex)
        assertEquals(1, r3.tokens.last().endIndex)
    }

    // --- Kotlin grammar tests (capture retokenization) ---

    @Test
    fun `Kotlin fun keyword and function name scoped correctly`() {
        val ktGrammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val result = ktGrammar.tokenizeLine("fun main(args: Array<String>)")
        assertTrue(
            result.tokens.any { it.scopes.contains("keyword.hard.fun.kotlin") },
            "Should have keyword.hard.fun scope"
        )
        assertTrue(
            result.tokens.any { it.scopes.contains("entity.name.function.declaration.kotlin") },
            "Should have function name scope"
        )
    }

    @Test
    fun `Kotlin generic function retokenizes type parameters`() {
        val ktGrammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val result = ktGrammar.tokenizeLine("fun <T> test()")
        assertTrue(
            result.tokens.any { it.scopes.contains("entity.name.type.kotlin") },
            "Should have type parameter scope from retokenization"
        )
        assertTrue(
            result.tokens.any { it.scopes.contains("entity.name.function.declaration.kotlin") },
            "Should have function name scope"
        )
    }

    @Test
    fun `Kotlin function tokens cover entire line`() {
        val ktGrammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val line = "fun main(args: Array<String>)"
        val result = ktGrammar.tokenizeLine(line)
        val tokens = result.tokens

        assertEquals(0, tokens.first().startIndex, "First token should start at 0")
        for (i in 1 until tokens.size) {
            assertEquals(
                tokens[i - 1].endIndex,
                tokens[i].startIndex,
                "Token $i should start where token ${i - 1} ends"
            )
        }
        // Last token may extend 1 past line.length due to appended \n (matches vscode-textmate behavior)
        assertTrue(
            tokens.last().endIndex >= line.length,
            "Last token should end at or past line length"
        )
    }

    // --- Markdown grammar tests ---

    @Test
    fun `Markdown fenced code block multiline state`() {
        val mdGrammar = loadGrammar("grammars/markdown.tmLanguage.json")

        // ``` triggers fenced_code_block_unknown (a BeginEndRule)
        val r1 = mdGrammar.tokenizeLine("```")
        assertTrue(
            r1.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "Fenced code opener should have fenced_code scope"
        )
        assertTrue(
            r1.tokens.any { it.scopes.contains("punctuation.definition.markdown") },
            "Fenced code opener should have punctuation scope"
        )

        // Content inside the fenced code block should carry state
        val r2 = mdGrammar.tokenizeLine("code here", r1.ruleStack)
        assertTrue(
            r2.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "Content inside fenced code should still have fenced_code scope"
        )
    }

    // --- Multiline / BeginWhileRule condition checking tests ---

    @Test
    fun `multiline block comment in JSON`() {
        // JSON block comments use BeginEndRule, verify state carries across lines
        val r1 = grammar.tokenizeLine("/* start")
        assertTrue(
            r1.tokens.any { it.scopes.contains("comment.block.json") },
            "Opening line should have block comment scope"
        )

        val r2 = grammar.tokenizeLine("middle", r1.ruleStack)
        assertTrue(
            r2.tokens.any { it.scopes.contains("comment.block.json") },
            "Middle line should still be inside block comment"
        )

        val r3 = grammar.tokenizeLine("end */", r2.ruleStack)
        assertTrue(
            r3.tokens.any { it.scopes.contains("comment.block.json") },
            "Closing line should have block comment scope"
        )
    }

    @Test
    fun `Markdown fenced code block closes properly`() {
        val mdGrammar = loadGrammar("grammars/markdown.tmLanguage.json")

        val r1 = mdGrammar.tokenizeLine("```")
        assertTrue(
            r1.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") }
        )

        val r2 = mdGrammar.tokenizeLine("code here", r1.ruleStack)
        assertTrue(
            r2.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "Content should have fenced_code scope"
        )

        // Closing ``` pops the BeginEndRule
        val r3 = mdGrammar.tokenizeLine("```", r2.ruleStack)

        // Empty line after close should NOT have fenced_code scope
        // (avoids Joni lookbehind crash from Markdown inline patterns)
        val r4 = mdGrammar.tokenizeLine("", r3.ruleStack)
        assertFalse(
            r4.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "Line after closing fence should NOT have fenced_code scope"
        )
    }

    @Test
    fun `Kotlin multiline string carries state across lines`() {
        val ktGrammar = loadGrammar("grammars/kotlin.tmLanguage.json")

        val r1 = ktGrammar.tokenizeLine("val s = \"\"\"")
        assertTrue(
            r1.tokens.any { it.scopes.any { s -> s.contains("string") } },
            "Triple-quote should start a string scope"
        )

        val r2 = ktGrammar.tokenizeLine("  content", r1.ruleStack)
        assertTrue(
            r2.tokens.any { it.scopes.any { s -> s.contains("string") } },
            "Content inside multiline string should have string scope"
        )
    }

    @Test
    fun `clean state reset after fenced code block closes`() {
        val mdGrammar = loadGrammar("grammars/markdown.tmLanguage.json")

        val r1 = mdGrammar.tokenizeLine("```")
        val r2 = mdGrammar.tokenizeLine("code", r1.ruleStack)
        val r3 = mdGrammar.tokenizeLine("```", r2.ruleStack)

        // Separator after closed block should have separator scope, not fenced_code
        val r4 = mdGrammar.tokenizeLine("---", r3.ruleStack)
        assertTrue(
            r4.tokens.any { it.scopes.contains("meta.separator.markdown") },
            "Separator after closed block should have separator scope"
        )
        assertFalse(
            r4.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "Separator after closed block should NOT have fenced_code scope"
        )
    }

    // --- BeginWhileRule condition checking (raw_block) ---

    @Test
    fun `Markdown raw block BeginWhileRule stays open while indented`() {
        val mdGrammar = loadGrammar("grammars/markdown.tmLanguage.json")

        // 4-space indentation triggers raw_block (a BeginWhileRule)
        val r1 = mdGrammar.tokenizeLine("    code")
        assertTrue(
            r1.tokens.any { it.scopes.contains("markup.raw.block.markdown") },
            "Indented line should have raw block scope"
        )

        // While condition matches: still indented
        val r2 = mdGrammar.tokenizeLine("    more code", r1.ruleStack)
        assertTrue(
            r2.tokens.any { it.scopes.contains("markup.raw.block.markdown") },
            "Continued indented line should still have raw block scope"
        )
    }

    @Test
    fun `Markdown raw block BeginWhileRule exits on unindented line`() {
        val mdGrammar = loadGrammar("grammars/markdown.tmLanguage.json")

        // Open raw_block
        val r1 = mdGrammar.tokenizeLine("    code")
        assertTrue(
            r1.tokens.any { it.scopes.contains("markup.raw.block.markdown") }
        )

        val r2 = mdGrammar.tokenizeLine("    more", r1.ruleStack)
        assertTrue(
            r2.tokens.any { it.scopes.contains("markup.raw.block.markdown") }
        )

        // Empty line: while condition fails, raw_block pops
        val r3 = mdGrammar.tokenizeLine("", r2.ruleStack)
        assertFalse(
            r3.tokens.any { it.scopes.contains("markup.raw.block.markdown") },
            "Unindented line should NOT have raw block scope"
        )
    }
}
