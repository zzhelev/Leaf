package dev.textmate.grammar

import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.tokenize.StateStack
import dev.textmate.regex.JoniOnigLib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end integration tests verifying the tokenizer produces correct output
 * on realistic multi-token, multi-line code samples across all 3 test grammars.
 */
class GrammarIntegrationTest {

    private fun loadGrammar(resourcePath: String): Grammar {
        val rawGrammar = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.use { stream -> GrammarReader.readGrammar(stream) }
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        return Grammar(rawGrammar.scopeName, rawGrammar, JoniOnigLib())
    }

    private fun printTokens(line: String, tokens: List<Token>) {
        println("Line: \"$line\"")
        for (token in tokens) {
            val end = token.endIndex.coerceAtMost(line.length)
            val text = line.substring(token.startIndex.coerceAtMost(line.length), end)
            println("  [${token.startIndex}..${token.endIndex}) \"$text\" → ${token.scopes.joinToString(", ")}")
        }
    }

    private fun assertTokensCoverLine(line: String, tokens: List<Token>) {
        assertTrue(tokens.isNotEmpty(), "Should have at least one token")
        assertEquals(0, tokens.first().startIndex, "First token should start at 0")
        for (i in 1 until tokens.size) {
            assertEquals(
                tokens[i - 1].endIndex,
                tokens[i].startIndex,
                "Token $i should start where token ${i - 1} ends"
            )
        }
        assertTrue(
            tokens.last().endIndex >= line.length,
            "Last token should end at or past line length (got ${tokens.last().endIndex}, line length ${line.length})"
        )
    }

    private fun tokenizeLines(grammar: Grammar, vararg lines: String): List<Pair<String, TokenizeLineResult>> {
        val results = mutableListOf<Pair<String, TokenizeLineResult>>()
        var state: StateStack? = null
        for (line in lines) {
            val result = grammar.tokenizeLine(line, state)
            results.add(line to result)
            state = result.ruleStack
        }
        return results
    }

    // --- JSON integration tests ---

    @Test
    fun `JSON single-line object with mixed value types`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        val line = """{"name": "test", "count": 42, "active": true}"""
        val result = grammar.tokenizeLine(line)
        val tokens = result.tokens

        printTokens(line, tokens)
        assertTokensCoverLine(line, tokens)

