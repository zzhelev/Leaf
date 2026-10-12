// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val HASH = "3f6f43f2"

private fun local(name: String) = Branch(HASH, "refs/heads/$name", isLocal = true)

private fun remote(name: String) = Branch(HASH, "refs/remotes/$name", isLocal = false)

private fun tag(name: String) = Tag(HASH, "t-$name", "refs/tags/$name")

/** The chips as short labels: a local branch with the remotes that share its chip in brackets. */
private fun List<LogRef>.labels(): List<String> = map { ref ->
    when (ref) {
        is LogRef.LocalBranch -> ref.branch.simpleName +
                if (ref.remotes.isEmpty()) "" else ref.remotes.joinToString(",", " [", "]") { it.simpleNameWithRemote }

        is LogRef.RemoteBranch -> ref.branch.simpleNameWithRemote
        is LogRef.TagRef -> "tag " + ref.tag.simpleName
    }
}

class LogRefsTest {
    /** What an agent run leaves: every worktree's branch starts on main, which is also tagged and pushed. */
    private val agentRun = listOf(
        local("claude/eager-hopper"),
        local("claude/quirky-bose"),
        local("claude/tender-wu"),
        local("main"),
        remote("origin/main"),
    )

    private val usedByWorktrees = setOf("refs/heads/claude/eager-hopper", "refs/heads/claude/tender-wu")

    @Test
    fun `the current branch comes first, then branches of other worktrees, other local ones, remote ones and tags`() {
        val refs = logRefs(
            branches = agentRun + remote("origin/claude/eager-hopper-old"),
            tags = listOf(tag("leaf-1.3.0")),
            currentBranch = local("main"),
            isUsedByOtherWorktree = { it.name in usedByWorktrees },
            mergeRemotes = false,
        )

        assertEquals(
            listOf(
                "main",
                "claude/eager-hopper",
                "claude/tender-wu",
                "claude/quirky-bose",
                "origin/claude/eager-hopper-old",
                "origin/main",
                "tag leaf-1.3.0",
            ),
            refs.labels(),
        )
    }

    @Test
    fun `a remote branch shares the chip of the local branch of the same name`() {
        val refs = logRefs(
            branches = agentRun + remote("upstream/main") + remote("origin/release"),
            tags = listOf(tag("leaf-1.3.0")),
            currentBranch = local("main"),
            isUsedByOtherWorktree = { it.name in usedByWorktrees },
            mergeRemotes = true,
        )

        assertEquals(
            listOf(
                "main [origin/main,upstream/main]",
                "claude/eager-hopper",
                "claude/tender-wu",
                "claude/quirky-bose",
                "origin/release",
                "tag leaf-1.3.0",
            ),
            refs.labels(),
        )
    }

    @Test
    fun `a remote branch keeps its own chip when no local branch of its name is on the commit`() {
        val refs = logRefs(
            branches = listOf(local("feature"), remote("origin/feature-2"), remote("origin/main")),
            tags = emptyList(),
            currentBranch = local("main"),
            isUsedByOtherWorktree = { false },
            mergeRemotes = true,
        )

        assertEquals(listOf("feature", "origin/feature-2", "origin/main"), refs.labels())
    }

    @Test
    fun `names are in natural order within a group`() {
        val refs = logRefs(
            branches = listOf(local("release-10"), local("release-9"), local("Release-1")),
            tags = listOf(tag("v1.10.0"), tag("v1.9.0")),
            currentBranch = null,
            isUsedByOtherWorktree = { false },
            mergeRemotes = true,
        )

        assertEquals(listOf("Release-1", "release-9", "release-10", "tag v1.9.0", "tag v1.10.0"), refs.labels())
    }

    @Test
    fun `a detached HEAD comes first`() {
        val head = Branch(HASH, "HEAD", isLocal = true)

        val refs = logRefs(
            branches = listOf(local("a"), head),
            tags = emptyList(),
            currentBranch = Branch(HASH, "HEAD", isLocal = true),
            isUsedByOtherWorktree = { false },
            mergeRemotes = true,
        )

        assertEquals(listOf("HEAD", "a"), refs.labels())
    }

    private fun fit(vararg widths: Int, share: Int, firstChipRoom: Int = share) =
        fitChips(widths.toList(), share, firstChipRoom, overflowWidth = 40, minChipWidth = 60)

    @Test
    fun `all chips show when they fit`() {
        assertEquals(ChipsFit(3), fit(100, 100, 100, share = 300))
        assertEquals(ChipsFit(0), fit(share = 0))
    }

    @Test
    fun `chips that don't fit leave room for the chip that stands for them`() {
        // 100 + 100 + 40 fits in 250, a third chip doesn't
        assertEquals(ChipsFit(2), fit(100, 100, 100, 100, share = 250))
        // Two chips and +1 take 240
        assertEquals(ChipsFit(2), fit(100, 100, 100, share = 240))
        assertEquals(ChipsFit(1), fit(100, 100, 100, share = 239))
    }

    @Test
    fun `a later chip that would fit doesn't skip ahead of one that doesn't`() {
        assertEquals(ChipsFit(1), fit(50, 300, 10, 10, share = 120))
    }

    @Test
    fun `the first chip shows whole past the share when the row has room for it`() {
        // 100 + 40 doesn't fit in the share, but does in the row
        assertEquals(ChipsFit(1), fit(100, 100, share = 120, firstChipRoom = 140))
        // A single chip needs no +N
        assertEquals(ChipsFit(1), fit(150, share = 120, firstChipRoom = 150))
    }

    @Test
    fun `the first chip is cut down to the room the row has, but not below the narrowest chip`() {
        assertEquals(ChipsFit(1, cutFirstTo = 80), fit(200, 100, share = 100, firstChipRoom = 120))
        assertEquals(ChipsFit(1, cutFirstTo = 60), fit(200, 100, share = 100, firstChipRoom = 100))
        assertEquals(ChipsFit(0), fit(200, 100, share = 100, firstChipRoom = 99))
        // Without +N, a single chip has the whole room
        assertEquals(ChipsFit(1, cutFirstTo = 99), fit(200, share = 50, firstChipRoom = 99))
    }
}
