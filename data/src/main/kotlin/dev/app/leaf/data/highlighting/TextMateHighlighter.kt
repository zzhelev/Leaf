// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.highlighting

import dev.app.leaf.common.printError
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.isBoldSyntax
import dev.app.leaf.domain.highlighting.isItalicSyntax
import dev.app.leaf.domain.highlighting.lineCountOf
import dev.app.leaf.domain.highlighting.linesOf
import dev.app.leaf.domain.highlighting.syntaxCategoryOf
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import dev.app.leaf.textmate.ShippedGrammars
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.TokenizeLineResult
import dev.textmate.grammar.tokenize.StateStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TextMateHighlighter"

/** How long colouring one text may take, from its first line; the lines after stay plain. */
const val HIGHLIGHT_TIME_LIMIT_MS = 2_000L

/**
 * Lines longer than this aren't tokenized: they stay plain, and the next line starts in the state that they started
 * in, as VS Code does past its `editor.maxTokenizationLineLength` (20,000). The diff pane shows a line's first 10,000
 * characters and colours the first 1,000; minified JavaScript takes about 25 µs a character, so such a line takes a
 * quarter of a second (2026-10-23).
 */
const val MAX_TOKENIZED_LINE_LENGTH = 10_000

/** How many bytes the kept results may take (12 a span, 4 a line). */
const val HIGHLIGHT_CACHE_BYTES = 32L * 1024 * 1024

/** How many threads colour texts: a diff's two versions at once. */
private const val THREADS = 2

/**
 * How many grammars each thread keeps compiled. All 59 languages' grammars take about 60 MB compiled, and a second set
 * 37 MB more, as they share their regular expressions (2026-10-25); a repository has a few languages.
 */
private const val MAX_GRAMMARS_PER_THREAD = 12

/** Tokenizes a line from the state that the line before ended in, as [Grammar.tokenizeLine] does. */
internal fun interface LineTokenizer {
    fun tokenizeLine(lineText: String, prevState: StateStack?, timeLimit: Int): TokenizeLineResult
}

/**
 * Colours texts with the TextMate grammars that Leaf ships (fork-only, `docs/fork/syntax-highlighting.md`, 4.3).
 *
 * A [Grammar] isn't thread-safe, so each of the [THREADS] threads loads its own, once, the first time it needs it.
 * Results are kept by the grammar and the text's content, the least recently used dropped first past
 * [HIGHLIGHT_CACHE_BYTES]. Colouring checks for cancellation before each line.
 */
