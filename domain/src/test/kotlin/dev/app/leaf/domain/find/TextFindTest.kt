// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.find

import dev.app.leaf.domain.log.SearchStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private fun rows(vararg texts: String?) = texts.map { FindRow(listOf(it)) }

/** Find in the diff and blame (upstream issues 275 and 312): what it matches, in which order, and where it starts. */
class TextFindTest {
    @Test
    fun `matches are found row by row, left to right, case ignored`() {
        val result = findMatches(rows("val parser = Parser()", null, "", "no", "PARSER.parser()"), "parser", matchCase = false)

        assertEquals(
            listOf(
                FindMatch(0, 0, 4, 10),
                FindMatch(0, 0, 13, 19),
                FindMatch(4, 0, 0, 6),
                FindMatch(4, 0, 7, 13),
            ),
            result.matches,
        )
        assertFalse(result.isCapped)
    }

    @Test
    fun `match case keeps only the same letters`() {
        val result = findMatches(rows("val parser = Parser()", "PARSER"), "Parser", matchCase = true)

        assertEquals(listOf(FindMatch(0, 0, 13, 19)), result.matches)
    }

    @Test
    fun `a match starts after the one before ends`() {
        assertEquals(
            listOf(FindMatch(0, 0, 0, 2), FindMatch(0, 0, 2, 4)),
            findMatches(rows("aaaaa"), "aa", matchCase = false).matches,
        )
    }

    @Test
    fun `a split row's old side comes before its new side`() {
        val split = listOf(
            FindRow(listOf(null)),
            FindRow(listOf("x = 1", "x = 2")),
            FindRow(listOf(null, "x")),
        )

        assertEquals(
            listOf(FindMatch(1, 0, 0, 1), FindMatch(1, 1, 0, 1), FindMatch(2, 1, 0, 1)),
            findMatches(split, "x", matchCase = false).matches,
        )
    }

    @Test
    fun `an empty query finds nothing, nor does one longer than every text`() {
        assertEquals(FindResult.NONE, findMatches(rows("abc"), "", matchCase = false))
        assertEquals(emptyList<FindMatch>(), findMatches(rows("abc", "ab"), "abcd", matchCase = false).matches)
    }

    @Test
    fun `it stops at the limit and says that more are left`() {
        val many = rows(*Array(30) { "x x x" })

        val capped = findMatches(many, "x", matchCase = false, limit = 10)
        assertEquals(10, capped.matches.size)
        assertTrue(capped.isCapped)

        val exact = findMatches(many, "x", matchCase = false, limit = 90)
        assertEquals(90, exact.matches.size)
        assertFalse(exact.isCapped)
    }

    @Test
    fun `ignoring case matches what lower case comparison of each character matches`() {
        val random = Random(275)
        val alphabet = "aAbBcC xé"

        repeat(500) {
            val text = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.length)] })
            val query = String(CharArray(random.nextInt(1, 4)) { alphabet[random.nextInt(alphabet.length)] })

            val expected = ArrayList<Int>()
            var from = 0
            while (from <= text.length - query.length) {
                if (text.substring(from, from + query.length).lowercase() == query.lowercase()) {
                    expected.add(from)
                    from += query.length
                } else {
                    from++
                }
            }

            assertEquals(expected, findMatches(rows(text), query, matchCase = false).matches.map { it.start }, "$query in $text")
        }
    }

    @Test
    fun `the first match shown is the first at or below the list's top, else the first of all`() {
        val matches = listOf(FindMatch(2, 0, 0, 1), FindMatch(5, 0, 0, 1), FindMatch(5, 0, 3, 4), FindMatch(9, 0, 0, 1))

        assertEquals(0, firstMatchFrom(matches, 0))
        assertEquals(0, firstMatchFrom(matches, 2))
        assertEquals(1, firstMatchFrom(matches, 3))
        assertEquals(3, firstMatchFrom(matches, 9))
        assertEquals(0, firstMatchFrom(matches, 10))
        assertEquals(-1, firstMatchFrom(emptyList(), 0))
    }

    @Test
    fun `a match found again stays, or the next one after where it was takes its place`() {
        val matches = listOf(FindMatch(2, 0, 4, 5), FindMatch(5, 1, 0, 1), FindMatch(5, 1, 3, 4), FindMatch(9, 0, 0, 1))

        assertEquals(1, matchKeptFrom(FindMatch(5, 1, 0, 1), matches))
        assertEquals(2, matchKeptFrom(FindMatch(5, 1, 2, 3), matches))
        assertEquals(1, matchKeptFrom(FindMatch(5, 0, 7, 8), matches))
        assertEquals(1, matchKeptFrom(FindMatch(2, 0, 5, 6), matches))
        assertEquals(0, matchKeptFrom(FindMatch(2, 0, 4, 9), matches))
        assertEquals(0, matchKeptFrom(FindMatch(12, 0, 0, 1), matches))
        assertEquals(0, matchKeptFrom(null, matches))
        assertEquals(-1, matchKeptFrom(FindMatch(1, 0, 0, 1), emptyList()))
    }

    @Test
    fun `steps wrap at both ends`() {
        assertEquals(1, steppedMatch(0, 3, SearchStep.NEXT))
        assertEquals(0, steppedMatch(2, 3, SearchStep.NEXT))
        assertEquals(2, steppedMatch(0, 3, SearchStep.PREVIOUS))
        assertEquals(1, steppedMatch(2, 3, SearchStep.PREVIOUS))
        assertEquals(0, steppedMatch(-1, 3, SearchStep.NEXT))
        assertEquals(2, steppedMatch(-1, 3, SearchStep.PREVIOUS))
        assertEquals(0, steppedMatch(0, 1, SearchStep.NEXT))
        assertEquals(-1, steppedMatch(-1, 0, SearchStep.NEXT))
    }
}
