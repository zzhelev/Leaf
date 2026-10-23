// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.copiesToDelete
import dev.app.leaf.domain.branches.offersCopies
import dev.app.leaf.domain.branches.toDelete
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteOperationError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that deletes local branches and their copies on origin together (fork-only, follows upstream issue 116), in a
 * clone whose branches were pushed with their upstreams set: merged, squash-merged, unmerged, a merged `develop`, one
 * that a worktree uses, and one never pushed. Skipped without the askpass helper, as git deletes the copies.
 */
@DisabledOnOs(OS.WINDOWS)
class DeletingBranchesWithCopiesTest {
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

        pushWithUpstream("merged")
        git.run(work, "merge", "--no-ff", "-m", "Merge merged", "merged")
        pushWithUpstream("squashed", commits = 2)
        git.run(work, "merge", "--squash", "squashed")
        git.run(work, "commit", "-m", "Squashed")
        pushWithUpstream("develop")
        git.run(work, "merge", "--no-ff", "-m", "Merge develop", "develop")
        pushWithUpstream("used")
        git.run(work, "merge", "--no-ff", "-m", "Merge used", "used")
        git.run(work, "switch", "-c", "local-only")
        git.run(work, "switch", "main")
        git.run(work, "push", "origin", "main")
        pushWithUpstream("wip")
        git.run(work, "worktree", "add", File(tempDir, "used-wt").path, "used")
        git.run(work, "fetch", "origin")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads each local branch's copy on origin, as origin's own dialog would check it`(): Unit = runBlocking {
        open()

        val plan = plan()

        assertTrue(plan.offersCopies)
        assertEquals(
            mapOf(
                "develop" to "Merged COMMITS, goes, long-lived",
                "merged" to "Merged COMMITS, goes",
                "squashed" to "Merged CHANGES, goes",
                "used" to "Merged COMMITS, goes",
                "wip" to "OnOtherRefs, goes",
            ),
            plan.copies.entries.associate { (local, copy) -> local.removePrefix("refs/heads/") to describe(copy) },
        )
        // A branch never pushed has none, and main, the base, isn't offered
        assertFalse("refs/heads/local-only" in plan.copies)
        // The worktree's branch can't be deleted, so neither is its copy
        assertEquals(
            listOf("develop", "merged", "squashed"),
            plan.copiesToDelete(plan.checkedAtFirst()).keys.map { it.removePrefix("refs/heads/") }.sorted(),
        )
    }

    @Test
    fun `deletes the checked branches, then the copies that go with them, on the server`(): Unit = runBlocking {
        open()
        val plan = plan()
        val checked = plan.checkedAtFirst()

        val result = delete(plan, checked)

        assertEquals(
            listOf("develop", "local-only", "merged", "squashed"),
            result.deleted.map { it.simpleName }.sorted(),
        )
        assertEquals(
            mapOf(
                "refs/heads/develop" to "origin/develop",
                "refs/heads/merged" to "origin/merged",
                "refs/heads/squashed" to "origin/squashed",
            ),
            result.copies.mapValues { (_, outcome) ->
                assertInstanceOf(BranchDeletionOutcome.Deleted::class.java, outcome)
                outcome.branch.name.removePrefix("refs/remotes/")
            },
        )
        // develop's name looks long-lived, which the dialog marks: it goes, as asked
        assertEquals(listOf("main", "used", "wip"), serverBranches())
        assertEquals(listOf("origin/HEAD", "origin/main", "origin/used", "origin/wip"), trackingBranches())
        assertEquals("origin/merged ${plan.copies.getValue("refs/heads/merged").info.branch.tip}", result.deletedList().lines().last { it.startsWith("origin/merged") })
    }

    @Test
    fun `a copy that someone pushed to since the fetch stays, and its local branch still goes`(): Unit = runBlocking {
        open()
        val plan = plan()
        val other = File(tempDir, "other")
        git.run(tempDir, "clone", bare.path, other.path)
        git.run(other, "switch", "merged")
        File(other, "theirs.txt").writeText("theirs\n")
        git.run(other, "add", ".")
        git.run(other, "commit", "-m", "Their work")
        git.run(other, "push", "origin", "merged")
        val theirs = git.run(other, "rev-parse", "HEAD").trim()

        val result = delete(plan, plan.checkedAtFirst())

        assertTrue(result.deleted.any { it.simpleName == "merged" })
        assertEquals(
            BranchKept.ChangedOnRemote(theirs),
            (result.copies.getValue("refs/heads/merged") as BranchDeletionOutcome.Kept).reason,
        )
        assertTrue("merged" in serverBranches())
        assertFalse("squashed" in serverBranches())
        // Deleting it anyway takes the commit the server said
        val copy = plan.copies.getValue("refs/heads/merged").info.branch

        assertEquals(mapOf("refs/heads/merged" to ("origin" to copy.copy(serverTip = theirs))), result.changedCopies)

        // Deleted anyway, the copy goes, and Leaf's remote-tracking branch of it with it
        val anyway = tab.deleteBranches(listOf(copy.copy(serverTip = theirs)), "origin")
        assertEquals(Either.Ok(listOf(BranchDeletionOutcome.Deleted(copy.copy(serverTip = theirs)))), (anyway as? Either.Ok)?.let { Either.Ok(it.value.outcomes) })
        assertFalse("merged" in serverBranches())
        assertFalse("origin/merged" in trackingBranches())
    }

    @Test
    fun `a copy whose local branch stays, stays too`(): Unit = runBlocking {
        open()
        val plan = plan()
        val copies = plan.copiesToDelete(plan.checkedAtFirst())
        // The local branch moves after the dialog read it, so it isn't deleted
        git.run(work, "switch", "merged")
        File(work, "later.txt").writeText("later\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Later")
        git.run(work, "switch", "main")

        val result = when (val deleted = tab.deleteBranches.withCopies(plan.toDelete(plan.checkedAtFirst()), copies) {}) {
            is Either.Ok -> deleted.value
            is Either.Err -> throw AssertionError("The delete failed: ${deleted.error}")
        }

        assertInstanceOf(
            BranchDeletionOutcome.Kept::class.java,
            result.outcomes.single { it.branch.simpleName == "merged" },
        )
        assertFalse("refs/heads/merged" in result.copies)
        assertTrue("merged" in serverBranches())
        assertFalse("squashed" in serverBranches())
    }

    @Test
    fun `a server out of reach keeps the copies, and the local branches are deleted anyway`(): Unit = runBlocking {
        open()
        val plan = plan()
        git.run(work, "remote", "set-url", "origin", File(tempDir, "nowhere.git").path)

        val result = delete(plan, plan.checkedAtFirst())

        assertEquals(4, result.deleted.size)
        assertTrue(result.copies.isEmpty())
        assertInstanceOf(RemoteOperationError::class.java, result.copyErrors["origin"])
        assertTrue("merged" in serverBranches() && "squashed" in serverBranches())
    }

    @Test
    fun `the local branches' result reaches the dialog before git runs`(): Unit = runBlocking {
        open()
        val plan = plan()
        var local: BranchesDeletion? = null

