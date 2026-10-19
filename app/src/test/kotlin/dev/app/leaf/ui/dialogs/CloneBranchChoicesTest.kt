// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.input.key.Key
import dev.app.leaf.domain.clone.BranchChoice
import dev.app.leaf.domain.clone.BranchChoices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the arrow keys and Enter do in the list of "Choose…", and where a row is among the list's items. */
class CloneBranchChoicesTest {
    private fun choice(name: String, isTag: Boolean = false) = BranchChoice(name, isTag, false, 0, 0)

    private fun choices(branches: Int, tags: Int) = BranchChoices(
        branches = (1..branches).map { choice("b$it") },
        tags = (1..tags).map { choice("t$it", isTag = true) },
        total = branches + tags,
    )

    @Test
    fun `Down starts at the first row, moves one row and stops at the last`() {
        assertEquals(BranchChoicesKeyResult(0, used = true), branchChoicesKey(Key.DirectionDown, -1, 3))
        assertEquals(BranchChoicesKeyResult(1, used = true), branchChoicesKey(Key.DirectionDown, 0, 3))
        assertEquals(BranchChoicesKeyResult(2, used = true), branchChoicesKey(Key.DirectionDown, 1, 3))
        assertEquals(BranchChoicesKeyResult(2, used = true), branchChoicesKey(Key.DirectionDown, 2, 3))
    }

    @Test
    fun `Up moves one row and stops at the first`() {
        assertEquals(BranchChoicesKeyResult(1, used = true), branchChoicesKey(Key.DirectionUp, 2, 3))
        assertEquals(BranchChoicesKeyResult(0, used = true), branchChoicesKey(Key.DirectionUp, 1, 3))
        assertEquals(BranchChoicesKeyResult(0, used = true), branchChoicesKey(Key.DirectionUp, 0, 3))
        assertEquals(BranchChoicesKeyResult(0, used = true), branchChoicesKey(Key.DirectionUp, -1, 3))
    }

    @Test
    fun `Enter picks only a row that was moved to`() {
        assertEquals(BranchChoicesKeyResult(-1, used = false), branchChoicesKey(Key.Enter, -1, 3))
        assertEquals(BranchChoicesKeyResult(1, used = true, picks = true), branchChoicesKey(Key.Enter, 1, 3))
        assertEquals(BranchChoicesKeyResult(2, used = true, picks = true), branchChoicesKey(Key.NumPadEnter, 2, 3))
        // A highlight that the list outgrew, as when a filter left fewer rows
        assertEquals(BranchChoicesKeyResult(5, used = false), branchChoicesKey(Key.Enter, 5, 3))
    }

    @Test
    fun `no rows and other keys are left to the field`() {
        for (key in listOf(Key.DirectionDown, Key.DirectionUp, Key.Enter)) {
            assertEquals(BranchChoicesKeyResult(-1, used = false), branchChoicesKey(key, -1, 0), "$key")
        }

        for (key in listOf(Key.A, Key.Tab, Key.Backspace, Key.DirectionLeft, Key.Escape)) {
            assertEquals(BranchChoicesKeyResult(1, used = false), branchChoicesKey(key, 1, 3), "$key")
        }
    }

    @Test
    fun `a row is an item after the header of its group`() {
        // Items: header, b1, b2, header, t1
        assertEquals(1, itemIndexOf(0, choices(branches = 2, tags = 1)))
        assertEquals(2, itemIndexOf(1, choices(branches = 2, tags = 1)))
        assertEquals(4, itemIndexOf(2, choices(branches = 2, tags = 1)))
        // Only tags: header, t1, t2
        assertEquals(1, itemIndexOf(0, choices(branches = 0, tags = 2)))
        assertEquals(2, itemIndexOf(1, choices(branches = 0, tags = 2)))
        // Only branches: header, b1
        assertEquals(1, itemIndexOf(0, choices(branches = 1, tags = 0)))
    }
}
