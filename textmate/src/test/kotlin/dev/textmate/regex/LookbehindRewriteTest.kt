// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

import org.jcodings.specific.UTF8Encoding
import org.joni.Matcher
import org.joni.Option
import org.joni.Regex
import org.joni.Syntax
import org.joni.WarnCallback
import org.joni.exception.JOniException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.random.Random

class LookbehindRewriteTest {

    private fun joni(pattern: String): Regex? = try {
        val bytes = pattern.toByteArray()
        Regex(bytes, 0, bytes.size, Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, Syntax.DEFAULT, WarnCallback.NONE)
    } catch (_: JOniException) {
        null
    }

    private fun rewrite(pattern: String): String? = LookbehindRewrite.rewrite(pattern) { joni(it) != null }

    /** Asserts that Joni refuses [pattern], and takes its rewrite, which is [expected]. */
    private fun assertSpread(pattern: String, expected: String) {
        assertNull(joni(pattern), "Joni takes $pattern already")
        assertEquals(expected, rewrite(pattern))
        assertNotNull(joni(expected), "Joni refuses $expected")
    }

    // ── What grammars have ─────────────────────────────────────────────

    @Test
    fun `a group of alternatives of different lengths is spread to the top level`() {
        // C++, C and Objective-C: a line that a backslash doesn't continue.
        assertSpread("""(?<!\\)(?:(?=\n)|(?<=(?:^|[^\\])\n)(?=$))""", """(?<!\\)(?:(?=\n)|(?<=^\n|[^\\]\n)(?=$))""")
        // Rust: `super::` and `self::` aren't namespaces.
        assertSpread("""((?<!s(?:uper|elf))::)""", """((?<!super|self)::)""")
        // JavaScript and TypeScript: what follows `import`.
        assertSpread(
            """(?<=(?:^|[^$._[:alnum:]])import)(?!\s*["'])""",
            """(?<=^import|[^$._[:alnum:]]import)(?!\s*["'])""",
        )
        // Swift: two groups, four alternatives.
        assertSpread(
            """(?<=(?:^|\W)(?:Process\.|CommandLine\.))(arguments|argc)""",
            """(?<=^Process\.|^CommandLine\.|\WProcess\.|\WCommandLine\.)(arguments|argc)""",
        )
    }

    @Test
    fun `the shell grammar's statements are spread`() {
        val pattern = """(?!^[\t ]*+$)(?:(?<=(?:^until| until|\tuntil|^do| do|\tdo) )|(?<=^|[!\&(;`{|]))[\t ]*+"""
        assertSpread(
            pattern,
            """(?!^[\t ]*+$)(?:(?<=^until | until |\tuntil |^do | do |\tdo )|(?<=^|[!\&(;`{|]))[\t ]*+""",
        )
    }

    @Test
    fun `optional pieces and small repeats are spread into each count`() {
        assertSpread("""(?<=(?:\W|^)for ?\()x""", """(?<=\Wfor\(|\Wfor \(|^for\(|^for \()x""")
        assertSpread("""(?<=\.\*?|->\*??)x""", """(?<=\.|\.\*|->|->\*)x""")
        assertSpread("""(?<=\\(?:[@\w]|[@\w]{2}))\s""", """(?<=\\[@\w]|\\[@\w][@\w])\s""")
        assertSpread("""(?<=a{1,3}b)c""", """(?<=ab|aab|aaab)c""")
    }

    @Test
    fun `a capturing group is spread without capturing, and an empty group keeps the later groups' numbers`() {
        // CSS's selectors: no rule uses what the look-behind's group captures.
        val pattern = """(?<=^|(^|[^:])\s|[;{}])(\w+)"""
        val rewritten = """()(?<=^|^\s|[^:]\s|[;{}])(\w+)"""
        assertSpread(pattern, rewritten)

        val text = "a b".toByteArray()
        val matcher = joni(rewritten)!!.matcher(text)
        assertEquals(2, matcher.search(1, text.size, Option.DEFAULT))
        val region = matcher.eagerRegion
        assertEquals(3, region.numRegs)
        assertEquals(2 to 2, region.getBeg(1) to region.getEnd(1))
        assertEquals(2 to 3, region.getBeg(2) to region.getEnd(2))
    }

    // ── What stays as it is ────────────────────────────────────────────

    @Test
    fun `look-behinds that Joni takes stay exactly as written`() {
        assertNull(rewrite("""(?<=a|bc)(?<!(?:x))(?<=(y))z"""))
        // In a pattern that is rewritten for another look-behind, too.
        assertEquals("""(?<=(?:x))(?<=ab|b)c""", rewrite("""(?<=(?:x))(?<=(?:a|)b)c"""))
    }

