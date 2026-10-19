// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RemoteRefsTest {
    @Test
    fun `branches and tags come out as short names in natural order`() {
        val refs = remoteRefsOf(
            headTarget = "refs/heads/main",
            refNames = listOf(
                "refs/heads/release/1.10", "refs/heads/main", "refs/heads/release/1.2", "refs/heads/Docs",
                "refs/tags/v10", "refs/tags/v2", "refs/tags/v1.0",
            ),
        )

        assertEquals(listOf("Docs", "main", "release/1.2", "release/1.10"), refs.branches)
        assertEquals(listOf("v1.0", "v2", "v10"), refs.tags)
        assertEquals("main", refs.defaultBranch)
    }

    @Test
    fun `the line that names what an annotated tag points to is not a tag of its own`() {
        val refs = remoteRefsOf(null, listOf("refs/tags/v1.0", "refs/tags/v1.0^{}", "refs/tags/v2^{}"))

        assertEquals(listOf("v1.0"), refs.tags)
    }

    @Test
    fun `refs that are neither branches nor tags are left out`() {
        val refs = remoteRefsOf(
            headTarget = null,
            refNames = listOf("HEAD", "refs/pull/1/head", "refs/notes/commits", "refs/changes/01/1/1", "refs/heads/main"),
        )

        assertEquals(RemoteRefs(defaultBranch = null, branches = listOf("main"), tags = emptyList()), refs)
    }

    @Test
    fun `a ref listed twice is one`() {
        val refs = remoteRefsOf(null, listOf("refs/heads/main", "refs/heads/main", "refs/tags/v1", "refs/tags/v1"))

        assertEquals(listOf("main"), refs.branches)
        assertEquals(listOf("v1"), refs.tags)
    }

    @Test
    fun `the default branch is the branch that HEAD points to, when the remote has it`() {
        assertEquals("dev", remoteRefsOf("refs/heads/dev", listOf("refs/heads/main", "refs/heads/dev")).defaultBranch)
        // An unborn HEAD, as in an empty repository, and a HEAD that isn't a branch
        assertEquals(null, remoteRefsOf("refs/heads/gone", listOf("refs/heads/main")).defaultBranch)
        assertEquals(null, remoteRefsOf("refs/tags/v1", listOf("refs/heads/main", "refs/tags/v1")).defaultBranch)
        assertEquals(null, remoteRefsOf(null, listOf("refs/heads/main")).defaultBranch)
        // Not even when a branch has the tag's full name, as refs/heads/refs/tags/v1 does
        assertEquals(
            null,
            remoteRefsOf("refs/tags/v1", listOf("refs/heads/refs/tags/v1", "refs/tags/v1")).defaultBranch,
        )
    }

    @Test
    fun `a remote with nothing has nothing`() {
        assertEquals(RemoteRefs(null, emptyList(), emptyList()), remoteRefsOf(null, emptyList()))
    }
}