@Singleton
class TextMateHighlighter internal constructor(
    /** Loads the grammar of a scope, on the thread that will use it; null when Leaf ships none. */
    private val newGrammar: (scopeName: String) -> LineTokenizer?,
    private val timeLimitMillis: Long = HIGHLIGHT_TIME_LIMIT_MS,
    private val maxLineLength: Int = MAX_TOKENIZED_LINE_LENGTH,
    private val cacheBytes: Long = HIGHLIGHT_CACHE_BYTES,
    /** The clock that the time limit is read on, in nanoseconds. */
    private val clock: () -> Long = System::nanoTime,
    threads: Int = THREADS,
) : IHighlightTextAction {

    @Inject
    constructor() : this(newGrammar = ::shippedGrammar)

    private val dispatcher = Executors.newFixedThreadPool(threads) { runnable ->
        Thread(runnable, "Leaf highlighter ${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Each thread's grammars, by scope, the least recently used dropped past [MAX_GRAMMARS_PER_THREAD]. */
    private val grammars = ThreadLocal.withInitial {
        object : LinkedHashMap<String, LineTokenizer>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LineTokenizer>) =
                size > MAX_GRAMMARS_PER_THREAD
        }
    }

    /** Scopes whose grammar didn't load: not tried again. */
    private val unloadable = ConcurrentHashMap.newKeySet<String>()

    /** Scopes whose grammar failed while colouring, logged once each. */
    private val failing = ConcurrentHashMap.newKeySet<String>()

    private val cache = LinkedHashMap<String, HighlightedText>(16, 0.75f, true)
    private var cachedBytes = 0L

    override suspend fun invoke(scopeName: String, text: String): HighlightedText? = withContext(dispatcher) {
        val key = keyOf(scopeName, text)
        cached(key)?.let { return@withContext it }
        val grammar = grammarOf(scopeName) ?: return@withContext null
        val result = try {
            tokenize(grammar, text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The grammar may have been left half compiled: load it again next time.
            grammars.get().remove(scopeName)
            if (failing.add(scopeName)) printError(TAG, "Colouring with $scopeName failed", e)
            return@withContext null
        }
        keep(key, result)
        result
    }

    private fun grammarOf(scopeName: String): LineTokenizer? {
        if (scopeName in unloadable) return null
        val threadGrammars = grammars.get()
        threadGrammars[scopeName]?.let { return it }
        val grammar = try {
            newGrammar(scopeName)
        } catch (e: Exception) {
            printError(TAG, "The grammar $scopeName doesn't load", e)
            null
        }
        if (grammar == null) {
            unloadable.add(scopeName)
            return null
        }
        threadGrammars[scopeName] = grammar
        return grammar
    }

    private fun CoroutineScope.tokenize(grammar: LineTokenizer, text: String): HighlightedText {
        val builder = HighlightedText.Builder()
        val deadline = clock() + timeLimitMillis * NANOS_PER_MILLI
        var state: StateStack? = null
        var plainFrom: Int? = null
        for ((index, line) in linesOf(text).withIndex()) {
            ensureActive()
            // A line too long to tokenize stays plain, and the next starts in the state that it started in.
            if (line.length <= maxLineLength) {
                val left = (deadline - clock()) / NANOS_PER_MILLI
                if (left <= 0) {
                    plainFrom = index
                    break
                }
                val result = grammar.tokenizeLine(line, state, left.toInt())
                for (token in result.tokens) {
                    builder.add(
                        start = token.startIndex,
                        end = minOf(token.endIndex, line.length),
                        category = syntaxCategoryOf(token.scopes),
                        bold = isBoldSyntax(token.scopes),
                        italic = isItalicSyntax(token.scopes),
                    )
                }
                if (result.stoppedEarly) {
                    builder.endLine()
                    plainFrom = index
                    break
                }
                state = result.ruleStack
            }
            builder.endLine()
        }
        return builder.build(lineCountOf(text), plainFrom)
    }

    @Synchronized
    private fun cached(key: String): HighlightedText? = cache[key]

    /** Whether the colours of [text] in [scopeName] are kept. For tests. */
    @Synchronized
    internal fun isKept(scopeName: String, text: String): Boolean = cache.containsKey(keyOf(scopeName, text))

    @Synchronized
    private fun keep(key: String, result: HighlightedText) {
        val bytes = bytesOf(result)
        if (bytes > cacheBytes) return
        cache.put(key, result)?.let { cachedBytes -= bytesOf(it) }
        cachedBytes += bytes
        val entries = cache.entries.iterator()
        while (cachedBytes > cacheBytes && entries.hasNext()) {
            val oldest = entries.next()
            if (oldest.key == key) continue
            cachedBytes -= bytesOf(oldest.value)
            entries.remove()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val threadNumber = AtomicInteger()

        fun bytesOf(text: HighlightedText): Long = 12L * text.spanCount + 4L * text.lineCount

        /** The grammar of [scopeName] and the grammars that inject into it, in a registry of their own. */
        fun shippedGrammar(scopeName: String): LineTokenizer? =
            ShippedGrammars.loadGrammar(ShippedGrammars.newRegistry(), scopeName)
                ?.let { LineTokenizer(it::tokenizeLine) }

        fun keyOf(scopeName: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return scopeName + " " + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
