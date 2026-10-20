// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.restore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What `git status --porcelain=v1` says about one path, read as what a restore would replace. */
class FileRestorePlanTest {
    @Test
    fun `a line git doesn't print is a tracked file with no changes, or nothing at all`() {
        assertEquals(CurrentFile.UNCHANGED to false, currentFileOf(null, exists = true))
        assertEquals(CurrentFile.MISSING to false, currentFileOf(null, exists = false))
    }

    @Test
    fun `changes that aren't staged lose their content, and staged ones stay in the index`() {
        assertEquals(CurrentFile.UNSTAGED_CHANGES to false, currentFileOf(" M a.txt", exists = true))
        assertEquals(CurrentFile.UNSTAGED_CHANGES to true, currentFileOf("MM a.txt", exists = true))
        assertEquals(CurrentFile.UNSTAGED_CHANGES to true, currentFileOf("AM a.txt", exists = true))
        // A file that was only marked as to be added (`git add -N`) is a change of the working file
        assertEquals(CurrentFile.UNSTAGED_CHANGES to false, currentFileOf(" A a.txt", exists = true))
        assertEquals(CurrentFile.STAGED_ONLY to true, currentFileOf("M  a.txt", exists = true))
        assertEquals(CurrentFile.STAGED_ONLY to true, currentFileOf("A  a.txt", exists = true))
    }

    @Test
    fun `a deleted file has nothing to lose, whether the deletion is staged or not`() {
        assertEquals(CurrentFile.MISSING to false, currentFileOf(" D a.txt", exists = false))
        assertEquals(CurrentFile.MISSING to true, currentFileOf("D  a.txt", exists = false))
        assertEquals(CurrentFile.MISSING to true, currentFileOf("MD a.txt", exists = false))
    }

    @Test
    fun `a file git doesn't track, or ignores, loses its content`() {
        assertEquals(CurrentFile.UNTRACKED to false, currentFileOf("?? a.txt", exists = true))
        assertEquals(CurrentFile.IGNORED to false, currentFileOf("!! a.txt", exists = true))
    }

    @Test
    fun `every code with an unmerged side, and both added or both deleted, is a conflict`() {
        for (code in listOf("UU", "AA", "DD", "AU", "UA", "DU", "UD")) {
            assertTrue(isConflictedStatus("$code a.txt"), code)
        }

        for (code in listOf(" M", "MM", "A ", "D ", "??", "!!", " D", "M ")) {
            assertFalse(isConflictedStatus("$code a.txt"), code)
        }

        assertFalse(isConflictedStatus(null))
    }
}
