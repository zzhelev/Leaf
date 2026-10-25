// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

import dev.app.leaf.domain.highlighting.SyntaxCategory.ANNOTATION
import dev.app.leaf.domain.highlighting.SyntaxCategory.ATTRIBUTE
import dev.app.leaf.domain.highlighting.SyntaxCategory.COMMENT
import dev.app.leaf.domain.highlighting.SyntaxCategory.FUNCTION
import dev.app.leaf.domain.highlighting.SyntaxCategory.KEYWORD
import dev.app.leaf.domain.highlighting.SyntaxCategory.NUMBER
import dev.app.leaf.domain.highlighting.SyntaxCategory.STRING
import dev.app.leaf.domain.highlighting.SyntaxCategory.TAG
import dev.app.leaf.domain.highlighting.SyntaxCategory.TYPE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The categories of real tokens: the scopes come from the textmate module's conformance goldens, which VS Code's
 * engine made for the samples next to them.
 */
class SyntaxCategoriesTest {

    private val conformance = File("../textmate/src/test/resources/conformance")

    /** A token of a sample, its text, and what it should be drawn as. */
    private data class Expected(
        val text: String,
        val category: SyntaxCategory?,
        val occurrence: Int = 0,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    private fun e(text: String, category: SyntaxCategory?, occurrence: Int = 0) = Expected(text, category, occurrence)

    private val expected: Map<String, List<Expected>> = mapOf(
        "kotlin" to listOf(
            e("package", KEYWORD), e("/**", COMMENT), e("@JvmInline", ANNOTATION), e("value", KEYWORD),
            e("class", KEYWORD), e("BranchName", TYPE), e("String", TYPE), e("removePrefix", FUNCTION),
            e("refs/heads/", STRING), e("\"", STRING), e("0", NUMBER), e("describe", FUNCTION), e("if", KEYWORD),
            e("=", null), e(">", null), e("->", null),
            // A template: `${` and `}` as keywords, the code inside plain but for its own tokens.
            e("\${", KEYWORD), e("branch.ahead", null), e("toString", FUNCTION), e("16", NUMBER),
            e("\$counts", KEYWORD), e("\"\"\"", STRING), e("1_000", NUMBER),
        ),
        "java" to listOf(
            e("package", KEYWORD), e("dev", null), e("java", null), e("/**", COMMENT), e("public", KEYWORD),
            // The first `Path` is in an import.
            e("Worktree", TYPE), e("Path", null), e("Path", TYPE, occurrence = 1), e("boolean", TYPE),
            e("Override", ANNOTATION), e("var", KEYWORD),
            e("->", null),
        ),
        "go" to listOf(e("string", TYPE), e("byte", TYPE)),
        "rust" to listOf(e("std", TYPE), e("::", null)),
        "csharp" to listOf(e("namespace", KEYWORD), e("class", KEYWORD)),
        "typescript" to listOf(
            e("import", KEYWORD), e("./model", STRING), e("Mode", TYPE), e("|", null), e("interface", KEYWORD),
            e("Comparison", TYPE), e("T", TYPE), e("extends", KEYWORD),
        ),
        "python" to listOf(e("\"\"\"", STRING), e("from", KEYWORD), e("__future__", null), e("HEADER", null)),
        "shellscript" to listOf(e("#!", COMMENT), e("set", FUNCTION), e("if", KEYWORD), e("\"", STRING)),
        "json" to listOf(e("name", ATTRIBUTE), e("leaf-site", STRING), e("{", null)),
        // YAML's keys are cut in two tokens; `on:` is a boolean to its grammar.
        "yaml" to listOf(e("n", ATTRIBUTE), e("ame", ATTRIBUTE), e("on", NUMBER), e("leaf-*", STRING), e("#", COMMENT)),
        "toml" to listOf(e("#", COMMENT), e("2.4.0", STRING), e("3", NUMBER), e("true", NUMBER), e("kotlin", null)),
        "xml" to listOf(
            e("xml", TAG), e("<", TAG), e(" version", ATTRIBUTE), e("1.0", STRING), e("<!--", COMMENT),
            e("resources", TAG), e("xmlns", ATTRIBUTE), e("&", NUMBER),
        ),
        "html" to listOf(
            e("DOCTYPE", TAG), e("en", STRING), e("&", NUMBER),
            // Its `<style>`'s CSS.
            e("body", TAG), e("margin", ATTRIBUTE), e("/", null),
        ),
        "css" to listOf(
            e("/*", COMMENT), e("root", ATTRIBUTE), e("--accent", null), e("8", NUMBER), e("px", NUMBER),
            e("diff-pane", ATTRIBUTE), e("added", ATTRIBUTE), e("added", STRING, occurrence = 1),
        ),
        "vue" to listOf(
            e("template", TAG), e("ul", TAG), e("class", ATTRIBUTE), e("import", KEYWORD), e("const", KEYWORD),
            e("defineProps", FUNCTION),
        ),
        "markdown" to listOf(
            Expected("#", KEYWORD, bold = true), Expected("Leaf", KEYWORD, bold = true),
            Expected("first-class", null, bold = true), Expected("agent", null, italic = true),
            e("inline code", STRING), e("```", STRING), e("Releases", STRING),
            // A fenced block's language is plain but for its own tokens.
            e("./gradlew build", null),
        ),
        "git-commit" to listOf(
            e("# Please enter the commit message for your changes. Lines starting", COMMENT),
        ),
    )

    /** The scopes of the [occurrence]th token of [language]'s sample whose text is [text]. */
    private fun scopesOf(language: String, text: String, occurrence: Int): List<String> {
        val sample = File(conformance, "samples/$language").listFiles()!!.single()
        val lines = sample.readLines()
        val tokens = File(conformance, "expected/$language/${sample.name}.tokens").readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (position, scopes) = line.split(' ', limit = 2)
                val (lineIndex, range) = position.split(':')
                val (start, end) = range.split('-').map { it.toInt() }
                lines[lineIndex.toInt()].substring(start, end) to scopes.split(' ')
            }
        val matching = tokens.filter { it.first == text }
        check(matching.size > occurrence) { "$language has ${matching.size} tokens \"$text\"" }
        return matching[occurrence].second
    }

