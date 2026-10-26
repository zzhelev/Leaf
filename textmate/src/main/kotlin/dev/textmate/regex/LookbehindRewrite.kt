// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

/**
 * Rewrites look-behinds that Joni refuses into ones it takes, which match the same text.
 *
 * Grammars are written for Oniguruma 6.9, which VS Code runs, and which takes a look-behind whose alternatives have
 * different lengths anywhere inside it. Joni follows an older Oniguruma, which takes different lengths only at the
 * look-behind's top level: `(?<=a|bc)`, but not `(?<=(?:a|b)c)` or `(?<=ab?)`. A look-behind only asks whether one of
 * its alternatives ends where the match starts, so spreading a group's alternatives, an optional piece or a small
 * bounded repeat into top-level alternatives changes nothing: `(?<=(?:^|[^\\])\n)` is `(?<=^\n|[^\\]\n)`.
 *
 * Only look-behinds that Joni refuses on their own are spread; the rest of the pattern is copied as it is. A capturing
 * group in a spread look-behind can't be copied into each alternative, as that would number the groups after it
 * differently: it is spread as a group that doesn't capture, and an empty group, `()`, is put before the look-behind in
 * its place. That group then captures an empty string where Oniguruma captures text before the match, which grammars
 * rarely use, and without the rewrite the whole pattern never matches.
 *
 * A look-behind is left as it is when spreading it could change what it matches: one with a named group, a
 * back-reference, an option that applies to the rest of its group (`(?i)`), or an escape whose end depends on what
 * follows it. One with an unbounded repeat (`\s*`) can't be spread. Patterns in free-spacing mode are left alone.
 */
internal object LookbehindRewrite {

    /** More alternatives than this in one look-behind are left alone. */
    private const val MAX_ALTERNATIVES = 512

    /** A repeat of up to this many times is spread; a larger one is left alone. */
    private const val MAX_REPEAT = 8

