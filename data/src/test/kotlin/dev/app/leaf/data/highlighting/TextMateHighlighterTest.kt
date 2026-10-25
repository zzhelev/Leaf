// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.highlighting

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.highlighting.SyntaxCategory.COMMENT
import dev.app.leaf.domain.highlighting.SyntaxCategory.FUNCTION
import dev.app.leaf.domain.highlighting.SyntaxCategory.KEYWORD
import dev.app.leaf.domain.highlighting.SyntaxCategory.STRING
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.textmate.ShippedGrammars
import dev.textmate.grammar.TokenizeLineResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TextMateHighlighterTest {

    private fun shipped(scopeName: String): LineTokenizer? =
        ShippedGrammars.loadGrammar(ShippedGrammars.newRegistry(), scopeName)?.let { LineTokenizer(it::tokenizeLine) }

    private fun highlighter(
        timeLimitMillis: Long = HIGHLIGHT_TIME_LIMIT_MS,
        maxLineLength: Int = MAX_TOKENIZED_LINE_LENGTH,
        cacheBytes: Long = HIGHLIGHT_CACHE_BYTES,
        clock: () -> Long = System::nanoTime,
        threads: Int = 2,
        newGrammar: (String) -> LineTokenizer? = ::shipped,
    ) = TextMateHighlighter(newGrammar, timeLimitMillis, maxLineLength, cacheBytes, clock, threads)

    /** The shipped grammar of each scope, with [change] around each line it tokenizes. */
    private fun wrapping(change: (line: String, tokenize: () -> TokenizeLineResult) -> TokenizeLineResult) =
        { scopeName: String ->
            val grammar = shipped(scopeName)!!
            LineTokenizer { line, state, timeLimit -> change(line) { grammar.tokenizeLine(line, state, timeLimit) } }
        }

    private fun HighlightedText.lines() = (0 until lineCount).map { spansOf(it) }

    /** The category of the span that covers [text] in [line] of [source], or null when none does. */
    private fun HighlightedText.categoryOf(source: String, line: Int, text: String, from: Int = 0): SyntaxCategory? {
        val start = source.lines()[line].indexOf(text, from)
        check(start >= 0) { "No \"$text\" in line $line" }
        return spansOf(line).firstOrNull { it.start <= start && it.end >= start + text.length }?.category
    }

    private val kotlin = """
        |/* A block comment
        |   on two lines */
        |fun greet(name: String) = println("Hello, ${'$'}name") // done
        |
    """.trimMargin()

    @Test
    fun `a Kotlin file gets the categories of its tokens`(): Unit = runBlocking {
        val text = highlighter()("source.kotlin", kotlin)!!
        assertEquals(4, text.lineCount)
        assertNull(text.plainFrom)
        assertEquals(listOf(SyntaxSpan(0, 18, COMMENT)), text.spansOf(0))
        assertEquals(listOf(SyntaxSpan(0, 18, COMMENT)), text.spansOf(1))
        assertEquals(KEYWORD, text.categoryOf(kotlin, 2, "fun"))
        assertEquals(FUNCTION, text.categoryOf(kotlin, 2, "greet"))
        assertEquals(SyntaxCategory.TYPE, text.categoryOf(kotlin, 2, "String"))
        assertEquals(STRING, text.categoryOf(kotlin, 2, "Hello, "))
        assertEquals(KEYWORD, text.categoryOf(kotlin, 2, "\$name"))
        assertEquals(COMMENT, text.categoryOf(kotlin, 2, "// done"))
        assertNull(text.categoryOf(kotlin, 2, "name: "))
        assertEquals(emptyList<SyntaxSpan>(), text.spansOf(3))
    }

    @Test
    fun `lines ending with a carriage return are coloured without it`(): Unit = runBlocking {
        val source = "val a = 1\r\nval b = \"x\"\r\n"
        val text = highlighter()("source.kotlin", source)!!
        assertEquals(3, text.lineCount)
        assertEquals(KEYWORD, text.spansOf(1).first().category)
        assertEquals(SyntaxSpan(8, 11, STRING), text.spansOf(1).last())
    }

    @Test
    fun `a Markdown file's code blocks are coloured in their own language`(): Unit = runBlocking {
        // VS Code's Markdown grammar embeds Java, Python and others, but not Kotlin.
        val source = "# Title\n\n```java\nclass A { void run() {} }\n```\n"
        val text = highlighter()("text.html.markdown", source)!!
        assertEquals(SyntaxSpan(0, 7, KEYWORD, bold = true), text.spansOf(0).single())
        assertEquals(KEYWORD, text.categoryOf(source, 3, "class"))
        assertEquals(FUNCTION, text.categoryOf(source, 3, "run"))
        assertEquals(STRING, text.categoryOf(source, 2, "```"))
    }

    @Test
    fun `a line past the length limit stays plain, and the next starts in the state that it started in`(): Unit =
        runBlocking {
            val long = "x".repeat(40) + " */ fun"
            val source = "/*\n$long\nstill a comment */ fun f()"
            val text = highlighter(maxLineLength = 40)("source.kotlin", source)!!
            assertEquals(emptyList<SyntaxSpan>(), text.spansOf(1))
            assertEquals(COMMENT, text.categoryOf(source, 2, "still a comment */"))
            assertEquals(KEYWORD, text.categoryOf(source, 2, "fun"))
            assertNull(text.plainFrom)
            // Within the limit, the long line ends the comment.
            val whole = highlighter(maxLineLength = 100)("source.kotlin", source)!!
            assertEquals(KEYWORD, whole.categoryOf(source, 1, "fun"))
            assertNull(whole.categoryOf(source, 2, "still"))
        }

    @Test
    fun `the lines after the time limit stay plain`(): Unit = runBlocking {
        // Each reading of the clock is a millisecond after the one before. The first sets the deadline 10 ms on, and
        // each line reads it once, so line 9 finds no time left.
        val now = AtomicLong()
        val clock = { now.addAndGet(1_000_000) }
        val source = (1..100).joinToString("\n") { "fun f$it() {}" }
        val text = highlighter(timeLimitMillis = 10, clock = clock)("source.kotlin", source)!!
        assertEquals(9, text.plainFrom)
        assertEquals(100, text.lineCount)
        for (line in 0..8) assertEquals(KEYWORD, text.spansOf(line).first().category, "line $line")
        for (line in 9..99) assertEquals(emptyList<SyntaxSpan>(), text.spansOf(line), "line $line")
    }

    @Test
    fun `a line that takes longer than the time left keeps its colours so far`(): Unit = runBlocking {
        val stopping = wrapping { line, tokenize ->
            val result = tokenize()
            if (line.startsWith("fun b")) result.copy(tokens = result.tokens.take(1), stoppedEarly = true) else result
        }
        val source = "fun a()\nfun b()\nfun c()"
        val text = highlighter(newGrammar = stopping)("source.kotlin", source)!!
        assertEquals(1, text.plainFrom)
        assertEquals(listOf(SyntaxSpan(0, 3, KEYWORD)), text.spansOf(1))
        assertEquals(emptyList<SyntaxSpan>(), text.spansOf(2))
    }

    @Test
    fun `results are kept by grammar and content`(): Unit = runBlocking {
        val highlighter = highlighter()
        val first = highlighter("source.kotlin", kotlin)!!
        assertTrue(highlighter.isKept("source.kotlin", kotlin))
        assertSame(first, highlighter("source.kotlin", kotlin))
        // The same text as another language, or another text, is coloured on its own.
        val asJava = highlighter("source.java", kotlin)!!
        assertNotSame(first, asJava)
        assertNotSame(first, highlighter("source.kotlin", "$kotlin "))
        assertSame(first, highlighter("source.kotlin", String(kotlin.toCharArray())))
    }

    @Test
    fun `the least recently used results go first`(): Unit = runBlocking {
        val texts = (1..4).map { n -> (1..10).joinToString("\n") { "fun f$n$it() = \"$it\"" } }
        val one = highlighter()("source.kotlin", texts[0])!!
        val bytes = 12L * one.spanCount + 4L * one.lineCount
        // Room for two of the four, which have as many spans.
        val highlighter = highlighter(cacheBytes = bytes * 2 + bytes / 2)
        val a = highlighter("source.kotlin", texts[0])!!
        highlighter("source.kotlin", texts[1])
        assertSame(a, highlighter("source.kotlin", texts[0]))
        highlighter("source.kotlin", texts[2])
        assertTrue(highlighter.isKept("source.kotlin", texts[0]))
        assertFalse(highlighter.isKept("source.kotlin", texts[1]))
        assertTrue(highlighter.isKept("source.kotlin", texts[2]))
        // A result larger than the room isn't kept, and pushes nothing out.
        val small = highlighter(cacheBytes = bytes / 2)
        small("source.kotlin", texts[3])
        assertFalse(small.isKept("source.kotlin", texts[3]))
    }

    @Test
    fun `cancelling the caller stops the work, and keeps nothing`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val lines = AtomicInteger()
        val counting = wrapping { _, tokenize ->
            if (lines.incrementAndGet() == 100) started.complete(Unit)
            tokenize()
        }
        val source = (1..1_000_000).joinToString("\n") { "val x$it = $it" }
        val highlighter = highlighter(timeLimitMillis = 600_000, newGrammar = counting)
        val job = launch(Dispatchers.Default) { highlighter("source.kotlin", source) }
        started.await()
        job.cancel()
        withTimeout(5_000) { job.join() }
        val done = lines.get()
        assertTrue(done < 1_000_000, "all $done lines were tokenized")
        Thread.sleep(200)
        assertEquals(done, lines.get(), "tokenizing went on after the cancellation")
        assertFalse(highlighter.isKept("source.kotlin", source))
    }

    @Test
    fun `a grammar that doesn't load leaves texts plain, and isn't tried again`(): Unit = runBlocking {
        val loads = AtomicInteger()
        val highlighter = highlighter(newGrammar = { loads.incrementAndGet(); error("broken") })
        repeat(3) { assertNull(highlighter("source.kotlin", "fun a() = $it")) }
        assertEquals(1, loads.get())
        // Another scope is tried once too.
        repeat(2) { assertNull(highlighter("source.unknown", "text $it")) }
        assertEquals(2, loads.get())
        assertNull(highlighter()("source.not-shipped", "text"))
    }

    @Test
    fun `a grammar that fails while colouring leaves that text plain, and is loaded again`(): Unit = runBlocking {
        val loads = AtomicInteger()
        val failing = wrapping { line, tokenize -> if (line == "boom") error("boom") else tokenize() }
        // One thread, so that the second text finds the grammar that the first left, if it's kept.
        val highlighter = highlighter(threads = 1, newGrammar = { loads.incrementAndGet(); failing(it) })
        assertNull(highlighter("source.kotlin", "fun a()\nboom"))
        assertFalse(highlighter.isKept("source.kotlin", "fun a()\nboom"))
        assertNotNull(highlighter("source.kotlin", "fun a()"))
        assertEquals(2, loads.get())
        assertNotNull(highlighter("source.kotlin", "fun b()"))
        assertEquals(2, loads.get())
    }

    @Test
    @Timeout(120)
    fun `texts coloured at once on both threads get the colours they get one at a time`(): Unit = runBlocking {
        val samples = File("../textmate/src/test/resources/conformance/samples").listFiles()!!
            .sortedBy { it.name }
            .mapNotNull { folder ->
                val entry = ShippedGrammars.all.single { it.name == folder.name }
                entry.scopeName to folder.listFiles()!!.single().readText()
            }
        val one = highlighter(cacheBytes = 0)
        val alone = samples.map { (scope, text) -> one(scope, text)!!.lines() }
        val shared = highlighter(cacheBytes = 0)
        val together = (1..3).flatMap { samples.indices.toList() }.shuffled(java.util.Random(7)).map { index ->
            async(Dispatchers.Default) { index to shared(samples[index].first, samples[index].second)!!.lines() }
        }.awaitAll()
        for ((index, lines) in together) assertEquals(alone[index], lines, samples[index].first)
    }
}