    @Test
    fun `tokens of the samples get their categories`() {
        check(conformance.isDirectory) { "No conformance samples in ${conformance.absolutePath}" }
        val wrong = expected.flatMap { (language, tokens) ->
            tokens.mapNotNull { token ->
                val scopes = scopesOf(language, token.text, token.occurrence)
                val actual = Expected(
                    token.text, syntaxCategoryOf(scopes), token.occurrence,
                    isBoldSyntax(scopes), isItalicSyntax(scopes),
                )
                if (actual == token) null else "$language: wanted $token, got $actual from $scopes"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every scope of the table is in a shipped grammar`() {
        val grammars = File("../textmate/src/main/resources/grammars").listFiles { it -> it.extension == "json" }!!
            .joinToString("\n") { it.readText() }
        val missing = categoryRules.map { it.first }.filter { !grammars.contains(it) }
        assertTrue(missing.isEmpty(), "Not in any shipped grammar: $missing")
    }

    @Test
    fun `the innermost scope that a rule matches decides`() {
        assertEquals(STRING, syntaxCategoryOf(listOf("source.kotlin", "string.quoted.double.kotlin")))
        // The inner scope comes first, whatever is around it.
        assertEquals(KEYWORD, syntaxCategoryOf(listOf("source.java", "comment.block.java", "keyword.control.java")))
        assertEquals(
            COMMENT, syntaxCategoryOf(listOf("source.java", "comment.block.java", "keyword.other.documentation.java")),
        )
        // No rule for the innermost: its parent's.
        assertEquals(STRING, syntaxCategoryOf(listOf("source.css", "string.quoted.double.css", "meta.something.css")))
        // A plain rule stops the search: code inside a string's template isn't a string.
        assertNull(
            syntaxCategoryOf(listOf("source.kotlin", "string.quoted.double.kotlin", "meta.template.expression.kotlin")),
        )
        assertNull(syntaxCategoryOf(listOf("source.kotlin", "string.quoted.double.kotlin", "keyword.operator.plus")))
        assertNull(syntaxCategoryOf(listOf("source.kotlin")))
        assertNull(syntaxCategoryOf(emptyList()))
    }

    @Test
    fun `a rule matches a scope and its children, by whole parts`() {
        assertTrue(scopeMatches("string", "string"))
        assertTrue(scopeMatches("string.quoted.double.kotlin", "string"))
        assertFalse(scopeMatches("strings.quoted", "string"))
        assertFalse(scopeMatches("str", "string"))
        assertFalse(scopeMatches("entity.other.attribute-name.css", "entity.other.attribute"))
        assertEquals(ATTRIBUTE, syntaxCategoryOf(listOf("entity.other.attribute-name.css")))
        assertEquals(TYPE, syntaxCategoryOf(listOf("entity.other.attribute.cs")))
    }

    @Test
    fun `the table's order decides between rules that match one scope`() {
        assertEquals(ANNOTATION, syntaxCategoryOf(listOf("entity.name.type.annotation.kotlin")))
        assertEquals(TYPE, syntaxCategoryOf(listOf("entity.name.type.kotlin")))
        assertEquals(ANNOTATION, syntaxCategoryOf(listOf("storage.type.annotation.java")))
        assertEquals(TYPE, syntaxCategoryOf(listOf("storage.type.java")))
        assertEquals(KEYWORD, syntaxCategoryOf(listOf("storage.type.class.java")))
        assertEquals(ATTRIBUTE, syntaxCategoryOf(listOf("entity.name.tag.yaml")))
        assertEquals(TAG, syntaxCategoryOf(listOf("entity.name.tag.html")))
        assertEquals(STRING, syntaxCategoryOf(listOf("constant.character.escape.kotlin")))
        assertEquals(NUMBER, syntaxCategoryOf(listOf("constant.character.entity.html")))
        assertEquals(KEYWORD, syntaxCategoryOf(listOf("punctuation.definition.template-expression.begin.kotlin")))
        assertEquals(STRING, syntaxCategoryOf(listOf("punctuation.definition.string.begin.kotlin")))
    }

    @Test
    fun `bold and italic come from any scope`() {
        assertTrue(
            isBoldSyntax(listOf("text.html.markdown", "markup.heading.markdown", "entity.name.section.markdown")),
        )
        assertTrue(isBoldSyntax(listOf("text.html.markdown", "markup.bold.markdown")))
        assertFalse(isBoldSyntax(listOf("text.html.markdown", "markup.italic.markdown")))
        assertTrue(isItalicSyntax(listOf("text.html.markdown", "markup.italic.markdown", "markup.bold.markdown")))
        assertFalse(isItalicSyntax(listOf("text.html.markdown", "markup.inline.raw.markdown")))
    }
}
