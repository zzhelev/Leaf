// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
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
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that reads what deleting a remote's branches would do and deletes them on the server (fork-only, follows
 * upstream issue 116), in a clone of a bare repository with a merged branch, a squash-merged one, an unmerged one, a
 * merged `develop`, and the remote's HEAD and default branch. Skipped without the askpass helper, as git deletes them.
 */
@DisabledOnOs(OS.WINDOWS)
class DeletingRemoteBranchesTest {
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

    private val bare by lazy { File(tempDir, "origin.git") }
    private val work by lazy { File(tempDir, "work") }

    @BeforeEach
    fun createRepositories() {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        git.run(tempDir, "init", "--bare", bare.path)
        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", bare.path, "main")
        git.run(tempDir, "clone", bare.path, work.path)

        pushBranch("merged")
        git.run(work, "merge", "--no-ff", "-m", "Merge merged", "origin/merged")
        pushBranch("squashed", commits = 2)
        git.run(work, "merge", "--squash", "origin/squashed")
        git.run(work, "commit", "-m", "Squashed")
        pushBranch("develop")
        git.run(work, "merge", "--no-ff", "-m", "Merge develop", "origin/develop")
        git.run(work, "push", "origin", "main")
        pushBranch("wip")
        git.run(work, "fetch", "origin")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads what deleting each of origin's branches would do, as the dialog shows it`(): Unit = runBlocking {
        open()

        val plan = plan(originBranches())

        assertEquals("refs/remotes/origin/main", plan.base)
        assertEquals("origin", plan.remote?.name)
        assertEquals(bare.path, plan.remote?.url)
        assertEquals(
            mapOf(
                "HEAD" to "Refused RemoteHead",
                "develop" to "Merged COMMITS",
                "main" to "Refused RemoteDefaultBranch(isNamedByRemote=true)",
                "merged" to "Merged COMMITS",
                "squashed" to "Merged CHANGES",
                "wip" to "Unmerged 1",
            ),
            plan.infos.associate { it.branch.simpleName to describe(plan.kindOf(it)) },
        )
        assertEquals(setOf("refs/remotes/origin/merged", "refs/remotes/origin/squashed"), plan.checkedAtFirst())
    }

    @Test
    fun `delete merged branches on origin leaves out its HEAD and default branch`(): Unit = runBlocking {
        open()

        val plan = plan(originBranches(), mergedOnly = true)

        assertEquals(listOf("develop", "merged", "squashed"), plan.rows.map { it.branch.simpleName }.sorted())
        assertEquals(setOf("refs/remotes/origin/merged", "refs/remotes/origin/squashed"), plan.checkedAtFirst())
    }

    @Test
    fun `deletes what was checked on the server, and the tab's remote branches show what is left`(): Unit = runBlocking {
        open()
        val plan = plan(originBranches())

        val result = delete(plan, plan.checkedAtFirst() + "refs/remotes/origin/wip")

        assertEquals(
            listOf("merged", "squashed", "wip"),
            result.outcomes.map { (it as BranchDeletionOutcome.Deleted).branch.simpleName },
        )
        val left = tab.awaitLoaded(tab.repositoryData.remotes) { remotes ->
            remotes.flatMap { it.branchesList }.none { it.simpleName == "merged" }
        }
        assertEquals(
            listOf("HEAD", "develop", "main"),
            left.flatMap { it.branchesList }.map { it.simpleName }.sorted(),
        )
        assertEquals(
            listOf("refs/heads/develop", "refs/heads/main"),
            git.run(bare, "for-each-ref", "--format=%(refname)", "refs/heads").lines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `a branch someone pushed to while the dialog was open stays on the server`(): Unit = runBlocking {
        open()
        val plan = plan(originBranches())
        val other = File(tempDir, "other")
        git.run(tempDir, "clone", bare.path, other.path)
        git.run(other, "switch", "merged")
        File(other, "more.txt").writeText("more\n")
        git.run(other, "add", ".")
        git.run(other, "commit", "-m", "More")
        git.run(other, "push", "origin", "merged")

        val result = delete(plan, plan.checkedAtFirst())

        assertEquals(
            BranchKept.ChangedOnRemote,
            (result.outcomes.single { it.branch.simpleName == "merged" } as BranchDeletionOutcome.Kept).reason,
        )
        assertEquals(1, result.deleted.size)
        assertTrue("refs/heads/merged" in git.run(bare, "for-each-ref", "--format=%(refname)", "refs/heads"))
    }

    private suspend fun open() {
        tab.openRepository(work.path)
        tab.awaitLoaded(tab.repositoryData.remotes) { remotes -> remotes.flatMap { it.branchesList }.size == 6 }
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun originBranches(): List<Branch> =
        tab.awaitLoaded(tab.repositoryData.remotes).single { it.remote.name == "origin" }.branchesList

    private suspend fun plan(branches: List<Branch>, mergedOnly: Boolean = false): BranchDeletionPlan =
        when (val result = tab.getBranchDeletionPlan(BranchesDeletionRequest(branches, mergedOnly, remote = "origin"))) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The plan failed: ${result.error}")
        }

    private suspend fun delete(plan: BranchDeletionPlan, checked: Set<String>): BranchesDeletion =
        when (val result = tab.deleteBranches(plan.toDelete(checked), "origin")) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The delete failed: ${result.error}")
        }

    private fun describe(kind: BranchDeletionKind): String = when (kind) {
        is BranchDeletionKind.Refused -> "Refused ${kind.reason}"
        is BranchDeletionKind.Merged -> "Merged ${kind.into}"
        BranchDeletionKind.OnOtherRefs -> "OnOtherRefs"
        is BranchDeletionKind.Unmerged -> "Unmerged ${kind.commitsOnlyHere}"
    }

    /** Pushes [name] to origin from main with [commits] commits of its own, and deletes the local branch. */
    private fun pushBranch(name: String, commits: Int = 1) {
        git.run(work, "switch", "-c", name)

        repeat(commits) { index ->
            File(work, "$name-$index.txt").writeText("$name $index\n")
            git.run(work, "add", ".")
            git.run(work, "commit", "-m", "$name $index")
        }

        git.run(work, "push", "origin", name)
        git.run(work, "switch", "main")
        git.run(work, "branch", "-D", name)
    }
}
