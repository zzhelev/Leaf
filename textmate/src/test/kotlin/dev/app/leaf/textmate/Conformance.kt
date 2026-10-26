// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.app.leaf.textmate

import dev.textmate.grammar.Grammar
import dev.textmate.grammar.tokenize.StateStack
import java.io.File

/** Compares this module's tokens for the shipped grammars' samples with VS Code's (`tools/conformance/`). */
internal object Conformance {

    /** A sample, `<grammar name>/<file>`, and the lines whose tokens differ from VS Code's (0-based). */
    data class Result(val sample: String, val differingLines: List<Int>, val firstDifference: String?)

    /** Compares each sample in [samples]`/<grammar name>/` with its tokens in [expected]. */
    fun compare(samples: File, expected: File): List<Result> =
        samples.listFiles()!!.sortedBy { it.name }.flatMap { folder ->
            folder.listFiles()!!.sortedBy { it.name }.map { file ->
                compare(folder.name, file, File(expected, "${folder.name}/${file.name}.tokens"))
            }
        }

    fun compare(grammarName: String, sample: File, expected: File): Result {
        val entry = ShippedGrammars.all.single { it.name == grammarName }
        val grammar = ShippedGrammars.loadGrammar(ShippedGrammars.newRegistry(), entry.scopeName)
            ?: error("$grammarName doesn't load")
        val lines = linesOf(sample.readText())
        val actual = tokens(grammar, lines).groupBy { lineOf(it) }
        val wanted = expected.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.groupBy { lineOf(it) }
        val differing = lines.indices.filter { actual[it].orEmpty() != wanted[it].orEmpty() }
        val first = differing.firstOrNull()?.let { line ->
            val a = actual[line].orEmpty()
            val w = wanted[line].orEmpty()
            val at = a.indices.firstOrNull { a[it] != w.getOrNull(it) } ?: a.size
            "line $line: ${lines[line].take(80)}\n  VS Code: ${w.getOrNull(at)}\n  Leaf:    ${a.getOrNull(at)}"
        }
        return Result("$grammarName/${sample.name}", differing, first)
    }

    /** The lines of a file as the generator splits them: at `\n` or `\r\n`, without an empty last line. */
    fun linesOf(text: String): List<String> =
        text.split(Regex("\r?\n")).let { if (it.lastOrNull() == "") it.dropLast(1) else it }

    /** Each token as `<line>:<start>-<end> <scopes>`, as `tools/conformance/generate.mjs` writes VS Code's. */
    fun tokens(grammar: Grammar, lines: List<String>): List<String> {
        var state: StateStack? = null
        return lines.flatMapIndexed { index, line ->
            val result = grammar.tokenizeLine(line, state)
            state = result.ruleStack
            result.tokens.map { "$index:${it.startIndex}-${minOf(it.endIndex, line.length)} ${it.scopes.joinToString(" ")}" }
        }
    }

    private fun lineOf(token: String) = token.substringBefore(':').toInt()
}
