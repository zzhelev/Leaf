// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BranchLineTest {
    private fun commit(name: String, vararg parents: String) = Commit(
        hash = name.padEnd(40, '0'),
        message = "Subject of $name\n\nBody of $name\n",
        committer = Identity("Committer", "c@example.invalid"),
        author = Identity("Author", "a@example.invalid"),
        date = 0,
        parentsHashes = parents.map { it.padEnd(40, '0') },
    )

    private fun hash(name: String) = name.padEnd(40, '0')

    // feat: e (tip), d, m (a merge of main), c, b (first); its base's commit is a
    private val e = commit("e", "d")
    private val d = commit("d", "m")
    private val m = commit("m", "c", "f")
    private val c = commit("c", "b")
    private val b = commit("b", "a")
    private val line = BranchLine("refs/heads/feat", "refs/heads/main", listOf(e, d, m, c, b))

    private fun <T> Either<T, RewriteRefusal>.ok(): T = (this as Either.Ok).value

    private fun Either<LineRange, RewriteRefusal>.hashes() = ok().commits.map { it.hash }

    @Test
    fun `squash and fixup into the parent join the commit and the one below it, oldest first`() {
        assertEquals(listOf(hash("d"), hash("e")), line.commitWithParent(hash("e")).hashes())
        assertEquals(listOf(hash("b"), hash("c")), line.commitWithParent(hash("c")).hashes())
    }

    @Test
    fun `the branch's first commit has no parent of its own to join`() {
        assertEquals(Either.Err(RewriteRefusal.FirstCommit), line.commitWithParent(hash("b")))
    }

    @Test
    fun `a merge can't be joined with its child or its parent`() {
        assertEquals(Either.Err(RewriteRefusal.JoinsMerge(hash("m"))), line.commitWithParent(hash("d")))
        assertEquals(Either.Err(RewriteRefusal.JoinsMerge(hash("m"))), line.commitWithParent(hash("m")))
    }

    @Test
    fun `a merge can be reworded`() {
        assertEquals(listOf(hash("m")), line.commitToReword(hash("m")).hashes())
    }

    @Test
    fun `a range joins two or more commits one after the other, whatever order they were picked in`() {
        assertEquals(listOf(hash("d"), hash("e")), line.commitsToJoin(listOf(hash("e"), hash("d"))).hashes())
        assertEquals(listOf(hash("b"), hash("c")), line.commitsToJoin(setOf(hash("b"), hash("c"), hash("c"))).hashes())
    }

    @Test
    fun `a range with a gap, a merge, one commit or a commit of another line is refused`() {
        assertEquals(Either.Err(RewriteRefusal.NotConsecutive), line.commitsToJoin(listOf(hash("e"), hash("c"))))
        assertEquals(
            Either.Err(RewriteRefusal.JoinsMerge(hash("m"))),
            line.commitsToJoin(listOf(hash("c"), hash("m"), hash("d"))),
        )
        assertEquals(Either.Err(RewriteRefusal.OneCommit), line.commitsToJoin(listOf(hash("e"), hash("e"))))
        assertEquals(
            Either.Err(RewriteRefusal.NotOnLine(hash("a"))),
            line.commitsToJoin(listOf(hash("b"), hash("a"))),
        )
        assertEquals(Either.Err(RewriteRefusal.NotOnLine(hash("a"))), line.commitToReword(hash("a")))
        assertEquals(Either.Err(RewriteRefusal.NotOnLine(hash("a"))), line.commitWithParent(hash("a")))
    }

    @Test
    fun `the whole branch is every commit of its line, oldest first, with no merge`() {
        val straight = BranchLine("refs/heads/x", "refs/heads/main", listOf(e, d))

        assertEquals(listOf(hash("d"), hash("e")), straight.allCommits().hashes())
        assertEquals(Either.Err(RewriteRefusal.JoinsMerge(hash("m"))), line.allCommits())
        assertEquals(Either.Err(RewriteRefusal.OneCommit), BranchLine("refs/heads/x", null, listOf(e)).allCommits())
        assertEquals(Either.Err(RewriteRefusal.OneCommit), BranchLine("refs/heads/x", null, emptyList()).allCommits())
    }

    @Test
    fun `a range becomes one group, planned on the branch's tip`() {
        val range = line.commitWithParent(hash("e")).ok()

        assertEquals(
            BranchRewrite(
                branch = "refs/heads/feat",
                expectedTip = hash("e"),
                groups = listOf(RewriteGroup(listOf(hash("d"), hash("e")), "Joined")),
                kind = RewriteKind.SQUASH,
            ),
            range.squash("Joined"),
        )
        assertEquals(RewriteGroup(listOf(hash("d"), hash("e")), null), range.fixup().groups.single())
        assertEquals(RewriteKind.FIXUP, range.fixup().kind)

        val reword = line.commitToReword(hash("c")).ok().reword("New")

        assertEquals(listOf(RewriteGroup(listOf(hash("c")), "New")), reword.groups)
        assertEquals(RewriteKind.REWORD, reword.kind)
        assertEquals(hash("e"), reword.expectedTip)
    }
}
