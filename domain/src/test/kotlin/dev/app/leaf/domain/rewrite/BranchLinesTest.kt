// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BranchLinesTest {
    private val someone = Identity("Author", "a@example.invalid")

    private fun commit(name: String, vararg parents: String) =
        Commit(name, "Subject of $name\n\nBody of $name\n", someone, someone, 0, parents.toList())

    // feat: c (tip), b, a; stacked: d on top of feat; main: m. feat's c and b are on origin/feat.
    private val a = commit("a", "m")
    private val b = commit("b", "a")
    private val c = commit("c", "b")
    private val d = commit("d", "c")
    private val feat = BranchLine("refs/heads/feat", "refs/heads/main", listOf(c, b, a), "refs/remotes/origin/feat", setOf("a", "b"))
    private val stacked = BranchLine("refs/heads/stacked", "refs/heads/main", listOf(d, c, b, a))
    private val lines = BranchLines(
        base = "refs/heads/main",
        lines = mapOf(feat.branch to feat, stacked.branch to stacked, "refs/heads/main" to BranchLine("refs/heads/main", "refs/heads/main", emptyList())),
        baseCommits = setOf("m"),
    )
    private val idle: (String) -> BranchOperation? = { null }

    private fun <T> Either<T, RewriteRefusal>.ok(): T = (this as Either.Ok).value

    @Test
    fun `a commit of one branch's line is that branch's`() {
        assertEquals(stacked, lines.lineHolding(listOf("d"), currentBranch = null).ok())
    }

    @Test
    fun `a commit that several lines have is the current branch's, and refused without one of them current`() {
        assertEquals(feat, lines.lineHolding(listOf("b"), currentBranch = "refs/heads/feat").ok())
        assertEquals(stacked, lines.lineHolding(listOf("b"), currentBranch = "refs/heads/stacked").ok())
        assertEquals(Either.Err(RewriteRefusal.OnSeveralBranches(2)), lines.lineHolding(listOf("b"), "refs/heads/main"))
    }

    @Test
    fun `a commit of the base, or of no local branch, is refused with why`() {
        assertEquals(Either.Err(RewriteRefusal.OnBase("main")), lines.lineHolding(listOf("m"), null))
        assertEquals(Either.Err(RewriteRefusal.NotOnLocalBranch), lines.lineHolding(listOf("z"), null))
        // A range with a commit of the base in it
        assertEquals(Either.Err(RewriteRefusal.OnBase("main")), lines.lineHolding(listOf("a", "m"), null))
    }

    @Test
    fun `a commit row's items reword it and join it with its parent, on its branch`() {
        val choices = lines.commitChoices("d", currentBranch = null, busy = idle)

        assertEquals(listOf("d"), choices.reword.ok().commits.map { it.hash })
        assertEquals(listOf("c", "d"), choices.intoParent.ok().commits.map { it.hash })
        assertEquals("refs/heads/stacked", choices.intoParent.ok().line.branch)

        val first = lines.commitChoices("a", currentBranch = "refs/heads/feat", busy = idle)
        assertEquals(Either.Err(RewriteRefusal.FirstCommit), first.intoParent)
    }

    @Test
    fun `a branch that a worktree is busy with refuses every item`() {
        val busy: (String) -> BranchOperation? = { if (it == "refs/heads/stacked") BranchOperation.REBASE else null }
        val refused = Either.Err(RewriteRefusal.Busy(BranchOperation.REBASE))

        assertEquals(CommitRewriteChoices(refused, refused), lines.commitChoices("d", null, busy))
        assertEquals(refused, lines.rangeChoice(listOf("d", "c"), null, busy))
        assertEquals(refused, lines.branchChoice("refs/heads/stacked", busy))
        assertEquals(listOf("a", "b", "c"), lines.branchChoice("refs/heads/feat", busy)?.ok()?.commits?.map { it.hash })
    }

    @Test
    fun `a range squashes commits of one line, one after another`() {
        assertEquals(listOf("c", "d"), lines.rangeChoice(listOf("d", "c"), null, idle).ok().commits.map { it.hash })
        assertEquals(Either.Err(RewriteRefusal.NotConsecutive), lines.rangeChoice(listOf("d", "b"), null, idle))
        assertEquals(Either.Err(RewriteRefusal.OnSeveralBranches(2)), lines.rangeChoice(listOf("b", "a"), null, idle))
    }

    @Test
    fun `squashing a branch is offered for a branch with commits of its own`() {
        assertEquals(listOf("a", "b", "c", "d"), lines.branchChoice("refs/heads/stacked", idle)?.ok()?.commits?.map { it.hash })
        assertNull(lines.branchChoice("refs/heads/main", idle))
        assertNull(lines.branchChoice("refs/heads/other", idle))

        val one = BranchLines(lines = mapOf("refs/heads/x" to BranchLine("refs/heads/x", null, listOf(a))))
        assertEquals(Either.Err(RewriteRefusal.OneCommit), one.branchChoice("refs/heads/x", idle))
    }

    @Test
    fun `a line cut short can't squash the whole branch, nor its last commit read into its parent`() {
        val cut = BranchLine("refs/heads/long", null, listOf(c, b), isTruncated = true)

        assertEquals(Either.Err(RewriteRefusal.TooManyCommits), cut.allCommits())
        assertEquals(Either.Err(RewriteRefusal.TooManyCommits), cut.commitWithParent("b"))
        assertEquals(listOf("b", "c"), cut.commitWithParent("c").ok().commits.map { it.hash })
    }

    @Test
    fun `the pushed commits that a rewrite changes are the range's and those after it`() {
        // feat: c, b pushed... a and b are on origin/feat
        assertEquals(1, feat.commitToReword("b").ok().rewrittenPushed)
        assertEquals(2, feat.commitToReword("a").ok().rewrittenPushed)
        assertEquals(0, feat.commitToReword("c").ok().rewrittenPushed)
        assertEquals(2, feat.allCommits().ok().rewrittenPushed)
    }

    @Test
    fun `a request starts from the subjects for a squash and from the message for a reword`() {
        val squash = RewriteRequest(RewriteDialogKind.SQUASH, feat.commitsToJoin(listOf("b", "c")).ok())
        val reword = RewriteRequest(RewriteDialogKind.REWORD, feat.commitToReword("b").ok())

        assertEquals("Subject of b\n\nBody of b\n\n- Subject of c", squash.messageStart())
        assertEquals("Subject of b\n\nBody of b", reword.messageStart())
        assertEquals(RewriteKind.SQUASH, squash.rewrite("New").kind)
        assertEquals(RewriteKind.REWORD, reword.rewrite("New").kind)
        assertEquals(listOf(RewriteGroup(listOf("b"), "New")), reword.rewrite("New").groups)
    }

    @Test
    fun `only squashing the whole branch goes on the latest base`() {
        val onto = OntoBase("refs/heads/main", "n".repeat(40))
        val whole = lines.branchChoice("refs/heads/feat", idle)!!.ok()
        val part = lines.rangeChoice(listOf("b", "c"), "refs/heads/feat", idle).ok()

        assertEquals(onto, RewriteRequest(RewriteDialogKind.SQUASH_BRANCH, whole).rewrite("All of it", onto).onto)
        assertEquals(listOf("a", "b", "c"), RewriteRequest(RewriteDialogKind.SQUASH_BRANCH, whole).rewrite("All", onto).groups.single().commits)
        assertNull(RewriteRequest(RewriteDialogKind.SQUASH, part).rewrite("Part", onto).onto)
        assertNull(RewriteRequest(RewriteDialogKind.SQUASH_BRANCH, whole).rewrite("All of it").onto)
    }
}