        assertTrue(
            tokens.any { it.scopes.contains("support.type.property-name.json") },
            "Should have property-name scope"
        )
        assertTrue(
            tokens.any { it.scopes.contains("string.quoted.double.json") },
            "Should have string scope"
        )
        assertTrue(
            tokens.any { it.scopes.contains("constant.numeric.json") },
            "Should have numeric scope"
        )
        assertTrue(
            tokens.any { it.scopes.contains("constant.language.json") },
            "Should have boolean scope"
        )
        assertTrue(
            tokens.any { it.scopes.contains("punctuation.definition.dictionary.begin.json") },
            "Should have dictionary begin"
        )
        assertTrue(
            tokens.any { it.scopes.contains("punctuation.definition.dictionary.end.json") },
            "Should have dictionary end"
        )
        assertTrue(
            tokens.any { it.scopes.contains("punctuation.separator.dictionary.key-value.json") },
            "Should have key-value separator"
        )
        assertTrue(
            tokens.any { it.scopes.contains("punctuation.separator.dictionary.pair.json") },
            "Should have pair separator"
        )
    }

    @Test
    fun `JSON multiline nested object`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        val results = tokenizeLines(
            grammar,
            "{",
            """  "person": {""",
            """    "age": 30""",
            "  }",
            "}"
        )

        for ((line, result) in results) {
            printTokens(line, result.tokens)
            assertTokensCoverLine(line, result.tokens)
        }

        // Line 3: nested value has numeric scope and property-name scope
        val line3Tokens = results[2].second.tokens
        assertTrue(
            line3Tokens.any { it.scopes.contains("constant.numeric.json") },
            "Nested value should have numeric scope"
        )
        assertTrue(
            line3Tokens.any { it.scopes.contains("support.type.property-name.json") },
            "Nested key should have property-name scope"
        )

        // Line 5: final } has dictionary end punctuation
        val line5Tokens = results[4].second.tokens
        assertTrue(
            line5Tokens.any { it.scopes.contains("punctuation.definition.dictionary.end.json") },
            "Final brace should have dictionary end scope"
        )
    }

    // --- Kotlin integration tests ---

    @Test
    fun `Kotlin multiline function with string interpolation`() {
        val grammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val results = tokenizeLines(
            grammar,
            "fun main() {",
            "    val message = \"Hello, \${name}!\"",
            "    println(message)",
            "}"
        )

        for ((line, result) in results) {
            printTokens(line, result.tokens)
            assertTokensCoverLine(line, result.tokens)
        }

        // L1: fun keyword and function name
        val l1 = results[0].second.tokens
        assertTrue(
            l1.any { it.scopes.contains("keyword.hard.fun.kotlin") },
            "L1 should have fun keyword"
        )
        assertTrue(
            l1.any { it.scopes.contains("entity.name.function.declaration.kotlin") },
            "L1 should have function name"
        )

        // L2: val keyword, string, template expression
        val l2 = results[1].second.tokens
        assertTrue(
            l2.any { it.scopes.contains("keyword.hard.kotlin") },
            "L2 should have val keyword"
        )
        assertTrue(
            l2.any { it.scopes.contains("string.quoted.double.kotlin") },
            "L2 should have string scope"
        )
        assertTrue(
            l2.any { it.scopes.contains("meta.template.expression.kotlin") },
            "L2 should have template expression"
        )

        // L3: function call
        val l3 = results[2].second.tokens
        assertTrue(
            l3.any { it.scopes.contains("entity.name.function.call.kotlin") },
            "L3 should have function call scope"
        )

        // L4: all tokens have source.kotlin
        val l4 = results[3].second.tokens
        assertTrue(
            l4.all { it.scopes.contains("source.kotlin") },
            "L4 all tokens should have source.kotlin"
        )
    }

    @Test
    fun `Kotlin simple dollar interpolation`() {
        val grammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val line = "val greeting = \"Hello, \$name!\""
        val result = grammar.tokenizeLine(line)

        printTokens(line, result.tokens)
        assertTokensCoverLine(line, result.tokens)

        assertTrue(
            result.tokens.any { it.scopes.contains("variable.string-escape.kotlin") },
            "Should have variable string-escape scope for \$name"
        )
    }

    // --- Markdown integration tests ---

    @Test
    fun `Markdown safe patterns across multiple block types`() {
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")
        val results = tokenizeLines(
            grammar,
            "---",
            "```",
            "val x = 1",
            "```",
            "",
            "    indented code",
            "    more code"
        )

        for ((line, result) in results) {
            printTokens(line, result.tokens)
            assertTokensCoverLine(line, result.tokens)
        }

        // L1: separator
        assertTrue(
            results[0].second.tokens.any { it.scopes.contains("meta.separator.markdown") },
            "L1 should have separator scope"
        )

        // L2: fenced code begin
        val l2 = results[1].second.tokens
        assertTrue(
            l2.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L2 should have fenced_code scope"
        )
        assertTrue(
            l2.any { it.scopes.contains("punctuation.definition.markdown") },
            "L2 should have punctuation scope"
        )

        // L3: inside fenced code
        assertTrue(
            results[2].second.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L3 should still have fenced_code scope"
        )

        // L4: closing fence
        assertTrue(
            results[3].second.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L4 should have fenced_code scope"
        )

        // L5: empty line after close — NOT fenced_code
        assertFalse(
            results[4].second.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L5 should NOT have fenced_code scope"
        )

        // L6-L7: raw block
        assertTrue(
            results[5].second.tokens.any { it.scopes.contains("markup.raw.block.markdown") },
            "L6 should have raw block scope"
        )
        assertTrue(
            results[6].second.tokens.any { it.scopes.contains("markup.raw.block.markdown") },
            "L7 should have raw block scope"
        )
    }

    @Test
    fun `Markdown state isolation between blocks`() {
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")
        val results = tokenizeLines(
            grammar,
            "```",
            "code inside",
            "```",
            "---",
            "    raw block",
            "    more raw"
        )

        for ((line, result) in results) {
            printTokens(line, result.tokens)
            assertTokensCoverLine(line, result.tokens)
        }

        // Fenced block lines
        assertTrue(
            results[0].second.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L1 should have fenced_code scope"
        )
        assertTrue(
            results[1].second.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L2 should have fenced_code scope"
        )
        assertTrue(
            results[2].second.tokens.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L3 should have fenced_code scope"
        )

        // Separator: no fenced scope leak
        val l4 = results[3].second.tokens
        assertTrue(
            l4.any { it.scopes.contains("meta.separator.markdown") },
            "L4 should have separator scope"
        )
        assertFalse(
            l4.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L4 should NOT have fenced_code scope"
        )

        // Raw block: no fenced scope leak
        val l5 = results[4].second.tokens
        assertTrue(
            l5.any { it.scopes.contains("markup.raw.block.markdown") },
            "L5 should have raw block scope"
        )
        assertFalse(
            l5.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L5 should NOT have fenced_code scope"
        )

        val l6 = results[5].second.tokens
        assertTrue(
            l6.any { it.scopes.contains("markup.raw.block.markdown") },
            "L6 should have raw block scope"
        )
        assertFalse(
            l6.any { it.scopes.contains("markup.fenced_code.block.markdown") },
            "L6 should NOT have fenced_code scope"
        )
    }

    @Test
    fun `Markdown inline patterns with graceful degradation`() {
        // Joni doesn't support backreferences inside lookbehinds (strikethrough pattern).
        // Graceful degradation in JoniOnigLib replaces unsupported patterns with a
        // never-matching sentinel, so tokenization succeeds without crash.
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")

        val r1 = grammar.tokenizeLine("# Title")
        val r2 = grammar.tokenizeLine("Some **bold** and `inline code`", r1.ruleStack)

        printTokens("# Title", r1.tokens)
        printTokens("Some **bold** and `inline code`", r2.tokens)

        // Heading should get markup.heading scope
        assertTrue(
            r1.tokens.any { it.scopes.any { s -> s.startsWith("markup.heading") } },
            "Heading should have markup.heading scope"
        )

        // Bold should get markup.bold scope
        assertTrue(
            r2.tokens.any { it.scopes.any { s -> s.startsWith("markup.bold") } },
            "Bold should have markup.bold scope"
        )

        // Inline code should get markup.inline.raw scope
        assertTrue(
            r2.tokens.any { it.scopes.any { s -> s.startsWith("markup.inline.raw") } },
            "Inline code should have markup.inline.raw scope"
        )
    }

    @Test
    fun `Markdown headings and inline formatting`() {
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")
        val results = tokenizeLines(
            grammar,
            "# Hello World",
            "",
            "Some **bold** and *italic* text.",
            "",
            "> Blockquote text",
            "",
            "- List item"
        )

        for ((line, result) in results) {
            printTokens(line, result.tokens)
            assertTokensCoverLine(line, result.tokens)
        }

        // Heading
        assertTrue(
            results[0].second.tokens.any { it.scopes.any { s -> s.startsWith("markup.heading") } },
            "Should have heading scope"
        )

        // Bold
        assertTrue(
            results[2].second.tokens.any { it.scopes.any { s -> s.startsWith("markup.bold") } },
            "Should have bold scope"
        )

        // Italic
        assertTrue(
            results[2].second.tokens.any { it.scopes.any { s -> s.startsWith("markup.italic") } },
            "Should have italic scope"
        )

        // Blockquote
        assertTrue(
            results[4].second.tokens.any { it.scopes.any { s -> s.startsWith("markup.quote") } },
            "Should have quote scope"
        )

        // List item
        assertTrue(
            results[6].second.tokens.any { it.scopes.any { s -> s.startsWith("markup.list") } },
            "Should have list scope"
        )
    }
}
