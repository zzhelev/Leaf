// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

import java.util.concurrent.ConcurrentHashMap

/** What a piece of code is, which decides its colour (`docs/fork/syntax-highlighting.md`, 4.2). */
enum class SyntaxCategory {
    KEYWORD,
    ANNOTATION,
    COMMENT,
    STRING,
    NUMBER,
    TYPE,
    FUNCTION,
    TAG,
    ATTRIBUTE,
}

/** A rule of section 4.2's table: the scopes it matches, and their category, or null for plain. */
private class CategoryRule(val scope: String, val category: SyntaxCategory?)

private fun rules(category: SyntaxCategory?, vararg scopes: String) = scopes.map { CategoryRule(it, category) }

/** Section 4.2's table, in its order: the first rule that a scope matches decides. */
private val CATEGORY_RULES: List<CategoryRule> = listOf(
    rules(SyntaxCategory.COMMENT, "comment", "punctuation.definition.comment", "keyword.other.documentation"),
    // `${`, `}` and `$name` in a string.
    rules(
        SyntaxCategory.KEYWORD,
        "punctuation.definition.template-expression", "punctuation.section.embedded", "variable.string-escape",
    ),
    // Code inside a string's template, or a language inside another, is plain but for its own tokens.
    rules(null, "meta.template.expression", "meta.embedded"),
    rules(
        SyntaxCategory.STRING,
        "string", "punctuation.definition.string", "constant.character.escape", "markup.inline.raw",
        "markup.fenced_code",
    ),
    // `px` in `8px`, as VS Code's themes colour it.
    rules(SyntaxCategory.NUMBER, "constant.numeric", "constant.language", "constant.character", "keyword.other.unit"),
    rules(null, "keyword.operator", "storage.type.function.arrow"),
    // Java's package names, which its grammar scopes as modifiers, are plain in VS Code's themes.
    rules(null, "storage.modifier.import.java", "variable.language.wildcard.java", "storage.modifier.package.java"),
    rules(
        SyntaxCategory.ANNOTATION,
        "entity.name.type.annotation", "storage.type.annotation", "meta.decorator",
    ),
    // YAML's keys are tags in its grammar.
    rules(
        SyntaxCategory.ATTRIBUTE, "support.type.property-name", "entity.other.attribute-name", "entity.name.tag.yaml",
    ),
    rules(SyntaxCategory.TAG, "entity.name.tag", "punctuation.definition.tag"),
    // The types that Go's, Java's and Groovy's grammars scope as `storage.type`, and other type scopes, which VS
    // Code's Dark+ and Light+ colour as types ("Types declaration and references"). Its entries that no shipped grammar
    // has (C#'s) are left out, and annotations stay annotations.
    rules(
        SyntaxCategory.TYPE,
        "entity.name.namespace", "entity.other.attribute", "entity.name.scope-resolution", "entity.name.class",
        "storage.type.numeric.go", "storage.type.byte.go", "storage.type.boolean.go", "storage.type.string.go",
        "storage.type.uintptr.go", "storage.type.error.go", "storage.type.rune.go",
        "storage.type.generic.java", "storage.type.java", "storage.type.object.array.java",
        "storage.type.primitive.array.java", "storage.type.primitive.java",
        "storage.type.groovy", "storage.type.parameters.groovy", "storage.type.generic.groovy",
        "storage.type.object.array.groovy", "storage.type.primitive.array.groovy", "storage.type.primitive.groovy",
    ),
    rules(SyntaxCategory.KEYWORD, "markup.heading", "keyword", "storage.type", "storage.modifier"),
    rules(
        SyntaxCategory.TYPE, "entity.name.type", "entity.other.inherited-class", "support.type", "support.class",
    ),
    rules(SyntaxCategory.FUNCTION, "entity.name.function", "support.function"),
).flatten()

/** The scopes of section 4.2's table, in its order, each with its category or null for plain. For tests. */
internal val categoryRules: List<Pair<String, SyntaxCategory?>> = CATEGORY_RULES.map { it.scope to it.category }

/** Whether [scope] is [rule] or one of its children: `string` matches `string.quoted.double.kotlin`, not `strings`. */
internal fun scopeMatches(scope: String, rule: String): Boolean =
    scope.startsWith(rule) && (scope.length == rule.length || scope[rule.length] == '.')

/** How many scopes [infoOf] remembers: grammars have a few thousand, but a few build scopes from the text. */
private const val MAX_REMEMBERED_SCOPES = 20_000

private const val RULE_MASK = 0xFF
private const val BOLD = 0x100
private const val ITALIC = 0x200

private val scopeInfo = ConcurrentHashMap<String, Int>()

/**
 * What [scope] says on its own, worked out once: the index of the first rule of the table that it matches, plus one (0
 * for none), and whether it's bold or italic. Each token has its own list of scopes, but the scopes repeat, and
 * checking each against the table took four times as long as tokenizing JSON, whose scopes nest deep.
 */
private fun infoOf(scope: String): Int {
    scopeInfo[scope]?.let { return it }
    val rule = CATEGORY_RULES.indexOfFirst { scopeMatches(scope, it.scope) } + 1
    val bold = scopeMatches(scope, "markup.bold") || scopeMatches(scope, "markup.heading")
    val italic = scopeMatches(scope, "markup.italic")
    val info = rule or (if (bold) BOLD else 0) or (if (italic) ITALIC else 0)
    if (scopeInfo.size < MAX_REMEMBERED_SCOPES) scopeInfo[scope] = info
    return info
}

/**
 * The category of a token whose scopes are [scopes], outermost first, as a grammar gives them: the first rule of the
 * table that the innermost scope matches, else the first that its parent matches, and so on outwards. Null when no
 * scope matches, or when the first that does is plain.
 */
fun syntaxCategoryOf(scopes: List<String>): SyntaxCategory? {
    for (index in scopes.indices.reversed()) {
        val rule = infoOf(scopes[index]) and RULE_MASK
        if (rule != 0) return CATEGORY_RULES[rule - 1].category
    }
    return null
}

/** Whether a token with [scopes] is drawn bold: Markdown's bold text and headings. */
fun isBoldSyntax(scopes: List<String>): Boolean = scopes.any { infoOf(it) and BOLD != 0 }

/** Whether a token with [scopes] is drawn in italics: Markdown's emphasis. */
fun isItalicSyntax(scopes: List<String>): Boolean = scopes.any { infoOf(it) and ITALIC != 0 }
