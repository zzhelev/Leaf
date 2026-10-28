// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which of the worktree dialogs' commands may check out or stage files, and so go through Leaf's LFS filter, its
 * dialogs and Cancel: not the ones that Cancel would stop halfway without need, such as `git worktree remove`.
 */
class ChecksOutOrStagesTest {
    @Test
    fun `commands that write the working tree or the index do`() {
        for (args in listOf(
            listOf("worktree", "add", "../agent", "-b", "agent"),
            listOf("merge", "--squash", "agent"),
            listOf("merge", "--no-ff", "--no-commit", "agent"),
            listOf("rebase", "--no-autostash", "main"),
            listOf("switch", "main"),
            listOf("sparse-checkout", "reapply", "--no-sparse-index"),
            listOf("checkout", "--theirs", "--", "a.bin"),
            listOf("add", "--", "a.bin"),
            listOf("stash", "apply", "abc"),
            listOf("reset", "--merge"),
            listOf("read-tree", "-m", "-u", "a", "b"),
            listOf("mergetool", "--tool=meld"),
        )) {
            assertEquals(true, checksOutOrStages(args), "$args")
        }
    }

    @Test
    fun `commands that only read or move refs don't`() {
        for (args in listOf(
            listOf("worktree", "remove", "../agent"),
            listOf("worktree", "prune"),
            listOf("worktree", "lock", "--reason", "x", "../agent"),
            listOf("branch", "-D", "agent"),
            listOf("rev-parse", "--symbolic-full-name", "agent"),
            listOf("config", "core.splitIndex", "false"),
            listOf("update-index", "--no-split-index"),
            listOf("sparse-checkout", "list"),
            emptyList(),
        )) {
            assertEquals(false, checksOutOrStages(args), "$args")
        }
    }

    @Test
    fun `git's options before the command are skipped`() {
        assertEquals(true, checksOutOrStages(listOf("--literal-pathspecs", "checkout", "--ours", "--", "[ab].txt")))
        assertEquals(true, checksOutOrStages(listOf("-c", "merge.ff=false", "merge", "agent")))
        assertEquals(true, checksOutOrStages(listOf("-C", "/repo", "worktree", "add", "../agent")))
        assertEquals(false, checksOutOrStages(listOf("-c", "core.quotePath=false", "branch", "-D", "agent")))
    }
}
