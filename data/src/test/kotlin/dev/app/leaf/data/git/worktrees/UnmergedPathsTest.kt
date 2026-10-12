// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The paths in `git ls-files --unmerged -z`'s output, which has a line per stage. Output from git 2.54. */
class UnmergedPathsTest {
    @Test
    fun `lists each conflicted path once`() {
        val output = "100644 7898192261 1\tf.txt\u0000" +
            "100644 0d55bed3a3 2\tf.txt\u0000" +
            "100644 1f7391f92b 3\tf.txt\u0000" +
            "100644 1f7391f92b 3\tdir/added by them.txt\u0000"

        assertEquals(listOf("f.txt", "dir/added by them.txt"), unmergedPaths(output))
    }

    @Test
    fun `keeps a tab in a path, after the one that ends the stage`() {
        assertEquals(listOf("a\tb.txt"), unmergedPaths("100644 7898192261 2\ta\tb.txt\u0000"))
    }

    @Test
    fun `has no paths without conflicts`() {
        assertEquals(emptyList<String>(), unmergedPaths(""))
    }
}
