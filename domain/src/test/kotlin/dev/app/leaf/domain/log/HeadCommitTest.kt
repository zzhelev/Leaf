// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which commit the log marks as HEAD's (fork-only, upstream issue 255). The real states are in `MarkingHeadTest`. */
class HeadCommitTest {
    @Test
    fun `a checked out branch gives its tip`() {
        assertEquals("abc123", headCommitHash(Branch(hash = "abc123", name = "refs/heads/main", isLocal = true)))
    }

    @Test
    fun `a detached HEAD, which is a branch named HEAD, gives its commit`() {
        assertEquals("def456", headCommitHash(Branch(hash = "def456", name = "HEAD", isLocal = true)))
    }

    @Test
    fun `no current branch, as for a branch with no commit, gives none`() {
        assertNull(headCommitHash(null))
    }

    @Test
    fun `a branch without a hash gives none rather than matching a row with an empty hash`() {
        assertNull(headCommitHash(Branch(hash = "", name = "refs/heads/main", isLocal = true)))
        assertNull(headCommitHash(Branch(hash = "  ", name = "refs/heads/main", isLocal = true)))
    }

    @Test
    fun `a remote branch's tip is no different, the rule only reads the hash`() {
        assertEquals("fed987", headCommitHash(Branch(hash = "fed987", name = "refs/remotes/origin/main", isLocal = false)))
    }
}
