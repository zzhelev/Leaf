// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.sorting.FileChangeKind
import dev.app.leaf.domain.sorting.RenamedFrom
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A commit's file list (and a worktree review's, which shares it) for files that were renamed. */
class CommitChangesRenamesTest {
    private val rename = entry(ChangeType.RENAME, "src/parse/Lexer.kt", "src/parse/Parser.kt", score = 92)
    private val modify = entry(ChangeType.MODIFY, "src/Theme.kt", "src/Theme.kt")
    private val add = entry(ChangeType.ADD, DiffEntry.DEV_NULL, "src/New.kt")
    private val delete = entry(ChangeType.DELETE, "src/Gone.kt", DiffEntry.DEV_NULL)

    @Test
    fun `a renamed file is listed at its new path, with where it came from`() {
        val items = listOf(rename, modify, add, delete).toFileItems()

        assertEquals(
            listOf(
                Triple("src/parse/Parser.kt", FileChangeKind.Renamed, RenamedFrom("src/parse/Lexer.kt", 92)),
                Triple("src/Theme.kt", FileChangeKind.Modified, null),
                Triple("src/New.kt", FileChangeKind.Added, null),
                Triple("src/Gone.kt", FileChangeKind.Deleted, null),
            ),
            items.map { Triple(it.path, it.kind, it.renamedFrom) },
        )
    }

    @Test
    fun `a search finds a renamed file by the name that it had too`() {
        assertTrue(rename.matchesSearch("parser"))
        assertTrue(rename.matchesSearch("lexer"))
        assertTrue(rename.matchesSearch("PARSE/LEX"))
        assertFalse(rename.matchesSearch("theme"))
        assertTrue(modify.matchesSearch("theme"))
        assertFalse(modify.matchesSearch("lexer"))
    }

    private fun entry(type: ChangeType, old: String, new: String, score: Int = 0): DiffEntry = object : DiffEntry() {
        init {
            changeType = type
            oldPath = old
            newPath = new
            this.score = score
        }
    }
}
