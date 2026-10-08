// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SHA = "2d91ab58cf6bfbcc84d29c7645a6f9ebaf625bf4"

/** Joins fields the way `-z` output does: each one NUL-terminated. */
private fun nulTerminated(vararg fields: String) = fields.joinToString("") { "$it\u0000" }

/** Fixtures copied from git 2.54's output. */
class WorktreeParsersTest {
    @Test
    fun `parses every kind of worktree`() {
        val output = nulTerminated(
            "worktree /lab/main", "HEAD $SHA", "branch refs/heads/main", "",
            "worktree /lab/wt with space", "HEAD $SHA", "branch refs/heads/spaced", "",
            "worktree /lab/wt-detached", "HEAD $SHA", "detached", "",
            "worktree /lab/wt-gone", "HEAD $SHA", "branch refs/heads/gone-branch",
            "prunable gitdir file points to non-existent location", "",
            "worktree /lab/wt-locked", "HEAD $SHA", "branch refs/heads/locked-branch", "locked agent running", "",
            "worktree /lab/wt-locked-noreason", "HEAD $SHA", "branch refs/heads/lock2", "locked", "",
        )

        val worktrees = parseWorktreeList(output)

        assertEquals(
            listOf(
                worktree("/lab/main", branch = "refs/heads/main", isMain = true),
                worktree("/lab/wt with space", branch = "refs/heads/spaced"),
                worktree("/lab/wt-detached", isDetached = true),
                worktree(
                    "/lab/wt-gone",
                    branch = "refs/heads/gone-branch",
                    prunable = "gitdir file points to non-existent location",
                ),
                worktree("/lab/wt-locked", branch = "refs/heads/locked-branch", locked = "agent running"),
                worktree("/lab/wt-locked-noreason", branch = "refs/heads/lock2", locked = ""),
            ),
            worktrees,
        )
    }

    @Test
    fun `parses a bare main repository`() {
        val output = nulTerminated(
            "worktree /lab/bare.git", "bare", "",
            "worktree /lab/wt-from-bare", "HEAD $SHA", "branch refs/heads/from-bare", "",
        )

        val worktrees = parseWorktreeList(output)

        assertEquals(
            Worktree(
                path = "/lab/bare.git",
                headSha = null,
                branch = null,
                isMain = true,
                isCurrent = false,
                isDetached = false,
                isBare = true,
                locked = null,
                prunable = null,
            ),
            worktrees.first(),
        )
        assertEquals(worktree("/lab/wt-from-bare", branch = "refs/heads/from-bare"), worktrees[1])
    }

    @Test
    fun `ignores attributes it doesn't know and output without the last terminator`() {
        val output = "worktree /lab/main\u0000HEAD $SHA\u0000branch refs/heads/main\u0000future-attribute value"

        assertEquals(
            listOf(worktree("/lab/main", branch = "refs/heads/main", isMain = true)),
            parseWorktreeList(output),
        )
    }

    @Test
    fun `parses empty output as no worktrees`() {
        assertEquals(emptyList<Worktree>(), parseWorktreeList(""))
    }

    @Test
    fun `counts staged, unstaged, untracked and upstream changes`() {
        val output = nulTerminated(
            "# branch.oid 332fa3e6b447e879349f51dbfe494db2423cd67f",
            "# branch.head feature",
            "# branch.upstream origin/main",
            "# branch.ab +1 -2",
            "1 D. N... 100644 000000 000000 78981922613b2afb6025042ff6bd878ac1994e85 " +
                "0000000000000000000000000000000000000000 a.txt",
            "1 MM N... 100644 100644 100644 61780798228d17af2d34fce4cfbdf35556832472 " +
                "49f33a8c6e8bb31f5d7c68f9c298cac55ec7cd85 b.txt",
            "1 .M N... 100644 100644 100644 61780798228d17af2d34fce4cfbdf35556832472 " +
                "61780798228d17af2d34fce4cfbdf35556832472 c.txt",
            "1 A. N... 000000 100644 100644 0000000000000000000000000000000000000000 " +
                "19d9cc8584ac2c7dcf57d2680375e80f099dc481 s.txt",
            "? untracked.txt",
            "? untracked-folder/",
        )

        val status = parseWorktreeStatus(output)

        assertEquals(WorktreeStatus(3, 2, 2, 0, "origin/main", AheadBehind(ahead = 1, behind = 2)), status)
        assertTrue(status.isDirty)
    }

    @Test
    fun `skips the original path of a renamed entry`() {
        val output = nulTerminated(
            "# branch.oid 332fa3e6b447e879349f51dbfe494db2423cd67f",
            "# branch.head feature",
            "2 RM N... 100644 100644 100644 78981922613b2afb6025042ff6bd878ac1994e85 " +
                "78981922613b2afb6025042ff6bd878ac1994e85 R100 renamed.txt",
            // The original path would otherwise count as an untracked file
            "? original.txt",
        )

        assertEquals(WorktreeStatus(1, 1, 0, 0, null, null), parseWorktreeStatus(output))
    }

    @Test
    fun `counts conflicts`() {
        val output = nulTerminated(
            "# branch.oid a8e6117b14095fe5c97c55ce8e31d46b750b4c06",
            "# branch.head tmp",
            "u UU N... 100644 100644 100644 100644 78981922613b2afb6025042ff6bd878ac1994e85 " +
                "975fbec8256d3e8a3797e7a3611380f27c49f4ac 587be6b4c3f93f93c489c0111bba5596147a26cb a.txt",
        )

        assertEquals(WorktreeStatus(0, 0, 0, 1, null, null), parseWorktreeStatus(output))
    }

    @Test
    fun `reads a clean detached worktree and an upstream that no longer exists`() {
        val detached = nulTerminated("# branch.oid $SHA", "# branch.head (detached)")
        val upstreamGone = nulTerminated("# branch.oid $SHA", "# branch.head feature", "# branch.upstream origin/gone")

        val detachedStatus = parseWorktreeStatus(detached)

        assertEquals(WorktreeStatus(0, 0, 0, 0, null, null), detachedStatus)
        assertFalse(detachedStatus.isDirty)
        assertEquals(WorktreeStatus(0, 0, 0, 0, "origin/gone", null), parseWorktreeStatus(upstreamGone))
    }

    @Test
    fun `parses rev-list left-right counts as behind then ahead`() {
        assertEquals(AheadBehind(ahead = 1, behind = 0), parseLeftRightCount("0\t1\n"))
        assertEquals(AheadBehind(ahead = 3, behind = 12), parseLeftRightCount("12\t3\n"))
        assertNull(parseLeftRightCount("fatal: something\n"))
    }

    private fun worktree(
        path: String,
        branch: String? = null,
        isMain: Boolean = false,
        isDetached: Boolean = false,
        locked: String? = null,
        prunable: String? = null,
    ) = Worktree(
        path = path,
        headSha = SHA,
        branch = branch,
        isMain = isMain,
        isCurrent = false,
        isDetached = isDetached,
        isBare = false,
        locked = locked,
        prunable = prunable,
    )
}
