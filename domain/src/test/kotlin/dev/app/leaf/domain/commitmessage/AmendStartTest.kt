// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val LAST = "Add the parser cache\n\nKeeps parsed files by content hash.\n"
private val MAIN = AmendHead("refs/heads/main", "a".repeat(40))

class AmendStartTest {
    @Test
    fun `an empty field gets the last commit's message`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertEquals(LAST, start.loadedMessage)
        assertEquals(MAIN, start.head)
    }

    @Test
    fun `a field with text keeps it, and nothing is loaded`() {
        assertNull(amendStart(fieldText = "My draft", lastCommitMessage = LAST, head = MAIN).loadedMessage)
        assertNull(amendStart(fieldText = " ", lastCommitMessage = LAST, head = MAIN).loadedMessage)
    }

    @Test
    fun `nothing is loaded without a last commit or with an empty message`() {
        assertNull(amendStart(fieldText = "", lastCommitMessage = null, head = null).loadedMessage)
        assertNull(amendStart(fieldText = "", lastCommitMessage = "", head = MAIN).loadedMessage)
    }

    @Test
    fun `ending Amend empties the field that still has the loaded message`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertEquals("", start.fieldAfterEnd(LAST))
    }

    @Test
    fun `ending Amend keeps an edited message`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertNull(start.fieldAfterEnd("Add the parser cache, with a limit\n"))
        assertNull(start.fieldAfterEnd(LAST.trimEnd()))
        assertNull(start.fieldAfterEnd(""))
    }

    @Test
    fun `ending Amend keeps a message that the field had before`() {
        val start = amendStart(fieldText = "My draft", lastCommitMessage = "My draft", head = MAIN)

        assertNull(start.fieldAfterEnd("My draft"))
        assertEquals("My draft", start.draftFor("My draft"))
    }

    @Test
    fun `the draft while amending is the field without the loaded message`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertEquals("", start.draftFor(LAST))
        assertEquals("Edited", start.draftFor("Edited"))
    }

    @Test
    fun `another branch at the same commit ends Amend`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertTrue(start.isEndedBy(AmendHead("refs/heads/feature", MAIN.commit)))
    }

    @Test
    fun `another commit on the same branch ends Amend`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertTrue(start.isEndedBy(MAIN.copy(commit = "b".repeat(40))))
    }

    @Test
    fun `a branch without commits ends Amend`() {
        assertTrue(amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN).isEndedBy(null))
    }

    @Test
    fun `the same branch at the same commit keeps Amend`() {
        assertFalse(amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN).isEndedBy(MAIN.copy()))
    }

    @Test
    fun `Amend turned on before the branch was known takes the first one known, and ends with the next`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = null)

        assertFalse(start.isEndedBy(MAIN))
        assertFalse(start.isEndedBy(null))

        val known = start.withHeadKnown(MAIN)

        assertEquals(MAIN, known.head)
        assertFalse(known.isEndedBy(MAIN))
        assertTrue(known.isEndedBy(MAIN.copy(branch = "refs/heads/feature")))
    }

    @Test
    fun `a known branch stays the one Amend belongs to`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = MAIN)

        assertSame(start, start.withHeadKnown(MAIN.copy(branch = "refs/heads/feature")))
    }

    @Test
    fun `an unknown branch stays unknown while none is loaded`() {
        val start = amendStart(fieldText = "", lastCommitMessage = LAST, head = null)

        assertSame(start, start.withHeadKnown(null))
    }
}
