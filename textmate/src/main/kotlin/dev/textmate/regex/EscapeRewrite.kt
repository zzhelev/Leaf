// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

/**
 * Rewrites the escapes that Oniguruma 6.9, which grammars are written for, reads one way and Joni another, into what
 * Joni reads the same way: `\N`, any character but a newline, becomes `(?-m:.)`, and `\O`, any character, `(?m:.)`, as
 * Oniguruma's documentation defines them. Joni takes both as the letters themselves. In a character class, where
 * Oniguruma reads them as letters too, they stay.
 */
internal object EscapeRewrite {

    /** [pattern] with its `\N` and `\O` rewritten, or [pattern] itself when it has none. */
    fun rewrite(pattern: String): String {
        if (!pattern.contains("\\N") && !pattern.contains("\\O")) return pattern
        val out = StringBuilder(pattern.length + 8)
        var classDepth = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    out.append(
                        when {
                            classDepth > 0 -> pattern.substring(i, i + 2)
                            next == 'N' -> "(?-m:.)"
                            next == 'O' -> "(?m:.)"
                            else -> pattern.substring(i, i + 2)
                        }
                    )
                    i += 2
                    continue
                }
                classDepth > 0 && pattern.startsWith("[:", i) && POSIX_BRACKET.matchAt(pattern, i) != null -> {
                    val bracket = POSIX_BRACKET.matchAt(pattern, i)!!.value
                    out.append(bracket)
                    i += bracket.length
                    continue
                }
                c == '[' -> {
                    classDepth++
                    out.append(c)
                    i++
                    // A `]` right after `[` or `[^` is a character, not the class's end.
                    if (i < pattern.length && pattern[i] == '^') out.append(pattern[i++])
                    if (i < pattern.length && pattern[i] == ']') out.append(pattern[i++])
                    continue
                }
                c == ']' && classDepth > 0 -> classDepth--
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private val POSIX_BRACKET = Regex("""\[:\^?[a-z]+:]""")
}