        tab.deleteBranches.withCopies(plan.toDelete(plan.checkedAtFirst()), plan.copiesToDelete(plan.checkedAtFirst())) {
            local = it
            // git hasn't deleted the copies yet
            assertTrue("merged" in serverBranches())
        }

        assertEquals(4, local?.deleted?.size)
    }

    private suspend fun open() {
        tab.openRepository(work.path)
        tab.awaitLoaded(tab.repositoryData.localBranches) { branches -> branches.size == 7 }
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun plan(): BranchDeletionPlan {
        val branches = tab.awaitLoaded(tab.repositoryData.localBranches)

        return when (val result = tab.getBranchDeletionPlan(BranchesDeletionRequest(branches, mergedOnly = true))) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The plan failed: ${result.error}")
        }
    }

    private suspend fun delete(plan: BranchDeletionPlan, checked: Set<String>): BranchesDeletion =
        when (val result = tab.deleteBranches.withCopies(plan.toDelete(checked), plan.copiesToDelete(checked)) {}) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The delete failed: ${result.error}")
        }

    private fun describe(copy: BranchCopy): String {
        val kind = when (val kind = copy.kind) {
            is BranchDeletionKind.Refused -> "Refused ${kind.reason}"
            is BranchDeletionKind.Merged -> "Merged ${kind.into}"
            BranchDeletionKind.OnOtherRefs -> "OnOtherRefs"
            is BranchDeletionKind.Unmerged -> "Unmerged ${kind.commitsOnlyHere}"
        }

        return listOfNotNull(kind, "goes".takeIf { copy.goes }, "long-lived".takeIf { copy.looksLongLived }).joinToString(", ")
    }

    /** Creates [name] from main with [commits] commits of its own, and pushes it to origin with its upstream set. */
    private fun pushWithUpstream(name: String, commits: Int = 1) {
        git.run(work, "switch", "-c", name)

        repeat(commits) { index ->
            File(work, "$name-$index.txt").writeText("$name $index\n")
            git.run(work, "add", ".")
            git.run(work, "commit", "-m", "$name $index")
        }

        git.run(work, "push", "-u", "origin", name)
        git.run(work, "switch", "main")
    }

    private fun serverBranches(): List<String> =
        git.run(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads").lines().filter { it.isNotBlank() }

    private fun trackingBranches(): List<String> =
        git.run(work, "for-each-ref", "--format=%(refname)", "refs/remotes")
            .lines()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("refs/remotes/") }
}
