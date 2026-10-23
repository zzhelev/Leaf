package dev.textmate.grammar.raw

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrammarReaderTest {

    private fun loadGrammar(resourcePath: String): RawGrammar {
        return javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.use { stream -> GrammarReader.readGrammar(stream) }
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
    }

    private fun loadGrammarAsString(resourcePath: String): RawGrammar {
        val json = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        return GrammarReader.readGrammar(json)
    }

    // ── JSON grammar ────────────────────────────────────────────────

    @Test
    fun `JSON grammar has correct scopeName`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        assertEquals("source.json", grammar.scopeName)
    }

    @Test
    fun `JSON grammar has correct name`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        assertEquals("JSON (Javascript Next)", grammar.name)
    }

    @Test
    fun `JSON grammar has patterns`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        assertNotNull(grammar.patterns)
        assertTrue(grammar.patterns!!.isNotEmpty())
        assertEquals("#value", grammar.patterns[0].include)
    }

    @Test
    fun `JSON grammar has expected repository keys`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        val keys = grammar.repository!!.keys
        val expected = setOf(
            "array", "comments", "constant", "number",
            "object", "string", "objectkey", "stringcontent", "value"
        )
        assertEquals(expected, keys)
    }

    // ── Kotlin grammar ──────────────────────────────────────────────

    @Test
    fun `Kotlin grammar has correct scopeName`() {
        val grammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        assertEquals("source.kotlin", grammar.scopeName)
    }

    @Test
    fun `Kotlin grammar has fileTypes`() {
        val grammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        assertEquals(listOf("kt", "kts"), grammar.fileTypes)
    }

    @Test
    fun `Kotlin grammar has repository with expected keys`() {
        val grammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val keys = grammar.repository!!.keys
        assertTrue("import" in keys)
        assertTrue("package" in keys)
        assertTrue("code" in keys)
        assertTrue("comments" in keys)
        assertTrue("keywords" in keys)
    }

    // ── Markdown grammar ────────────────────────────────────────────

    @Test
    fun `markdown grammar has correct scopeName`() {
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")
        assertEquals("text.html.markdown", grammar.scopeName)
    }

    @Test
    fun `markdown blockquote rule has while field deserialized`() {
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")
        val blockquote = grammar.repository!!["blockquote"]
        assertNotNull(blockquote, "blockquote rule should exist")
        assertNotNull(blockquote!!.whilePattern, "whilePattern should be deserialized")
        assertTrue(
            blockquote.whilePattern!!.contains("(>) ?")
        )
    }

    @Test
    fun `markdown frontMatter has applyEndPatternLast as integer`() {
        val grammar = loadGrammar("grammars/markdown.tmLanguage.json")
        val frontMatter = grammar.repository!!["frontMatter"]
        assertNotNull(frontMatter, "frontMatter rule should exist")
        assertEquals(1, frontMatter!!.applyEndPatternLast)
    }

    // ── Structural tests ────────────────────────────────────────────

    @Test
    fun `captures are deserialized with string keys`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        val array = grammar.repository!!.getValue("array")
        val cap0 = array.beginCaptures!!["0"]
        assertNotNull(cap0)
        assertEquals("punctuation.definition.array.begin.json", cap0!!.name)
    }

    @Test
    fun `nested patterns and repository structures are preserved`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        val obj = grammar.repository!!.getValue("object")
        assertNotNull(obj.begin)
        assertNotNull(obj.end)
        assertNotNull(obj.patterns)
        assertTrue(obj.patterns!!.size >= 2)
    }

    @Test
    fun `include references are preserved`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        assertEquals("#value", grammar.patterns!![0].include)
    }

    @Test
    fun `begin end captures are deserialized correctly`() {
        val grammar = loadGrammar("grammars/JSON.tmLanguage.json")
        val string = grammar.repository!!.getValue("string")
        assertEquals(
            "punctuation.definition.string.begin.json",
            string.beginCaptures!!.getValue("0").name
        )
        assertEquals(
            "punctuation.definition.string.end.json",
            string.endCaptures!!.getValue("0").name
        )
    }

    @Test
    fun `contentName is deserialized`() {
        val grammar = loadGrammar("grammars/kotlin.tmLanguage.json")
        val import = grammar.repository!!.getValue("import")
        assertEquals("entity.name.package.kotlin", import.contentName)
    }

    @Test
    fun `readGrammar from string produces same result for JSON`() {
        val fromStream = loadGrammar("grammars/JSON.tmLanguage.json")
        val fromString = loadGrammarAsString("grammars/JSON.tmLanguage.json")
        assertEquals(fromStream.scopeName, fromString.scopeName)
        assertEquals(fromStream.patterns!!.size, fromString.patterns!!.size)
        assertEquals(fromStream.repository!!.keys, fromString.repository!!.keys)
    }

    @Test
    fun `readGrammar from string produces same result for Kotlin`() {
        val fromStream = loadGrammar("grammars/kotlin.tmLanguage.json")
        val fromString = loadGrammarAsString("grammars/kotlin.tmLanguage.json")
        assertEquals(fromStream.scopeName, fromString.scopeName)
        assertEquals(fromStream.patterns!!.size, fromString.patterns!!.size)
        assertEquals(fromStream.repository!!.keys, fromString.repository!!.keys)
    }

    @Test
    fun `readGrammar from string produces same result for Markdown`() {
        val fromStream = loadGrammar("grammars/markdown.tmLanguage.json")
        val fromString = loadGrammarAsString("grammars/markdown.tmLanguage.json")
        assertEquals(fromStream.scopeName, fromString.scopeName)
        assertEquals(fromStream.patterns!!.size, fromString.patterns!!.size)
        assertEquals(fromStream.repository!!.keys, fromString.repository!!.keys)
    }

    // ── Lenient reading, as vscode-textmate's ────────────────────────

    @Test
    fun `a capture that isn't a rule is left out`() {
        // As in shiki's XML grammar, whose captures have strings among the rules.
        val grammar = GrammarReader.readGrammar(
            """{"scopeName": "source.t", "patterns": [{"match": "(a)(b)(c)",
                "captures": {"1": {"name": "one"}, "2": "two", "3": null}}]}"""
        )
        val captures = grammar.patterns!!.single().captures!!
        assertEquals(setOf("1"), captures.keys)
        assertEquals("one", captures.getValue("1").name)
    }

    @Test
    fun `captures given as an array are indexed by capture number`() {
        val grammar = GrammarReader.readGrammar(
            """{"scopeName": "source.t", "patterns": [{"begin": "(a)(b)", "end": "c",
                "beginCaptures": [{"name": "whole"}, "one", {"name": "two"}], "endCaptures": []}]}"""
        )
        val rule = grammar.patterns!!.single()
        assertEquals(mapOf("0" to "whole", "2" to "two"), rule.beginCaptures!!.mapValues { it.value.name })
        assertEquals(emptyMap<String, RawRule>(), rule.endCaptures)
    }

    @Test
    fun `entries of the wrong type read as missing`() {
        val grammar = GrammarReader.readGrammar(
            """{"scopeName": "source.t", "name": 3, "fileTypes": ["t", 1, null], "repository": {"a": [], "b": {}},
                "patterns": ["text", 1, {"include": "#b", "contentName": null, "match": false}]}"""
        )
        assertNull(grammar.name)
        assertEquals(listOf("t"), grammar.fileTypes)
        assertEquals(setOf("b"), grammar.repository!!.keys)
        val rule = grammar.patterns!!.single()
        assertEquals("#b", rule.include)
        assertNull(rule.contentName)
        assertNull(rule.match)
    }

    @Test
    fun `applyEndPatternLast reads from a boolean or a number`() {
        fun applyEndPatternLast(value: String): Int? = GrammarReader.readGrammar(
            """{"scopeName": "source.t", "patterns": [{"begin": "a", "end": "b", "applyEndPatternLast": $value}]}"""
        ).patterns!!.single().applyEndPatternLast

        assertEquals(1, applyEndPatternLast("true"))
        assertEquals(0, applyEndPatternLast("false"))
        assertEquals(1, applyEndPatternLast("1"))
        assertEquals(0, applyEndPatternLast("0"))
        assertNull(applyEndPatternLast("\"1\""))
        assertNull(applyEndPatternLast("null"))
    }

    @Test
    fun `the while key reads into whilePattern`() {
        val grammar = GrammarReader.readGrammar(
            """{"scopeName": "source.t", "patterns": [{"begin": "^>", "while": "^>", "whileCaptures": {"0": {}}}]}"""
        )
        assertEquals("^>", grammar.patterns!!.single().whilePattern)
    }

    @Test
    fun `a grammar that isn't an object, has no scopeName or isn't JSON is refused`() {
        assertThrows(IllegalArgumentException::class.java) { GrammarReader.readGrammar("[]") }
        assertThrows(IllegalArgumentException::class.java) { GrammarReader.readGrammar("""{"name": "x"}""") }
        assertThrows(IllegalArgumentException::class.java) { GrammarReader.readGrammar("""{"scopeName": 1}""") }
        assertThrows(IllegalArgumentException::class.java) { GrammarReader.readGrammar("""{"scopeName": "s",""") }
    }
}