    @Test
    fun `look-behinds that spreading would change, or can't spread, are left alone`() {
        // Unbounded repeats: C#'s `await` and Julia's `as`.
        assertNull(rewrite("""(?<!\.\s*)\b(await)\b"""))
        assertNull(rewrite("""(?<=\S\s+)\b(as)\b"""))
        // A back-reference, a named group, an option for the rest of the group, a capture inside a look-ahead.
        assertNull(rewrite("""(a)(?<=\1(?:b|cd))e"""))
        assertNull(rewrite("""(?<=(?<n>a|bc))d"""))
        assertNull(rewrite("""(?<=(?i)(?:a|bc))d"""))
        assertNull(rewrite("""(?<=(?:a|bc)(?=(d)))e"""))
        // An escape whose end depends on what follows it, once spread.
        assertNull(rewrite("""(?<=(?:\x1|b)2)c"""))
        // A repeat too large to spread.
        assertNull(rewrite("""(?<=a{1,20}b)c"""))
    }

    @Test
    fun `patterns in free-spacing mode and patterns this reader doesn't know are left alone`() {
        assertNull(rewrite("""(?x) (?<= (?:a|bc) ) d # comment"""))
        assertNull(rewrite("""(a)?(?(1)b|c)(?<=(?:a|bc))d"""))
        assertNull(rewrite("""(?<=(?:a|bc)"""))
        assertNull(rewrite("""[a(?<=(?:a|bc))d"""))
    }

    @Test
    fun `classes, escapes and groups are read whole`() {
        // A `]` that opens a class, a nested class, a POSIX bracket, a `)` in a class and in an escape.
        assertSpread(
            """(?<=[]"(][^)\x00-\x7F[:alpha:]](?:\)|\\\(|a)\x{29})x""",
            """(?<=[]"(][^)\x00-\x7F[:alpha:]]\)\x{29}|[]"(][^)\x00-\x7F[:alpha:]]\\\(\x{29}|[]"(][^)\x00-""" +
                """\x7F[:alpha:]]a\x{29})x""",
        )
        // Possessive and lazy quantifiers outside look-behinds, a comment group and a group with options.
        assertSpread("""a*+b++(?#c)(?i:d)(?<=(?:e|fg)h)i??""", """a*+b++(?#c)(?i:d)(?<=eh|fgh)i??""")
    }

    // ── The same matches ──────────────────────────────────────────────

    /**
     * Java's regular expressions take bounded look-behinds with groups of alternatives, as Oniguruma 6.9 does: the
     * rewritten pattern on Joni must find the same matches as the original on Java, from every position of random text.
     */
    @Test
    fun `rewritten patterns match what the originals match`() {
        val patterns = listOf(
            """(?<=(?:^|[^\\])\n)x""",
            """(?<=(?:\W|^)for ?\()y""",
            """(?<!(?:\W|^)(?:struct|class))\bz""",
            """(?<=\.\*?|->\*??)w""",
            """(?<!s(?:uper|elf))::""",
            """(?<=^|(^|[^:])\s|[;{}])a""",
            """(?<=a{1,3}b)c""",
            """(?<=(?:ab|c)(?:ab|c))d""",
            """(?<!(?:x|yz)?q)[a-z]""",
        )
        val alphabet = "abcdqxyz ;{}:\\\n.*->(forstuctlasepw"
        val random = Random(20261023)
        for (pattern in patterns) {
            val rewritten = rewrite(pattern) ?: error("Not rewritten: $pattern")
            val joni = joni(rewritten)!!
            val java = Pattern.compile(pattern, Pattern.MULTILINE)
            repeat(2_000) {
                val text = String(CharArray(random.nextInt(0, 14)) { alphabet[random.nextInt(alphabet.length)] })
                val bytes = text.toByteArray()
                for (start in 0..text.length) {
                    val matcher = joni.matcher(bytes)
                    val found = matcher.search(start, bytes.size, Option.DEFAULT)
                    val joniMatch = if (found == Matcher.FAILED) null else found to matcher.end
                    val javaMatcher = java.matcher(text)
                    val javaMatch = if (javaMatcher.find(start)) javaMatcher.start() to javaMatcher.end() else null
                    assertEquals(javaMatch, joniMatch, "$pattern on ${text.replace("\n", "\\n")} from $start")
                }
            }
        }
    }

    // ── In the scanner ────────────────────────────────────────────────

    @Test
    fun `the scanner compiles a refused pattern through the rewrite, and counts no sentinel`() {
        val lib = JoniOnigLib()
        val scanner = lib.createOnigScanner(listOf("""(?<=(?:^|\W)for ?\()\w+"""))
        val match = scanner.findNextMatchSync(lib.createOnigString("x = for (item in list)"), 0)

        assertNotNull(match)
        assertEquals(9 to 13, match!!.captureIndices[0].start to match.captureIndices[0].end)
        assertEquals(0, lib.sentinelPatternCount)
    }

    @Test
    fun `a pattern that the rewrite can't fix still never matches, and is counted`() {
        val lib = JoniOnigLib()
        val scanner = lib.createOnigScanner(listOf("""(?<!\.\s*)\b(await)\b"""))

        assertNull(scanner.findNextMatchSync(lib.createOnigString("await x"), 0))
        assertEquals(1, lib.sentinelPatternCount)
        assertTrue(rewrite("""(?<!\.\s*)\b(await)\b""") == null)
    }
}
