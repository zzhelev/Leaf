// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FileHistoryCommitsTest {
    private val newest = commit("a")
    private val middle = commit("b")
    private val oldest = commit("c")
    private val commits = listOf(newest, middle, oldest)

    @Test
    fun `finds a commit by its hash`() {
        assertEquals(1, positionOf(commits, middle.copy(message = "Loaded again")))
        assertEquals(-1, positionOf(commits, commit("d")))
        assertEquals(-1, positionOf(commits, null))
    }

    @Test
    fun `a positive offset is an older commit, a negative one a newer`() {
        assertEquals(oldest, adjacentCommit(commits, middle, 1))
        assertEquals(newest, adjacentCommit(commits, middle, -1))
    }

    @Test
    fun `there's nothing past either end`() {
        assertNull(adjacentCommit(commits, newest, -1))
        assertNull(adjacentCommit(commits, oldest, 1))
    }

    @Test
    fun `starts from the newest when no commit in the list is selected`() {
        assertEquals(newest, adjacentCommit(commits, null, 1))
        assertEquals(newest, adjacentCommit(commits, commit("d"), -1))
        assertNull(adjacentCommit(emptyList(), null, 1))
    }

    private fun commit(letter: String) = Commit(
        hash = letter.repeat(40),
        message = "Commit $letter",
        committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        date = 1_760_000_000,
        parentsHashes = emptyList(),
    )
}
