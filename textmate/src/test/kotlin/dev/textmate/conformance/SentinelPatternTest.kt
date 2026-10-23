package dev.textmate.conformance

import dev.textmate.regex.JoniOnigLib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Asserts the exact number of regex patterns that fell back to the
 * never-matching sentinel due to Joni compilation failures.
 * Catches silent degradation from Joni or grammar updates.
 */
class SentinelPatternTest {

    @Test
    fun `JSON grammar has 0 sentinel patterns`() {
        val onigLib = JoniOnigLib()
        loadAndCompileGrammar("grammars/JSON.tmLanguage.json", onigLib)
        assertEquals(0, onigLib.sentinelPatternCount, "JSON should have no sentinel patterns")
    }

    @Test
    fun `Kotlin grammar has 0 sentinel patterns`() {
        val onigLib = JoniOnigLib()
        loadAndCompileGrammar("grammars/kotlin.tmLanguage.json", onigLib)
        assertEquals(0, onigLib.sentinelPatternCount, "Kotlin should have no sentinel patterns")
    }

    @Test
    fun `JavaScript grammar has 0 sentinel patterns`() {
        val onigLib = JoniOnigLib()
        loadAndCompileGrammar("grammars/JavaScript.tmLanguage.json", onigLib)
        assertEquals(0, onigLib.sentinelPatternCount, "JavaScript should have no sentinel patterns")
    }

    @Test
    fun `Markdown grammar has exactly 1 sentinel pattern`() {
        val onigLib = JoniOnigLib()
        loadAndCompileGrammar("grammars/markdown.tmLanguage.json", onigLib)
        assertEquals(
            1,
            onigLib.sentinelPatternCount,
            "Markdown should have exactly 1 sentinel (strikethrough)"
        )
    }

    private fun loadAndCompileGrammar(resourcePath: String, onigLib: JoniOnigLib) {
        val rawGrammar = ConformanceTestSupport.loadRawGrammar(resourcePath)
        val grammar = dev.textmate.grammar.Grammar(
            rawGrammar.scopeName,
            rawGrammar,
            onigLib
        )
        // Tokenize representative text to trigger lazy pattern compilation.
        // Only rules actually entered get their sub-patterns compiled, so deeply
        // nested rules not exercised here won't be counted.
        val lines = listOf(
            "# heading",
            "some **bold** and ~~strike~~ text",
            "{ \"key\": true }",
            "fun main() { val x = 1 }"
        )
        var state: dev.textmate.grammar.tokenize.StateStack? = null
        for (line in lines) {
            state = grammar.tokenizeLine(line, state).ruleStack
        }
    }
}