    /**
     * [pattern] with the look-behinds that [isAccepted] refuses spread, or null when none changed or the pattern
     * couldn't be read.
     */
    fun rewrite(pattern: String, isAccepted: (String) -> Boolean): String? {
        if (FREE_SPACING.containsMatchIn(pattern)) return null
        val rewritten = try {
            Reader(pattern, isAccepted).copy(0, pattern.length)
        } catch (_: Unreadable) {
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return rewritten.takeIf { it != pattern }
    }

    private val FREE_SPACING = Regex("""\(\?[imx]*x""")

    /** The pattern has something this reader doesn't know: nothing in it is rewritten. */
    private class Unreadable : Exception()

    /** This look-behind can't be spread: it stays as it is. */
    private class CantSpread : Exception()

    /**
     * Reads a pattern a piece (an atom and its quantifier) at a time. Outside look-behinds it only finds where pieces
     * end, and copies them; inside one it splits the body into alternatives and pieces, and spreads them.
     */
    private class Reader(private val s: String, private val isAccepted: (String) -> Boolean) {

        /** Capturing groups made non-capturing in the look-behind being spread. */
        private var movedCaptures = 0

        /** [from] to [to], with every look-behind in it that Joni refuses spread. */
        fun copy(from: Int, to: Int): String {
            val out = StringBuilder()
            var i = from
            while (i < to) {
                if (s[i] == '|') {
                    out.append('|')
                    i++
                    continue
                }
                val atomEnd = atomEnd(i)
                val end = quantifierEnd(atomEnd)
                out.append(group(i, atomEnd) ?: s.substring(i, atomEnd)).append(s, atomEnd, end)
                i = end
            }
            return out.toString()
        }

        /** The group from [start] to [end] with its look-behinds spread, or null when [start] isn't a group. */
        private fun group(start: Int, end: Int): String? {
            if (s[start] != '(' || s.startsWith("(?#", start)) return null
            val bodyStart = bodyStart(start)
            val prefix = s.substring(start, bodyStart)
            if (!isLookbehind(start)) return prefix + copy(bodyStart, end - 1) + ")"

            val original = s.substring(start, end)
            if (isAccepted(original)) return original
            val outerMoved = movedCaptures
            movedCaptures = 0
            return try {
                val body = spread(bodyStart, end - 1)
                "()".repeat(movedCaptures) + prefix + body + ")"
            } catch (_: CantSpread) {
                prefix + copy(bodyStart, end - 1) + ")"
            } finally {
                movedCaptures = outerMoved
            }
        }

        private fun isLookbehind(i: Int) = s.startsWith("(?<=", i) || s.startsWith("(?<!", i)

        /** The body of a look-behind, from [from] to [to], written as top-level alternatives. */
        private fun spread(from: Int, to: Int): String {
            val alternatives = spreadAlternatives(from, to)
            if (alternatives.size > MAX_ALTERNATIVES) throw CantSpread()
            return alternatives.joinToString("|")
        }

        private fun spreadAlternatives(from: Int, to: Int): List<String> =
            alternatives(from, to).flatMap { (start, end) -> sequences(start, end) }

        /** Every way to write the alternative from [from] to [to] without groups of alternatives or optional pieces. */
        private fun sequences(from: Int, to: Int): List<String> {
            var results = listOf("")
            var i = from
            while (i < to) {
                val atomEnd = atomEnd(i)
                val pieceEnd = quantifierEnd(atomEnd)
                val choices = choices(i, atomEnd, pieceEnd)
                results = results.flatMap { prefix -> choices.map { prefix + it } }
                if (results.size > MAX_ALTERNATIVES) throw CantSpread()
                i = pieceEnd
            }
            return results
        }

        /** The ways to write the atom from [start] to [atomEnd] with its quantifier, which ends at [pieceEnd]. */
        private fun choices(start: Int, atomEnd: Int, pieceEnd: Int): List<String> {
            val forms: List<String> = when {
                s.startsWith("(?:", start) -> spreadAlternatives(start + 3, atomEnd - 1)
                s[start] == '(' && !s.startsWith("(?", start) -> {
                    movedCaptures++
                    spreadAlternatives(start + 1, atomEnd - 1)
                }
                s[start] == '(' -> listOf(keptGroup(start, atomEnd))
                s[start] == '\\' -> listOf(keptEscape(start, atomEnd))
                s[start] == '{' || s[start] == '}' -> throw CantSpread() // a brace that could join a quantifier
                else -> listOf(s.substring(start, atomEnd))
            }
            val quantifier = s.substring(atomEnd, pieceEnd)
            val counts = counts(quantifier)
            if (counts == null) {
                // The atom keeps its quantifier, which needs it whole.
                if (forms.size > 1) throw CantSpread()
                return listOf(forms.single() + quantifier)
            }
            return counts.flatMap { count -> repeated(forms, count) }
        }

        /**
         * A group in a look-behind other than `(?:` or a capturing one, kept whole, when keeping it whole changes
         * nothing: a look-around, an atomic group, a group with options, or a comment.
         */
        private fun keptGroup(start: Int, end: Int): String {
            if (s.startsWith("(?#", start)) return s.substring(start, end)
            val prefix = s.substring(start, bodyStart(start))
            val kept = prefix == "(?=" || prefix == "(?!" || prefix == "(?>" || isLookbehind(start) ||
                OPTIONS_WITH_BODY.matches(prefix)
            // Not named groups, `(?i)` or absent groups, nor a capture inside, which the copies would number twice.
            if (!kept || hasCapture(bodyStart(start), end - 1)) throw CantSpread()
            return group(start, end)!!
        }

        /** Whether the pattern from [from] to [to] has a capturing group. */
        private fun hasCapture(from: Int, to: Int): Boolean {
            var i = from
            while (i < to) {
                if (s[i] == '(' && !s.startsWith("(?#", i)) {
                    val bodyStart = bodyStart(i)
                    val prefix = s.substring(i, bodyStart)
                    val named = (prefix.startsWith("(?<") && !isLookbehind(i)) || prefix.startsWith("(?'")
                    if (prefix == "(" || named || hasCapture(bodyStart, groupEnd(i) - 1)) return true
                }
                i = if (s[i] == '|') i + 1 else quantifierEnd(atomEnd(i))
            }
            return false
        }

        /** An escape in a look-behind, kept whole, unless it refers to a group or ends where what follows decides. */
        private fun keptEscape(start: Int, end: Int): String {
            val escape = s.substring(start, end)
            val next = escape.getOrNull(1)
            if (next == null || next in '1'..'9' || next == 'k' || next == 'g') throw CantSpread()
            if (next == 'x' && !escape.startsWith("\\x{") && escape.length < 4) throw CantSpread()
            if (next == '0' && escape.length < 4) throw CantSpread()
            return escape
        }

        /** How many times a quantifier allows, when it is none, `?`, `??` or a small bounded repeat; otherwise null. */
        private fun counts(quantifier: String): IntRange? {
            if (quantifier.isEmpty()) return 1..1
            if (quantifier == "?" || quantifier == "??") return 0..1
            val interval = INTERVAL.matchEntire(quantifier.removeSuffix("?")) ?: return null
            val (lowText, comma, highText) = interval.destructured
            val low = lowText.ifEmpty { "0" }.toInt()
            val high = when {
                comma.isEmpty() -> low
                highText.isEmpty() -> return null
                else -> highText.toInt()
            }
            if (low > high || high > MAX_REPEAT) return null
            return low..high
        }

        private fun repeated(forms: List<String>, count: Int): List<String> {
            var results = listOf("")
            repeat(count) {
                results = results.flatMap { prefix -> forms.map { prefix + it } }
                if (results.size > MAX_ALTERNATIVES) throw CantSpread()
            }
            return results
        }

        /** The top-level alternatives between [from] and [to], as ranges. */
        private fun alternatives(from: Int, to: Int): List<Pair<Int, Int>> {
            val result = mutableListOf<Pair<Int, Int>>()
            var start = from
            var i = from
            while (i < to) {
                if (s[i] == '|') {
                    result += start to i
                    start = i + 1
                    i++
                } else {
                    i = quantifierEnd(atomEnd(i))
                }
            }
            result += start to to
            return result
        }

        private fun atomEnd(i: Int): Int = when (s[i]) {
            '\\' -> escapeEnd(i)
            '[' -> classEnd(i)
            '(' -> groupEnd(i)
            ')' -> throw Unreadable()
            else -> if (s[i].isHighSurrogate() && i + 1 < s.length) i + 2 else i + 1
        }

        /** Where the quantifiers after an atom end: `?`, `*`, `+` or an interval, each lazy or possessive. */
        private fun quantifierEnd(i: Int): Int {
            var end = i
            while (end < s.length) {
                end = when (s[end]) {
                    '?', '*', '+' -> end + 1
                    '{' -> INTERVAL_AT.matchAt(s, end)?.let { end + it.value.length } ?: return end
                    else -> return end
                }
            }
            return end
        }

        private fun escapeEnd(i: Int): Int {
            if (i + 1 >= s.length) throw Unreadable()
            val c = s[i + 1]
            return when {
                c in "xpPou" && s.startsWith("{", i + 2) -> closing('}', i + 2) + 1
                (c == 'k' || c == 'g') && i + 2 < s.length && s[i + 2] in "<'" ->
                    closing(if (s[i + 2] == '<') '>' else '\'', i + 3) + 1
                c == 'x' -> digitsEnd(i + 2, 2, HEX)
                c == 'u' -> digitsEnd(i + 2, 4, HEX)
                c == '0' -> digitsEnd(i + 2, 2, OCTAL)
                c == 'c' -> minOf(i + 3, s.length)
                c.isHighSurrogate() && i + 2 < s.length -> i + 3
                else -> i + 2
            }
        }

        private fun closing(char: Char, from: Int): Int = s.indexOf(char, from).takeIf { it >= 0 } ?: throw Unreadable()

        private fun digitsEnd(from: Int, max: Int, digits: String): Int {
            var j = from
            while (j < s.length && j < from + max && s[j] in digits) j++
            return j
        }

        /** Where a character class ends, with Oniguruma's nested classes and POSIX brackets. */
        private fun classEnd(i: Int): Int {
            var j = i + 1
            if (j < s.length && s[j] == '^') j++
            if (j < s.length && s[j] == ']') j++ // a `]` that opens a class is a character
            while (j < s.length) {
                val posix = if (s.startsWith("[:", j)) POSIX_BRACKET.matchAt(s, j) else null
                j = when {
                    // In a class, `\1` and `\k` are characters.
                    s[j] == '\\' && j + 1 < s.length && (s[j + 1].isDigit() || s[j + 1] in "kg") -> j + 2
                    s[j] == '\\' -> escapeEnd(j)
                    posix != null -> j + posix.value.length
                    s[j] == '[' -> classEnd(j)
                    s[j] == ']' -> return j + 1
                    else -> j + 1
                }
            }
            throw Unreadable()
        }

        /** Where a group ends, after its `)`. */
        private fun groupEnd(i: Int): Int {
            if (s.startsWith("(?#", i)) return closing(')', i) + 1
            var j = bodyStart(i)
            while (j < s.length) {
                j = when (s[j]) {
                    ')' -> return j + 1
                    '|' -> j + 1
                    else -> quantifierEnd(atomEnd(j))
                }
            }
            throw Unreadable()
        }

        /** Where a group's body starts: after `(`, `(?:`, `(?<=`, `(?<name>`, `(?i:`; at the `)` of `(?i)`. */
        private fun bodyStart(i: Int): Int {
            if (!s.startsWith("(?", i)) return i + 1
            if (isLookbehind(i)) return i + 4
            if (s.startsWith("(?<", i) || s.startsWith("(?'", i)) {
                return closing(if (s[i + 2] == '<') '>' else '\'', i + 3) + 1
            }
            val options = OPTIONS.matchAt(s, i + 2)!!.value.length
            val k = i + 2 + options
            return when {
                k >= s.length -> throw Unreadable()
                s[k] == ')' && options > 0 -> k
                s[k] == ':' -> k + 1
                options == 0 && s[k] in "=!>~" -> k + 1
                else -> throw Unreadable() // conditionals and other constructs this reader doesn't know
            }
        }
    }

    private const val HEX = "0123456789abcdefABCDEF"
    private const val OCTAL = "01234567"
    private val INTERVAL = Regex("""\{(\d*)(,?)(\d*)}""")
    private val INTERVAL_AT = Regex("""\{(?:\d+|\d+,\d*|,\d+)}""")
    private val POSIX_BRACKET = Regex("""\[:\^?[a-z]+:]""")
    private val OPTIONS = Regex("""[imx]*(?:-[imx]*)?""")
    private val OPTIONS_WITH_BODY = Regex("""\(\?[imx]*(?:-[imx]*)?:""")
}
