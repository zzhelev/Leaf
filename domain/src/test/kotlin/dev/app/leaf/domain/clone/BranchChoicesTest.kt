// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.clone

import dev.app.leaf.domain.models.RemoteRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BranchChoicesTest {
    private val refs = RemoteRefs(
        defaultBranch = "main",
        branches = listOf("docs", "feature/clone-branch", "main", "release/1.0", "release/1.10"),
        tags = listOf("v0.9", "v1.0", "v1.0-release-candidate"),
    )

    @Test
    fun `nothing typed lists everything, the default branch first`() {
        val choices = refs.choicesFor("")

        assertEquals(listOf("main", "docs", "feature/clone-branch", "release/1.0", "release/1.10"), choices.branches.map { it.name })
        assertEquals(listOf("v0.9", "v1.0", "v1.0-release-candidate"), choices.tags.map { it.name })
        assertEquals(listOf(true, false, false, false, false), choices.branches.map { it.isDefault })
        assertEquals(8, choices.total)
        assertEquals(8, choices.matching)
    }

    @Test
    fun `the text matches anywhere in the name, without regard to case, and is trimmed`() {
        val choices = refs.choicesFor("  RE ")

        // "feature" has it inside too
        assertEquals(listOf("feature/clone-branch", "release/1.0", "release/1.10"), choices.branches.map { it.name })
        assertEquals(listOf("v1.0-release-candidate"), choices.tags.map { it.name })
        assertEquals(4, choices.matching)
        assertEquals(8, choices.total)
        assertEquals(BranchChoice("feature/clone-branch", isTag = false, isDefault = false, matchStart = 5, matchLength = 2), choices.branches[0])
        assertEquals(BranchChoice("release/1.0", isTag = false, isDefault = false, matchStart = 0, matchLength = 2), choices.branches[1])
        assertEquals(BranchChoice("v1.0-release-candidate", isTag = true, isDefault = false, matchStart = 5, matchLength = 2), choices.tags[0])
    }

    @Test
    fun `the default branch stays first when it matches, and is left out when it doesn't`() {
        assertEquals(listOf("main"), refs.choicesFor("ma").branches.map { it.name })
        assertEquals(true, refs.choicesFor("ma").branches.single().isDefault)
        assertEquals(listOf("docs"), refs.choicesFor("doc").branches.map { it.name })
        assertEquals(false, refs.choicesFor("doc").branches.single().isDefault)
    }

    @Test
    fun `a tag named like the default branch isn't the default`() {
        val tagged = RemoteRefs("main", listOf("main"), listOf("main"))

        assertEquals(listOf(true), tagged.choicesFor("").branches.map { it.isDefault })
        assertEquals(listOf(false), tagged.choicesFor("").tags.map { it.isDefault })
    }

    @Test
    fun `nothing matching is nothing, and the total stays`() {
        val choices = refs.choicesFor("zzz")

        assertEquals(0, choices.matching)
        assertEquals(8, choices.total)
    }

    @Test
    fun `a remote without a default branch lists the branches as they are`() {
        val noDefault = refs.copy(defaultBranch = null)

        assertEquals(refs.branches, noDefault.choicesFor("").branches.map { it.name })
    }

    @Test
    fun `the host is the one that the URL names`() {
        assertEquals("github.com", remoteHostOf("https://github.com/zzhelev/Leaf.git"))
        assertEquals("github.com", remoteHostOf("git@github.com:zzhelev/Leaf.git"))
        assertEquals("git.example.com", remoteHostOf("ssh://git@git.example.com:2222/team/repo.git"))
        assertEquals("github.com", remoteHostOf("  https://github.com/x/y  "))
        assertEquals(null, remoteHostOf("/tmp/leaf-clone-demo/source"))
        assertEquals(null, remoteHostOf("file:///tmp/leaf-clone-demo/source"))
        assertEquals(null, remoteHostOf(""))
    }
}
