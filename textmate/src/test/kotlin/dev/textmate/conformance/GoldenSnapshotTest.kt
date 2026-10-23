package dev.textmate.conformance

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GoldenSnapshotTest {

    companion object {
        @JvmStatic
        fun grammars(): List<Arguments> = listOf(
            Arguments.of("JSON", "grammars/JSON.tmLanguage.json", "conformance/golden/json.snapshot.json"),
            Arguments.of("Kotlin", "grammars/kotlin.tmLanguage.json", "conformance/golden/kotlin.snapshot.json"),
            Arguments.of("Markdown", "grammars/markdown.tmLanguage.json", "conformance/golden/markdown.snapshot.json"),
            Arguments.of(
                "JavaScript", "grammars/JavaScript.tmLanguage.json", "conformance/golden/javascript.snapshot.json"
            ),
            Arguments.of("Shell", "grammars/shell-unix-bash.tmLanguage.json", "conformance/golden/shell.snapshot.json"),
            Arguments.of("CSS", "grammars/css.tmLanguage.json", "conformance/golden/css.snapshot.json"),
            Arguments.of("Rust", "grammars/rust.tmLanguage.json", "conformance/golden/rust.snapshot.json"),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("grammars")
    fun `tokens match golden snapshot`(label: String, grammarResource: String, snapshotResource: String) {
        val rawGrammar = ConformanceTestSupport.loadRawGrammar(grammarResource)
        val grammar = ConformanceTestSupport.createGrammar(rawGrammar)
        val snapshot = ConformanceTestSupport.loadGoldenSnapshot(snapshotResource)

        for (file in snapshot.files) {
            ConformanceTestSupport.assertGrammarTokenization(
                grammar,
                file.lines,
                "$label/${file.source}"
            )
        }
    }
}
