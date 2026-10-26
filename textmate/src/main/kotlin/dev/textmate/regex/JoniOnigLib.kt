package dev.textmate.regex

import org.jcodings.specific.UTF8Encoding
import org.joni.Matcher
import org.joni.Option
import org.joni.Regex
import org.joni.Region
import org.joni.Syntax
import org.joni.WarnCallback
import org.joni.exception.JOniException

public class JoniOnigLib : IOnigLib {

    private val _sentinelPatterns = mutableSetOf<String>()

    /** Number of unique regex patterns that fell back to the never-matching sentinel. */
    internal val sentinelPatternCount: Int get() = _sentinelPatterns.size

    override fun createOnigScanner(patterns: List<String>): OnigScanner {
        return JoniOnigScanner(patterns) { pattern -> _sentinelPatterns.add(pattern) }
    }

    override fun createOnigString(str: String): OnigString {
        return OnigString(str)
    }
}

internal class JoniOnigScanner(
    patterns: List<String>,
    private val onSentinel: ((String) -> Unit)? = null
) : OnigScanner {

    private val regexes: List<Regex> = patterns.map { pattern ->
        val compiled = CompiledPatterns.get(pattern, ::compilePattern)
        if (compiled.refused) onSentinel?.invoke(pattern)
        compiled.regex
    }

    /**
     * Each pattern's last search, as vscode-oniguruma keeps it: the string searched, the byte it started at, and the
     * match (its start, or -1 for none). The tokenizer searches a line from one position after another, and without
     * this every pattern searched to the line's end each time, so that a line took the square of its length.
     */
    private val lastString = arrayOfNulls<OnigString>(regexes.size)
    private val lastStart = IntArray(regexes.size)
    private val lastMatchStart = IntArray(regexes.size)
    private val lastResult = arrayOfNulls<MatchResult>(regexes.size)

    /** Patterns with `\G`, whose matches depend on where the search starts, so that no search is reused. */
    private val anchoredToStart = BooleanArray(patterns.size) { hasGAnchor(patterns[it]) }

    override fun findNextMatchSync(string: OnigString, startPosition: Int): MatchResult? {
        val bytes = string.utf8Bytes
        val byteStart = string.charToByteOffset(startPosition)
        val byteLength = bytes.size

        var bestResult: MatchResult? = null
        var bestStartByte = Int.MAX_VALUE

        for ((patternIndex, regex) in regexes.withIndex()) {
            // A search from an earlier start that found nothing, or a match at or after this start, is this search's
            // answer too: the leftmost match from there is the leftmost from here.
            val reusable = !anchoredToStart[patternIndex] && lastString[patternIndex] === string &&
                lastStart[patternIndex] <= byteStart &&
                (lastMatchStart[patternIndex] < 0 || lastMatchStart[patternIndex] >= byteStart)
            if (!reusable) {
                val matcher = regex.matcher(bytes)
                val status = matcher.search(byteStart, byteLength, Option.DEFAULT)
                lastString[patternIndex] = string
                lastStart[patternIndex] = byteStart
                if (status == Matcher.FAILED) {
                    lastMatchStart[patternIndex] = -1
                    lastResult[patternIndex] = null
                } else {
                    val region = matcher.eagerRegion
                    lastMatchStart[patternIndex] = region.getBeg(0)
                    lastResult[patternIndex] = buildMatchResult(patternIndex, region, string)
                }
            }

            val matchStartByte = lastMatchStart[patternIndex]
            if (matchStartByte < 0) continue

            if (matchStartByte < bestStartByte) {
                bestStartByte = matchStartByte
                bestResult = lastResult[patternIndex]
            }

            if (matchStartByte == byteStart) {
                break
            }
        }

        return bestResult
    }

    private fun buildMatchResult(
        patternIndex: Int,
        region: Region,
        string: OnigString
    ): MatchResult {
        val captureIndices = (0 until region.numRegs).map { i ->
            val begByte = region.getBeg(i)
            val endByte = region.getEnd(i)
            if (begByte < 0) {
                CaptureIndex(start = 0, end = 0, length = 0)
            } else {
                val startChar = string.byteToCharOffset(begByte)
                val endChar = string.byteToCharOffset(endByte)
                CaptureIndex(start = startChar, end = endChar, length = endChar - startChar)
            }
        }
        return MatchResult(index = patternIndex, captureIndices = captureIndices)
    }

    private fun compilePattern(pattern: String): CompiledPattern {
        // Escapes that Joni reads differently from the Oniguruma that grammars are written for.
        val readable = EscapeRewrite.rewrite(pattern)
        return try {
            CompiledPattern(compile(readable), refused = false)
        } catch (_: JOniException) {
            // Joni takes fewer look-behinds than that Oniguruma: try them spread out.
            val rewritten = LookbehindRewrite.rewrite(readable) { compileOrNull(it) != null }?.let { compileOrNull(it) }
            CompiledPattern(rewritten ?: NEVER_MATCH_REGEX, refused = rewritten == null)
        }
    }

    private fun compileOrNull(pattern: String): Regex? =
        try {
            compile(pattern)
        } catch (_: JOniException) {
            null
        }

    private fun compile(pattern: String): Regex {
        val patternBytes = pattern.toByteArray(Charsets.UTF_8)
        return Regex(
            patternBytes,
            0,
            patternBytes.size,
            Option.CAPTURE_GROUP,
            UTF8Encoding.INSTANCE,
            Syntax.DEFAULT,
            WarnCallback.NONE
        )
    }

    companion object {
        /** Whether [pattern] has `\G`, not escaped, as vscode-oniguruma looks for it. */
        internal fun hasGAnchor(pattern: String): Boolean {
            var i = 0
            while (i < pattern.length - 1) {
                if (pattern[i] == '\\') {
                    if (pattern[i + 1] == 'G') return true
                    i++
                }
                i++
            }
            return false
        }

        private val NEVER_MATCH_REGEX: Regex by lazy {
            val bytes = "(?!)".toByteArray(Charsets.UTF_8)
            Regex(bytes, 0, bytes.size, Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, Syntax.DEFAULT, WarnCallback.NONE)
        }
    }
}
