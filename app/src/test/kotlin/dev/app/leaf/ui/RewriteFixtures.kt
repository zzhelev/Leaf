// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.BranchLines

/**
 * A branch `claude/parser-cache` with three commits of its own on `main`, for the tests of the menus, the dialog and the
 * strip that squash and reword commits (fork-only).
 */
object RewriteFixtures {
    const val BRANCH = "refs/heads/claude/parser-cache"
    const val MAIN = "refs/heads/main"
    const val UPSTREAM = "refs/remotes/origin/claude/parser-cache"

    fun commit(hash: String, message: String, parent: String?, parents: Int = 1) = Commit(
        hash = hash.padEnd(40, '0'),
        message = message,
        committer = Identity("A", "a@example.invalid"),
        author = Identity("A", "a@example.invalid"),
        date = 1_790_000_000,
        parentsHashes = if (parent == null) emptyList() else List(parents) { parent.padEnd(40, '0') },
    )

    val onMain = commit("e3c6632", "Fix the release notes", parent = null)
    val add = commit("a4880d1", "Add the parser", parent = "e3c6632")
    val wip = commit("b1c2d3e", "wip", parent = "a4880d1")
    val cache = commit("c0ffee1", "Cache the parsed files\n\nKeyed by the file's hash.", parent = "b1c2d3e")

    /** The line, newest first; [pushed] of its commits are on its upstream. */
    fun line(pushed: Set<String> = emptySet(), upstream: String? = UPSTREAM) = BranchLine(
        branch = BRANCH,
        base = MAIN,
        commits = listOf(cache, wip, add),
        upstream = upstream,
        pushed = pushed,
    )

    fun lines(pushed: Set<String> = emptySet()) = BranchLines(
        base = MAIN,
        lines = mapOf(BRANCH to line(pushed), MAIN to BranchLine(MAIN, MAIN, emptyList())),
        baseCommits = setOf(onMain.hash),
    )
}
