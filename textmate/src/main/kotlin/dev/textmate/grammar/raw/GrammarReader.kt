package dev.textmate.grammar.raw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Reads TextMate grammars from `.tmLanguage.json` files.
 *
 * Reads them as leniently as vscode-textmate does, which takes the JSON as it is and uses what fits: an entry of the
 * wrong type (a string where a capture should be a rule, a number where a name should be text) is left out, where a
 * typed reader would refuse the whole grammar. Captures may also be an array, indexed by capture number, as
 * JavaScript's `for…in` reads one. `applyEndPatternLast` is read from a boolean or a number.
 *
 * Rule IDs are not assigned during parsing — they are assigned later
 * during rule compilation by [dev.textmate.grammar.rule.RuleFactory].
 */
public object GrammarReader {

    /**
     * Parses a TextMate grammar from a JSON string.
     *
     * @throws IllegalArgumentException if the JSON is malformed, isn't an object or has no `scopeName`
     */
    public fun readGrammar(json: String): RawGrammar =
        grammarOf(Json.parseToJsonElement(json))

    /**
     * Parses a TextMate grammar from an [InputStream].
     *
     * The caller is responsible for closing the [inputStream] after this method returns.
     *
     * @throws IllegalArgumentException if the JSON is malformed, isn't an object or has no `scopeName`
     * @throws java.io.IOException if reading from the stream fails
     */
    public fun readGrammar(inputStream: InputStream): RawGrammar =
        readGrammar(InputStreamReader(inputStream, Charsets.UTF_8))

    /**
     * Parses a TextMate grammar from a [Reader].
     *
     * The caller is responsible for closing the [reader] after this method returns.
     *
     * @throws IllegalArgumentException if the JSON is malformed, isn't an object or has no `scopeName`
     * @throws java.io.IOException if reading from the reader fails
     */
    public fun readGrammar(reader: Reader): RawGrammar =
        readGrammar(reader.readText())

    private fun grammarOf(element: JsonElement): RawGrammar {
        val grammar = element as? JsonObject ?: throw IllegalArgumentException("A grammar must be a JSON object")
        val scopeName = grammar.string("scopeName") ?: throw IllegalArgumentException("A grammar must have a scopeName")
        return RawGrammar(
            scopeName = scopeName,
            name = grammar.string("name"),
            patterns = grammar.rules("patterns"),
            repository = grammar.ruleMap("repository"),
            injections = grammar.ruleMap("injections"),
            injectionSelector = grammar.string("injectionSelector"),
            fileTypes = grammar.strings("fileTypes"),
            firstLineMatch = grammar.string("firstLineMatch"),
        )
    }

    private fun ruleOf(rule: JsonObject): RawRule = RawRule(
        include = rule.string("include"),
        name = rule.string("name"),
        contentName = rule.string("contentName"),
        match = rule.string("match"),
        captures = rule.ruleMap("captures"),
        begin = rule.string("begin"),
        beginCaptures = rule.ruleMap("beginCaptures"),
        end = rule.string("end"),
        endCaptures = rule.ruleMap("endCaptures"),
        whilePattern = rule.string("while"),
        whileCaptures = rule.ruleMap("whileCaptures"),
        patterns = rule.rules("patterns"),
        repository = rule.ruleMap("repository"),
        applyEndPatternLast = rule.booleanOrInt("applyEndPatternLast"),
        comment = rule.string("comment"),
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }

    private fun JsonObject.booleanOrInt(key: String): Int? {
        val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
        return value.booleanOrNull?.let { if (it) 1 else 0 } ?: value.intOrNull
    }

    private fun JsonObject.rules(key: String): List<RawRule>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::ruleOf) }

    private fun JsonObject.ruleMap(key: String): Map<String, RawRule>? = when (val value = this[key]) {
        is JsonObject -> value.entries
            .mapNotNull { (name, rule) -> (rule as? JsonObject)?.let { name to ruleOf(it) } }
            .toMap()
        is JsonArray -> value.withIndex()
            .mapNotNull { (index, rule) -> (rule as? JsonObject)?.let { "$index" to ruleOf(it) } }
            .toMap()
        else -> null
    }
}
