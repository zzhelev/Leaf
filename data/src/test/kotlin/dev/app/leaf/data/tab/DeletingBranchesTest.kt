// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.toDelete
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.MergedIntoBase
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that reads what deleting branches would do and deletes them (fork-only, upstream issue 116), on a repository with
 * a merged branch, a squash-merged one, an unmerged one, one that a linked worktree uses, and one only a tag keeps.
 */
@DisabledOnOs(OS.WINDOWS)
class DeletingBranchesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }

    private val main by lazy { File(tempDir, "main") }
    private val agent by lazy { File(tempDir, "agent") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init")
        commit(main, "base.txt")

        git.run(main, "branch", "merged")

        git.run(main, "switch", "-c", "squashed")
        commit(main, "squashed-1.txt")
        commit(main, "squashed-2.txt")
        git.run(main, "switch", "main")
        git.run(main, "merge", "--squash", "squashed")
        git.run(main, "commit", "-m", "Squashed")

        git.run(main, "switch", "-c", "unmerged")
        commit(main, "unmerged-1.txt")
        git.run(main, "switch", "main")

        git.run(main, "switch", "-c", "tagged")
        commit(main, "tagged-1.txt")
        git.run(main, "tag", "keep-tagged")
        git.run(main, "switch", "main")

        git.run(main, "worktree", "add", agent.path, "-b", "agent")
        commit(agent, "agent-1.txt")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads what deleting each branch would do, as the dialog shows it`(): Unit = runBlocking {
        open()

        val plan = plan(everyLocalBranch())

        assertEquals("refs/heads/main", plan.base)
        assertEquals(
            mapOf(
                "agent" to "Refused",
                "main" to "Refused",
                "merged" to "Merged COMMITS 0",
                "squashed" to "Merged CHANGES 2",
                "tagged" to "OnOtherRefs",
                "unmerged" to "Unmerged 1",
            ),
            plan.infos.associate { it.branch.simpleName to describe(plan.kindOf(it)) },
        )
        assertEquals(
            setOf("refs/heads/merged", "refs/heads/squashed", "refs/heads/tagged"),
            plan.checkedAtFirst(),
        )
    }

    @Test
    fun `deletes what was checked, once, and the branch list shows what is left`(): Unit = runBlocking {
        open()
        val plan = plan(everyLocalBranch())

        val result = delete(plan, plan.checkedAtFirst())

        assertEquals(
            listOf("merged", "squashed", "tagged"),
            result.outcomes.map { assertDeleted(it).branch.simpleName },
        )
        val left = tab.awaitLoaded(tab.repositoryData.localBranches) { branches ->
            branches.none { it.simpleName == "merged" }
        }
        assertEquals(listOf("agent", "main", "unmerged"), left.map { it.simpleName }.sorted())
        assertEquals("keep-tagged", git.run(main, "tag", "--list").trim(), "A tag is not a branch")
        assertEquals("", git.run(main, "branch", "--list", "merged", "squashed", "tagged"))
    }

    @Test
    fun `a branch that moved after the dialog opened is left, and the others go`(): Unit = runBlocking {
        open()
        val plan = plan(everyLocalBranch())
        val unmergedTip = git.run(main, "rev-parse", "unmerged").trim()
        // An agent commits to it while the dialog is open
        git.run(main, "switch", "unmerged")
        commit(main, "unmerged-2.txt")
        git.run(main, "switch", "main")
        val movedTo = git.run(main, "rev-parse", "unmerged").trim()

        val result = delete(plan, plan.checkedAtFirst() + "refs/heads/unmerged")

        assertEquals(
            BranchDeletionOutcome.Kept(
                plan.infos.single { it.branch.simpleName == "unmerged" }.branch,
                BranchKept.Moved(movedTo),
            ),
            result.outcomes.single { it.branch.simpleName == "unmerged" },
        )
        assertEquals(unmergedTip, plan.infos.single { it.branch.simpleName == "unmerged" }.branch.tip)
        assertEquals(movedTo, git.run(main, "rev-parse", "unmerged").trim(), "Its new commit is still there")
        assertEquals(3, result.deleted.size)
    }

    @Test
    fun `delete merged branches lists the merged ones and leaves out the base`(): Unit = runBlocking {
        open()

        val plan = plan(everyLocalBranch(), mergedOnly = true)

        assertEquals(listOf("merged", "squashed"), plan.rows.map { it.branch.simpleName })
        assertEquals(setOf("refs/heads/merged", "refs/heads/squashed"), plan.checkedAtFirst())
        assertTrue(plan.infos.none { it.branch.name == "refs/heads/main" }, "The base is never offered")
    }

    @Test
    fun `a branch that a worktree uses is refused again by the delete, if it is checked anyway`(): Unit = runBlocking {
        open()
        val plan = plan(everyLocalBranch())

        val result = delete(plan, setOf("refs/heads/agent"), ignoringRefusals = true)

        val kept = result.outcomes.single() as BranchDeletionOutcome.Kept
        assertTrue(kept.reason is BranchKept.UsedByWorktree, kept.reason.toString())
        assertEquals("agent", git.run(main, "branch", "--list", "agent").trim().removePrefix("+ ").trim())
    }

    private suspend fun open() {
        tab.openRepository(main.path)
        tab.awaitLoaded(tab.repositoryData.localBranches) { it.size == 6 }
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun everyLocalBranch() = tab.awaitLoaded(tab.repositoryData.localBranches)

    private suspend fun plan(branches: List<Branch>, mergedOnly: Boolean = false): BranchDeletionPlan =
        when (val result = tab.getBranchDeletionPlan(BranchesDeletionRequest(branches, mergedOnly))) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The plan failed: ${result.error}")
        }

    private suspend fun delete(
        plan: BranchDeletionPlan,
        checked: Set<String>,
        ignoringRefusals: Boolean = false,
    ): BranchesDeletion {
        val toDelete = if (ignoringRefusals) {
            plan.infos.filter { it.branch.name in checked }.map { it.branch }
        } else {
            plan.toDelete(checked)
        }

        return when (val result = tab.deleteBranches(toDelete)) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The delete failed: ${result.error}")
        }
    }

    private fun assertDeleted(outcome: BranchDeletionOutcome): BranchDeletionOutcome.Deleted =
        outcome as? BranchDeletionOutcome.Deleted ?: throw AssertionError("Not deleted: $outcome")

    private fun describe(kind: BranchDeletionKind): String = when (kind) {
        is BranchDeletionKind.Refused -> "Refused"
        is BranchDeletionKind.Merged -> "Merged ${if (kind.into == MergedIntoBase.COMMITS) "COMMITS" else "CHANGES"} ${kind.commitsOnlyHere}"
        BranchDeletionKind.OnOtherRefs -> "OnOtherRefs"
        is BranchDeletionKind.Unmerged -> "Unmerged ${kind.commitsOnlyHere}"
    }

    /** A commit that adds [file]: a branch of commits that change nothing would count as merged. */
    private fun commit(repository: File, file: String) {
        File(repository, file).writeText("$file\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Add $file")
    }
}
