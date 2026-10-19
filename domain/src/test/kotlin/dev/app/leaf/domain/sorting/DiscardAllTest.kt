// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun unstaged(path: String, type: StatusType) = StatusEntry(path, type, EntryType.UNSTAGED)

private val entries = listOf(
    unstaged("a.kt", StatusType.MODIFIED),
    unstaged("src/b.kt", StatusType.MODIFIED),
    unstaged("src/gone.kt", StatusType.REMOVED),
    unstaged("new.kt", StatusType.ADDED),
    unstaged("src/new/c.kt", StatusType.ADDED),
    unstaged("src/clash.kt", StatusType.CONFLICTING),
)

/** What "Discard all" restores, offers to delete and leaves, from the files that the Unstaged pane shows. */
class DiscardAllTest {
    @Test
    fun `restores changed and deleted files`() {
        assertEquals(
            listOf("a.kt", "src/b.kt", "src/gone.kt"),
            entries.allDiscard().restored.map { it.filePath },
        )
    }

    @Test
    fun `offers the new files for deletion, which nothing does unless the box is ticked`() {
        assertEquals(listOf("new.kt", "src/new/c.kt"), entries.allDiscard().newFiles.map { it.filePath })
    }

    @Test
    fun `leaves files with conflicts, which no list restores or deletes`() {
        val plan = entries.allDiscard()

        assertEquals(listOf("src/clash.kt"), plan.keptConflicted.map { it.filePath })
        assertFalse("src/clash.kt" in (plan.restored + plan.newFiles).map { it.filePath })
    }

    @Test
    fun `every entry lands in exactly one list`() {
        val plan = entries.allDiscard()

        val all = entries + StatusEntry("libs/core", StatusType.MODIFIED, isSubmodule = true)
        val withSubmodule = all.allDiscard()
        val listed = withSubmodule.restored + withSubmodule.newFiles + withSubmodule.keptConflicted +
                withSubmodule.keptSubmodules

        assertEquals(all.toSet(), listed.toSet())
        assertEquals(all.size, listed.size)
        assertEquals(entries.size, plan.restored.size + plan.newFiles.size + plan.keptConflicted.size)
    }

    @Test
    fun `keeps the order of the list`() {
        val shuffled = entries.reversed().allDiscard()

        assertEquals(listOf("src/gone.kt", "src/b.kt", "a.kt"), shuffled.restored.map { it.filePath })
    }

    @Test
    fun `new files alone leave nothing to restore, so only the box makes the discard do something`() {
        val plan = listOf(unstaged("new.kt", StatusType.ADDED)).allDiscard()

        assertTrue(plan.restoresNothing)
        assertTrue(plan.hasWork)
    }

    @Test
    fun `leaves a submodule that moved on, which git's checkout leaves too`() {
        val submodule = StatusEntry("libs/core", StatusType.MODIFIED, isSubmodule = true)
        val missing = StatusEntry("libs/gone", StatusType.REMOVED, isSubmodule = true)
        val plan = (entries + submodule + missing).allDiscard()

        assertEquals(listOf("a.kt", "src/b.kt", "src/gone.kt"), plan.restored.map { it.filePath })
        assertEquals(listOf("libs/core", "libs/gone"), plan.keptSubmodules.map { it.filePath })
        assertFalse(listOf(submodule, missing).any { it in plan.newFiles || it in plan.keptConflicted })
    }

    @Test
    fun `submodules alone have no work`() {
        assertFalse(listOf(StatusEntry("libs/core", StatusType.MODIFIED, isSubmodule = true)).allDiscard().hasWork)
    }

    @Test
    fun `an empty list, or conflicts alone, has no work`() {
        assertFalse(emptyList<StatusEntry>().allDiscard().hasWork)
        assertFalse(listOf(unstaged("clash.kt", StatusType.CONFLICTING)).allDiscard().hasWork)
    }

    @Test
    fun `a search narrows what is discarded to the files it shows`() {
        val shown = entries.filter { it.filePath.contains("src/") }
        val plan = shown.allDiscard()

        assertEquals(listOf("src/b.kt", "src/gone.kt"), plan.restored.map { it.filePath })
        assertEquals(listOf("src/new/c.kt"), plan.newFiles.map { it.filePath })
        assertEquals(listOf("src/clash.kt"), plan.keptConflicted.map { it.filePath })
    }
}
