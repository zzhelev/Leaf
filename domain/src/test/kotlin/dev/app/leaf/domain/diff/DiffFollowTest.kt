// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.diff

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Which file of the next commit an open diff moves to. */
class DiffFollowTest {
    private class Entry(type: ChangeType, oldPath: String, newPath: String) : DiffEntry() {
        init {
            this.changeType = type
            this.oldPath = oldPath
            this.newPath = newPath
        }
    }

    private fun modified(path: String) = Entry(ChangeType.MODIFY, path, path)
    private fun added(path: String) = Entry(ChangeType.ADD, DiffEntry.DEV_NULL, path)
    private fun deleted(path: String) = Entry(ChangeType.DELETE, path, DiffEntry.DEV_NULL)
    private fun renamed(from: String, to: String) = Entry(ChangeType.RENAME, from, to)
    private fun copied(from: String, to: String) = Entry(ChangeType.COPY, from, to)

    @Test
    fun `a commit that changes the file shows it`() {
        val wanted = modified("src/Log.kt")
        val changes = listOf(modified("README.md"), wanted, modified("src/Diff.kt"))

        assertSame(wanted, followedEntry("src/Log.kt", changes))
    }

    @Test
    fun `a commit that doesn't touch the file shows none`() {
        assertNull(followedEntry("src/Log.kt", listOf(modified("README.md"), modified("src/Diff.kt"))))
        assertNull(followedEntry("src/Log.kt", emptyList()))
    }

    @Test
    fun `a similar path is not the file`() {
        val changes = listOf(modified("src/Log.kt.orig"), modified("other/src/Log.kt"), modified("src/log.kt"), modified("src/Log"))

        assertNull(followedEntry("src/Log.kt", changes))
    }

    @Test
    fun `a commit that adds or deletes the file shows that`() {
        val addition = added("src/Log.kt")
        val deletion = deleted("src/Log.kt")

        assertSame(addition, followedEntry("src/Log.kt", listOf(addition)))
        assertSame(deletion, followedEntry("src/Log.kt", listOf(deletion)))
    }

    @Test
    fun `a commit that moved the file shows the move`() {
        val move = renamed("src/Old.kt", "src/New.kt")

        assertSame(move, followedEntry("src/Old.kt", listOf(modified("README.md"), move)))
    }

    @Test
    fun `an entry at the path wins over a move from it`() {
        val atPath = modified("src/Old.kt")
        val move = renamed("src/Old.kt", "src/New.kt")

        assertSame(atPath, followedEntry("src/Old.kt", listOf(move, atPath)))
    }

    @Test
    fun `a file renamed to the opened path is the file at that path`() {
        val move = renamed("src/Old.kt", "src/New.kt")

        assertSame(move, followedEntry("src/New.kt", listOf(move)))
    }

    @Test
    fun `a copy leaves its source where it was, so it isn't a move of it`() {
        val copy = copied("src/Source.kt", "src/Copy.kt")

        assertNull(followedEntry("src/Source.kt", listOf(copy)))
        assertSame(copy, followedEntry("src/Copy.kt", listOf(copy)))
    }

    @Test
    fun `the first of two entries for one path is the one shown`() {
        val first = modified("src/Log.kt")
        val second = modified("src/Log.kt")

        assertSame(first, followedEntry("src/Log.kt", listOf(first, second)))
    }
}
