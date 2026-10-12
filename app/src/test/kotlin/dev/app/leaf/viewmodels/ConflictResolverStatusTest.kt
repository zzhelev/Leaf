// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the resolver does when the status changes: which file it shows, or whether it's done. */
class ConflictResolverStatusTest {
    @Test
    fun `without a file shown, it opens the first conflicted one`() {
        assertEquals(AfterStatus.Load("a.kt"), afterStatus(null, listOf("a.kt", "b.kt"), false, false))
        assertEquals(AfterStatus.Stay, afterStatus(null, emptyList(), false, false))
    }

    @Test
    fun `a file that is still conflicted stays, unless it's back from the merge tool`() {
        assertEquals(AfterStatus.Stay, afterStatus("b.kt", listOf("a.kt", "b.kt"), false, false))
        assertEquals(AfterStatus.Load("b.kt"), afterStatus("b.kt", listOf("a.kt", "b.kt"), false, true))
    }

    @Test
    fun `once the file has no conflicts, the first conflicted file opens`() {
        assertEquals(AfterStatus.Load("a.kt"), afterStatus("b.kt", listOf("a.kt", "c.kt"), true, false))
        // Resolved by the merge tool
        assertEquals(AfterStatus.Load("a.kt"), afterStatus("b.kt", listOf("a.kt"), false, true))
    }

    @Test
    fun `when no file is left, it's done if something was resolved here, and closes otherwise`() {
        assertEquals(AfterStatus.Done, afterStatus("b.kt", emptyList(), true, false))
        // Aborted, or resolved elsewhere
        assertEquals(AfterStatus.Close, afterStatus("b.kt", emptyList(), false, false))
    }
}
