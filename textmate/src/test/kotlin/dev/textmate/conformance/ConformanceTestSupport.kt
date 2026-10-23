package dev.textmate.conformance

import dev.textmate.grammar.Grammar
import dev.textmate.grammar.Token
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.raw.RawGrammar
import dev.textmate.grammar.tokenize.StateStack
import dev.textmate.regex.JoniOnigLib
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

// --- Data models ---

@Serializable
data class ExpectedToken(val value: String, val scopes: List<String>)

@Serializable
data class ExpectedLine(val line: String, val tokens: List<ExpectedToken>)

@Serializable
data class FirstMateTestCase(
    val desc: String,
    val grammarPath: String? = null,
    val grammarScopeName: String? = null,
    val grammars: List<String>,
    val grammarInjections: List<String>? = null,
    val lines: List<ExpectedLine>
)

@Serializable
data class GoldenSnapshot(
    val grammar: String,
    val generatedWith: String? = null,
    val files: List<GoldenFile>
)

@Serializable
data class GoldenFile(
    val source: String,
    val lines: List<ExpectedLine>
)

// --- Support object ---

object ConformanceTestSupport {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Loading ---

    fun loadFirstMateTests(resourcePath: String): List<FirstMateTestCase> {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        return stream.use { json.decodeFromString<List<FirstMateTestCase>>(it.readBytes().decodeToString()) }
    }

    fun loadGoldenSnapshot(resourcePath: String): GoldenSnapshot {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        return stream.use { json.decodeFromString<GoldenSnapshot>(it.readBytes().decodeToString()) }
    }

    fun loadRawGrammar(resourcePath: String): RawGrammar {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        return stream.use { GrammarReader.readGrammar(it) }
    }

    fun createGrammar(rawGrammar: RawGrammar): Grammar {
        return Grammar(rawGrammar.scopeName, rawGrammar, JoniOnigLib())
    }

    // --- Token conversion ---

    fun actualToExpected(line: String, tokens: List<Token>): List<ExpectedToken> {
        return tokens.map { token ->
            val start = token.startIndex.coerceAtMost(line.length)
            val end = token.endIndex.coerceAtMost(line.length)
            ExpectedToken(line.substring(start, end), token.scopes)
        }
    }

    // --- Assertions ---

    fun assertTokensMatch(
        lineText: String,
        lineIndex: Int,
        expected: List<ExpectedToken>,
        actual: List<ExpectedToken>,
        testDesc: String
    ) {
        val filteredExpected = if (lineText.isNotEmpty()) {
            expected.filter { it.value.isNotEmpty() }
        } else {
            expected
        }

        if (filteredExpected == actual) return

        val sb = StringBuilder()
        sb.appendLine("Token mismatch in '$testDesc', line $lineIndex: \"$lineText\"")
        sb.appendLine("Expected ${filteredExpected.size} tokens, got ${actual.size}")
        sb.appendLine()

        val maxTokens = maxOf(filteredExpected.size, actual.size)
        for (i in 0 until maxTokens) {
            val exp = filteredExpected.getOrNull(i)
            val act = actual.getOrNull(i)

            if (exp == act) {
                sb.appendLine("  token[$i] OK: \"${exp?.value}\" ${exp?.scopes}")
                continue
            }

            sb.appendLine("  token[$i] MISMATCH:")
            if (exp != null) {
                sb.appendLine("    expected: \"${exp.value}\" ${exp.scopes}")
            } else {
                sb.appendLine("    expected: (no more tokens)")
            }
            if (act != null) {
                sb.appendLine("    actual:   \"${act.value}\" ${act.scopes}")
            } else {
                sb.appendLine("    actual:   (no more tokens)")
            }

            if (exp != null && act != null && exp.value == act.value) {
                val missing = exp.scopes - act.scopes.toSet()
                val extra = act.scopes - exp.scopes.toSet()
                if (missing.isNotEmpty()) sb.appendLine("    missing scopes: $missing")
                if (extra.isNotEmpty()) sb.appendLine("    extra scopes:   $extra")
            }
        }

        fail<Unit>(sb.toString())
    }

    fun assertTilingInvariant(
        lineText: String,
        lineIndex: Int,
        tokens: List<Token>,
        testDesc: String
    ) {
        if (tokens.isEmpty()) return
        assertEquals(
            0,
            tokens.first().startIndex,
            "Tiling: first token should start at 0 in '$testDesc', line $lineIndex"
        )
        for (i in 1 until tokens.size) {
            assertEquals(
                tokens[i - 1].endIndex,
                tokens[i].startIndex,
                "Tiling: gap/overlap between token[${i - 1}] and token[$i] in '$testDesc', line $lineIndex"
            )
        }
        assertTrue(
            tokens.last().endIndex >= lineText.length,
            "Tiling: last token should end at or past line length in '$testDesc', line $lineIndex"
        )
    }

    // --- Tokenization loop ---

    fun assertGrammarTokenization(
        grammar: Grammar,
        expectedLines: List<ExpectedLine>,
        testDesc: String,
        initialState: StateStack? = null
    ) {
        var state = initialState
        for ((lineIndex, expectedLine) in expectedLines.withIndex()) {
            val result = grammar.tokenizeLine(expectedLine.line, state)
            assertTilingInvariant(expectedLine.line, lineIndex, result.tokens, testDesc)
            val actual = actualToExpected(expectedLine.line, result.tokens)
            assertTokensMatch(
                lineText = expectedLine.line,
                lineIndex = lineIndex,
                expected = expectedLine.tokens,
                actual = actual,
                testDesc = testDesc
            )
            state = result.ruleStack
        }
    }
}
