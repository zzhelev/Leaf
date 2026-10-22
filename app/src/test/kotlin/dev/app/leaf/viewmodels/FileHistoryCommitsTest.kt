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

    // Two versions, compared (docs/fork/compare.md, 7.3)

    @Test
    fun `a cmd-click compares the row with the selected commit, the older first, and remembers what was selected`() {
        assertEquals(
            VersionPick.Compare(FileVersions(a = oldest, b = newest, before = newest)),
            versionPick(commits, versions = null, selected = newest, picked = oldest),
        )
        assertEquals(
            VersionPick.Compare(FileVersions(a = oldest, b = newest, before = oldest)),
            versionPick(commits, versions = null, selected = oldest, picked = newest),
        )
    }

    @Test
    fun `with nothing else selected, or on the selected row, a cmd-click selects the row`() {
        assertEquals(VersionPick.Select(middle), versionPick(commits, versions = null, selected = null, picked = middle))
        assertEquals(VersionPick.Select(middle), versionPick(commits, versions = null, selected = middle, picked = middle))
    }

    @Test
    fun `while comparing, a cmd-click on A or B drops that side, and elsewhere it replaces B`() {
        val versions = FileVersions(a = oldest, b = middle, before = middle)

        assertEquals(VersionPick.Select(middle), versionPick(commits, versions, selected = middle, picked = oldest))
        assertEquals(VersionPick.Select(oldest), versionPick(commits, versions, selected = middle, picked = middle))
        assertEquals(
            VersionPick.Compare(FileVersions(a = oldest, b = newest, before = middle)),
            versionPick(commits, versions, selected = middle, picked = newest),
        )

        // A row older than A takes A's place: the older is A
        val newer = FileVersions(a = middle, b = newest, before = newest)
        assertEquals(
            VersionPick.Compare(FileVersions(a = oldest, b = middle, before = newest)),
            versionPick(commits, newer, selected = newest, picked = oldest),
        )
    }

    @Test
    fun `two versions are as many commits apart as their rows`() {
        assertEquals(2, FileVersions(oldest, newest, newest).distanceIn(commits))
        assertEquals(2, FileVersions(newest, oldest, newest).distanceIn(commits))
        assertEquals(1, FileVersions(middle, newest, newest).distanceIn(commits))
    }
}
