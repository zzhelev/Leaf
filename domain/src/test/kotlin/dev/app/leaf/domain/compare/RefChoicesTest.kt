// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.compare

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The list that picks a comparison's side (`docs/fork/compare.md`, 7.1). */
class RefChoicesTest {
    private val branches = listOf(
        Branch("m1", "refs/heads/main", isLocal = true),
        Branch("f1", "refs/heads/feat/clone-picker", isLocal = true),
        Branch("r2", "refs/heads/release/v1.10", isLocal = true),
        Branch("r1", "refs/heads/release/v1.9", isLocal = true),
        // A detached HEAD is in JGit's branch list
        Branch("h1", "HEAD", isLocal = true),
    )
    private val remoteBranches = listOf(
        Branch("o1", "refs/remotes/origin/main", isLocal = false),
        Branch("o2", "refs/remotes/origin/release/v1.3", isLocal = false),
    )
    private val tags = listOf(
        Tag("t1", "a1", "refs/tags/v1.2.0"),
        Tag("t2", "a2", "refs/tags/v1.10.0"),
        Tag("t3", "a3", "refs/tags/v1.3.0"),
    )

    @Test
    fun `nothing typed lists every ref by group, branches, tags newest version first, then remote branches`() {
        val choices = refChoicesFor("", branches, remoteBranches, tags, resolved = null)

        assertEquals(listOf(RefKind.BRANCH, RefKind.TAG, RefKind.REMOTE_BRANCH), choices.groups.map { it.kind })
        assertEquals(
            listOf(
                "feat/clone-picker", "main", "release/v1.9", "release/v1.10",
                "v1.10.0", "v1.3.0", "v1.2.0",
                "origin/main", "origin/release/v1.3",
            ),
            choices.rows.map { it.name },
        )
        assertEquals(9, choices.total)
        assertTrue(choices.rows.all { it.matchLength == 0 })
    }

    @Test
    fun `a branch is a branch side that follows it, a tag its commit named by the tag`() {
        val rows = refChoicesFor("", branches, remoteBranches, tags, resolved = null).rows

        assertEquals(CompareSide.Branch("refs/heads/main", "m1"), rows.first { it.name == "main" }.side)
        assertEquals(CompareSide.Branch("refs/remotes/origin/main", "o1"), rows.first { it.name == "origin/main" }.side)
        assertEquals(CompareSide.Commit("t3", tag = "refs/tags/v1.3.0"), rows.first { it.name == "v1.3.0" }.side)
    }

    @Test
    fun `what is typed filters anywhere in the name, without regard to case, and is marked`() {
        val choices = refChoicesFor("  V1.", branches, remoteBranches, tags, resolved = null)

        assertEquals(
            listOf("release/v1.9", "release/v1.10", "v1.10.0", "v1.3.0", "v1.2.0", "origin/release/v1.3"),
            choices.rows.map { it.name },
        )
        val release = choices.rows.first { it.name == "release/v1.9" }
        assertEquals(8, release.matchStart)
        assertEquals(3, release.matchLength)
        assertEquals(9, choices.total)
    }

    @Test
    fun `a typed revision that git resolves is a commit row on top, unless it's a listed name`() {
        val commit = Commit("a1e4f9a2".padEnd(40, '0'), "Docs: notes\n\nBody", someone, someone, 0, emptyList())

        val hash = refChoicesFor("a1e4f9", branches, remoteBranches, tags, resolved = commit)
        assertEquals(RefKind.COMMIT, hash.groups.first().kind)
        assertEquals(RefChoice(CompareSide.Commit(commit.hash), "a1e4f9a", RefKind.COMMIT, 0, 0, detail = "Docs: notes"), hash.rows.single())

        // main resolves too, but main's row is the branch
        val branch = refChoicesFor("main", branches, remoteBranches, tags, resolved = commit)
        assertFalse(branch.groups.any { it.kind == RefKind.COMMIT })
        assertEquals(listOf("main", "origin/main"), branch.rows.map { it.name })
    }

    @Test
    fun `what could be a revision is one line of text without spaces`() {
        assertTrue(looksLikeRevision("HEAD~2"))
        assertTrue(looksLikeRevision(" a1e4f9 "))
        assertFalse(looksLikeRevision(""))
        assertFalse(looksLikeRevision("   "))
        assertFalse(looksLikeRevision("fix the parser"))
    }

    private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")
}
