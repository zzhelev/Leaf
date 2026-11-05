// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiddleCutTextTest {
    /** Every surrogate in it is half of a pair. */
    private fun String.isWellFormed() = indices.all { i ->
        when {
            this[i].isHighSurrogate() -> i + 1 < length && this[i + 1].isLowSurrogate()
            this[i].isLowSurrogate() -> i > 0 && this[i - 1].isHighSurrogate()
            else -> true
        }
    }

    /** Fits when it has at most [characters] characters, the ellipsis included. */
    private fun atMost(characters: Int): (String) -> Boolean = { it.length <= characters }

    @Test
    fun `a text that fits stays as it is`() {
        assertEquals("to main", middleCut("to main", atMost(7)))
    }

    @Test
    fun `a text that doesn't fit keeps as much of both ends as fits, the extra character at its start`() {
        val text = "to claude/fix-login-redirect-on-safari"

        assertEquals("to claude/fix-l…rect-on-safari", middleCut(text, atMost(30)))
        assertEquals("to cl…afari", middleCut(text, atMost(11)))
    }

    @Test
    fun `what is left always fits, down to the ellipsis alone`() {
        val text = "feature/JIRA-4821-move-the-payment-retry-queue-to-the-new-scheduler"

        for (room in 1..text.length) {
            val cut = middleCut(text, atMost(room))

            assertTrue(cut.length <= room, "$room: $cut")
            assertEquals(room, cut.length, "$room: as much as fits")
        }
        assertEquals("…", middleCut(text) { it == "…" })
    }

    @Test
    fun `a character outside the BMP is never split`() {
        val text = "to 🌿🌿🌿🌿🌿🌿🌿🌿🌿🌿"

        for (kept in 0 until text.length) {
            val cut = keepingEnds(text, kept)

            assertTrue(cut.isWellFormed(), "$kept: $cut")
        }
    }
}
