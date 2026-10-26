// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.app.leaf.textmate

import dev.textmate.grammar.Grammar
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.raw.RawGrammar
import dev.textmate.regex.IOnigLib
import dev.textmate.regex.JoniOnigLib
import dev.textmate.registry.Registry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** A grammar that Leaf ships, as `grammars/index.json` lists it (see GRAMMARS.md). */
@Serializable
public data class ShippedGrammar(
    public val name: String,
    public val scopeName: String,
    public val file: String,
    /** Whether Leaf colours this language; the others are there because a listed grammar embeds them. */
    public val listed: Boolean = false,
    /** The names of the shipped grammars that this one includes. */
    public val embedded: List<String> = emptyList(),
    /** The scopes that this grammar injects into, when it is an injection. */
    public val injectTo: List<String> = emptyList(),
)

/**
 * The grammars in this module's resources. Each is read once, and kept: a [RawGrammar] doesn't change, so registries
 * on several threads can share them. A [Registry] and its grammars can't be shared.
 */
public object ShippedGrammars {

    private const val FOLDER = "grammars"

    public val all: List<ShippedGrammar> by lazy {
        Json.decodeFromString<List<ShippedGrammar>>(resourceText("$FOLDER/index.json"))
    }

    private val byScope: Map<String, ShippedGrammar> by lazy { all.associateBy { it.scopeName } }

    private val raw = ConcurrentHashMap<String, RawGrammar>()

    /** The grammar with [scopeName], read from its file the first time, or null when Leaf doesn't ship one. */
    public fun rawGrammar(scopeName: String): RawGrammar? {
        val entry = byScope[scopeName] ?: return null
        return raw.getOrPut(scopeName) { GrammarReader.readGrammar(resourceText("$FOLDER/${entry.file}")) }
    }

    /** The scopes of the grammars that inject into [scopeName], as VS Code finds them: by their `injectTo`. */
    public fun injectionsInto(scopeName: String): List<String> =
        all.filter { scopeName in it.injectTo }.map { it.scopeName }

    /** A registry that loads the shipped grammars. */
    public fun newRegistry(onigLib: IOnigLib = JoniOnigLib()): Registry =
        Registry(grammarSource = { scope -> rawGrammar(scope) }, onigLib = onigLib)

    /**
     * The grammar with [scopeName] from [registry], with the grammars that inject into it, or null when Leaf doesn't
     * ship one. The injections must be in the registry before the grammar is first loaded.
     */
    public fun loadGrammar(registry: Registry, scopeName: String): Grammar? {
        if (rawGrammar(scopeName) == null) return null
        for (injection in injectionsInto(scopeName)) registry.addGrammar(rawGrammar(injection) ?: continue)
        return registry.loadGrammar(scopeName)
    }

    private fun resourceText(path: String): String =
        (javaClass.classLoader.getResourceAsStream(path) ?: error("No resource $path")).use {
            it.readBytes().decodeToString()
        }
}
