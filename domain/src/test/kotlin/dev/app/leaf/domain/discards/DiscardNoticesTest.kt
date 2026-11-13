// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.discards

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private fun record(id: String, gitDir: String = "/repo/.git") =
    DiscardRecord(id, gitDir, 1L, DiscardKind.CHANGES, listOf("a.txt", "b.txt"), emptyList())

/** The tab's latest discard, which the strip shows (fork-only, upstream issue 39). */
class DiscardNoticesTest {
    @Test
    fun `a discard replaces the notice, and Undo of it marks it`() {
        val notices = DiscardNotices()
        val first = record("1")
        val second = record("2")

        notices.recorded(first)
        notices.recorded(second)

        assertEquals(DiscardNotice(second), notices.latest.value)

        val restore = DiscardRestore(second, listOf("a.txt"), listOf("b.txt"))
        notices.restored(restore)

        assertEquals(DiscardNotice(second, restore), notices.latest.value)
    }

    @Test
    fun `Undo of an older discard, or of one in another worktree, leaves the notice as it is`() {
        val notices = DiscardNotices()
        val shown = record("2")

        notices.recorded(shown)
        notices.restored(DiscardRestore(record("1"), listOf("a.txt"), emptyList()))
        notices.restored(DiscardRestore(record("2", gitDir = "/repo/.git/worktrees/agent"), listOf("a.txt"), emptyList()))

        assertEquals(DiscardNotice(shown), notices.latest.value)
    }

    @Test
    fun `× dismisses it`() {
        val notices = DiscardNotices()

        notices.recorded(record("1"))
        notices.dismiss()

        assertNull(notices.latest.value)
    }

    @Test
    fun `a plan sorts the files into those that come back and those that changed since, in the record's order`() {
        val record = DiscardRecord("1", "/repo/.git", 1L, DiscardKind.ALL, listOf("c", "a", "b", "d"), emptyList())
        val plan = DiscardRestorePlan(
            record,
            mapOf(
                "a" to RestoreWay.CHANGED_SINCE,
                "b" to RestoreWay.MERGED,
                "c" to RestoreWay.AS_IT_WAS,
                "d" to RestoreWay.ALREADY_BACK,
            ),
        )

        assertEquals(listOf("c", "b", "d"), plan.restorable)
        assertEquals(listOf("a"), plan.changedSince)
    }
}
