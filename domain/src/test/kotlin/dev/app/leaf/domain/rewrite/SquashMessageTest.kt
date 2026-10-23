// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquashMessageTest {
    private fun commit(message: String) = Commit(
        hash = message.hashCode().toString().padEnd(40, '0'),
        message = message,
        committer = Identity("C", "c@example.invalid"),
        author = Identity("A", "a@example.invalid"),
        date = 0,
        parentsHashes = emptyList(),
    )

    private val cache = commit("Add parser cache\n\nKeeps the parsed files by path.\n")
    private val wip = commit("wip\n")
    private val evict = commit("Evict the oldest entries\r\n\r\nPast 500 files.\r\n")
    private val fixup = commit("fixup! Add parser cache\n")
    private val tests = commit("Test the cache\n")

    @Test
    fun `by subjects, the first message that says something leads, the others that do are listed`() {
        assertEquals(
            "Add parser cache\n\nKeeps the parsed files by path.\n\n- Evict the oldest entries\n- Test the cache",
            squashMessageStart(listOf(wip, cache, fixup, evict, tests), SquashMessageStart.SUBJECTS),
        )
    }

    @Test
    fun `by subjects, one that says something leads alone, and so does the oldest when none does`() {
        assertEquals(
            "Add parser cache\n\nKeeps the parsed files by path.",
            squashMessageStart(listOf(cache, fixup, wip), SquashMessageStart.SUBJECTS),
        )
        assertEquals("wip", squashMessageStart(listOf(wip, fixup), SquashMessageStart.SUBJECTS))
    }

    @Test
    fun `all messages, oldest first, with CRLF made LF`() {
        assertEquals(
            "Add parser cache\n\nKeeps the parsed files by path.\n\nwip\n\nEvict the oldest entries\n\nPast 500 files.",
            squashMessageStart(listOf(cache, wip, evict), SquashMessageStart.ALL),
        )
    }

    @Test
    fun `the oldest message, as a fixup keeps it`() {
        assertEquals("wip", squashMessageStart(listOf(wip, cache), SquashMessageStart.OLDEST))
    }

    @Test
    fun `subjects that say nothing`() {
        assertTrue(saysNothing("wip"))
        assertTrue(saysNothing(" WIP "))
        assertTrue(saysNothing(""))
        assertTrue(saysNothing("fixup! Add parser cache"))
        assertTrue(saysNothing("squash! Add parser cache"))
        assertTrue(saysNothing("amend! Add parser cache"))
        assertFalse(saysNothing("wip: parser cache"))
        assertFalse(saysNothing("Fix up the parser"))
        assertFalse(saysNothing("fixup!"))
    }
}
