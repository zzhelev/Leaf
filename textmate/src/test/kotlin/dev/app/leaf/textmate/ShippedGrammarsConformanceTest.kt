// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.app.leaf.textmate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Each shipped grammar's samples, tokenized here and by vscode-textmate (`tools/conformance/`), give the same tokens,
 * except on the lines that [KNOWN_DIFFERENCES] names, which must still differ: a fix shows as a failure to update.
 */
class ShippedGrammarsConformanceTest {

    companion object {
        private val root = File(ShippedGrammarsConformanceTest::class.java.classLoader.getResource("conformance")!!.toURI())
        private val samples = File(root, "samples")
        private val expected = File(root, "expected")

        /** Lines that differ from VS Code's tokens, by sample, and why (README.md, Differences from VS Code). */
        private val KNOWN_DIFFERENCES: Map<String, List<Int>> = mapOf(
            // `///`: the documentation comment's pattern has `\s+{1,0}`, which Joni refuses. Still a comment.
            "cpp/lanes.cpp" to listOf(17),
            // `await`: a look-behind with an unbounded repeat, `(?<!\.\s*)`.
            "csharp/Stash.cs" to listOf(17),
            // `~~old text~~`: a back-reference in a look-behind, `(?<=_\1)`.
            "markdown/README.md" to listOf(15),
            // A block's parameters after `{ ` and `do `: `(?<=\{|\{\s+|…)`.
            "ruby/hooks.rb" to listOf(24, 25),
            // The expressions of `{#if …}` and `{#each …}`: `(?<=(if|each|…).*?)\G`.
            "svelte/Stash.svelte" to listOf(8, 10),
        )

        @JvmStatic
        fun samples(): List<String> = samples.listFiles()!!.flatMap { folder ->
            folder.listFiles()!!.map { "${folder.name}/${it.name}" }
        }.sorted()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    fun `tokens match VS Code's`(sample: String) {
        val (name, file) = sample.split('/')
        val result = Conformance.compare(name, File(samples, sample), File(expected, "$name/$file.tokens"))
        assertEquals(KNOWN_DIFFERENCES[sample].orEmpty(), result.differingLines, result.firstDifference)
    }

    @Test
    fun `every language that Leaf colours has a sample`() {
        val withSamples = samples.listFiles()!!.map { it.name }.toSet()
        val missing = ShippedGrammars.all.filter { it.listed && it.name !in withSamples }.map { it.name }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every sample has VS Code's tokens`() {
        for (sample in samples()) assertTrue(File(expected, "$sample.tokens").isFile, "No tokens for $sample")
    }
}
