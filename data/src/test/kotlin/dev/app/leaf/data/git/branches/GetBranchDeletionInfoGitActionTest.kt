// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.isolatedGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GetBranchDeletionInfoGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val action by lazy { GetBranchDeletionInfoGitAction(testJGit(), isolatedGitCli(globalConfig)) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a branch in the base is merged by its commits and has no commit of its own`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "old", "HEAD")
        commit(repository, "More on main")
        git.run(repository, "branch", "-f", "old", "HEAD~1")

        val info = read(repository, base = "refs/heads/main", "old").single()

        assertEquals(MergedIntoBase.COMMITS, info.merged)
        assertEquals(0, info.commitsOnlyHere)
        assertNull(info.refusal)
        assertEquals(tip(repository, "old"), info.branch.tip)
        assertEquals("refs/heads/old", info.branch.name)
    }

    @Test
    fun `an unmerged branch is not merged and counts its own commits`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 3)

        val info = read(repository, base = "refs/heads/main", "feature").single()

        assertNull(info.merged)
        assertEquals(3, info.commitsOnlyHere)
    }

    @Test
    fun `a squash-merged branch is merged by its changes and still counts its commits`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "switch", "-c", "feature")
        File(repository, "a.txt").writeText("one\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Add a")
        File(repository, "a.txt").writeText("one\ntwo\n")
        git.run(repository, "commit", "-am", "Extend a")
        git.run(repository, "switch", "main")
        git.run(repository, "merge", "--squash", "feature")
        git.run(repository, "commit", "-m", "Feature, squashed")

        val info = read(repository, base = "refs/heads/main", "feature").single()

        assertEquals(MergedIntoBase.CHANGES, info.merged)
        assertEquals(2, info.commitsOnlyHere, "A squash merge leaves its commits on no branch")
    }

    @Test
    fun `a branch whose commits another branch has too has none of its own`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 2)
        git.run(repository, "branch", "copy", "feature")

        val infos = read(repository, base = "refs/heads/main", "feature")

        assertEquals(0, infos.single().commitsOnlyHere)
        assertNull(infos.single().merged)
    }

    @Test
    fun `two selected branches at one commit don't make each other safe`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 2)
        git.run(repository, "branch", "copy", "feature")

        val infos = read(repository, base = "refs/heads/main", "feature", "copy")

        assertEquals(listOf(2, 2), infos.map { it.commitsOnlyHere })
    }

    @Test
    fun `a branch below another selected one counts against the refs left, not against the other`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 2)
        git.run(repository, "branch", "base-of-feature", "feature~1")

        val withOnlyLower = read(repository, base = "refs/heads/main", "base-of-feature").single()
        val withBoth = read(repository, base = "refs/heads/main", "base-of-feature", "feature")

        assertEquals(0, withOnlyLower.commitsOnlyHere, "feature keeps its commit")
        assertEquals(listOf(1, 2), withBoth.map { it.commitsOnlyHere })
    }

    @Test
    fun `a pushed branch has none of its own commits, a remote-tracking branch being another ref`(): Unit = runBlocking {
        val upstream = git.initRepository(File(tempDir, "upstream"))
        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)
        createBranchWithCommits(clone, "feature", commits = 1)
        git.run(clone, "push", "origin", "feature")

        val info = read(clone, base = "refs/heads/main", "feature").single()

        assertEquals(0, info.commitsOnlyHere)
        assertNull(info.merged)
    }

    @Test
    fun `a branch is merged into a remote base too`(): Unit = runBlocking {
        val upstream = git.initRepository(File(tempDir, "upstream"))
        val clone = File(tempDir, "clone")
        git.run(tempDir, "clone", upstream.absolutePath, clone.absolutePath)
        git.run(clone, "branch", "merged")
        createBranchWithCommits(clone, "unmerged", commits = 1)

        val infos = read(clone, base = "refs/remotes/origin/main", "merged", "unmerged")

        assertEquals(listOf(MergedIntoBase.COMMITS, null), infos.map { it.merged })
    }

    @Test
    fun `without a base nothing is merged`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "same-as-main")

        val info = read(repository, base = null, "same-as-main").single()

        assertNull(info.merged)
        assertEquals(0, info.commitsOnlyHere, "Main has its commits")
    }

    @Test
    fun `the base itself is merged into itself, which the dialog ignores`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "other")

        val infos = read(repository, base = "refs/heads/other", "other")

        assertEquals(MergedIntoBase.COMMITS, infos.single().merged)
        assertEquals(
            0,
            infos.single().commitsOnlyHere,
            "The base stays unless it is deleted too, which the dialog works out from its plan",
        )
    }

    @Test
    fun `a base that is selected too leaves its branches with the commits they only have`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "switch", "-c", "develop")
        commit(repository, "Develop")
        git.run(repository, "switch", "-c", "topic")
        commit(repository, "Topic")
        git.run(repository, "switch", "main")

        val infos = read(repository, base = "refs/heads/develop", "develop", "topic")

        assertEquals(
            listOf(1, 2),
            infos.map { it.commitsOnlyHere },
            "Develop's commit is only on develop and topic, so it counts for both when both go",
        )
    }

    @Test
    fun `names the worktree that uses a branch and leaves the others free`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)
        git.run(repository, "branch", "free")

        val infos = read(repository, base = "refs/heads/main", "agent", "free", "main")

        assertEquals(
            BranchKept.UsedByWorktree(worktree.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = false),
            infos[0].refusal?.copy(path = File(infos[0].refusal!!.path).canonicalPath),
        )
        assertNull(infos[1].refusal)
        assertEquals(
            BranchKept.UsedByWorktree(repository.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = true),
            infos[2].refusal?.copy(path = File(infos[2].refusal!!.path).canonicalPath),
        )
    }

    @Test
    fun `leaves out a branch that does not exist, and anything that is not a local branch`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "real")
        git.run(repository, "tag", "v1")

        val result = action(
            File(repository, ".git").absolutePath,
            "refs/heads/main",
            listOf("refs/heads/real", "refs/heads/missing", "refs/tags/v1", "refs/remotes/origin/main"),
        )

        assertEquals(listOf("refs/heads/real"), (result as Either.Ok).value.map { it.branch.name })
    }

    @Test
    fun `names with a slash and unusual characters are read like any other`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val names = listOf("claude/eager-ride", "fix#12", "user@host", "it's", "ünï/çödé", "100%")

        for (name in names) git.run(repository, "branch", name)

        val infos = read(repository, base = "refs/heads/main", *names.toTypedArray())

        assertEquals(names.map { "refs/heads/$it" }, infos.map { it.branch.name })
        assertTrue(infos.all { it.merged == MergedIntoBase.COMMITS && it.refusal == null })
    }

    @Test
    fun `reads sixty merged branches and sixty unmerged ones`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val merged = (1..60).map { "merged/m$it" }
        val unmerged = (1..60).map { "claude/u$it" }

        git.runWithInput(repository, merged.joinToString("\n") { "create refs/heads/$it HEAD" } + "\n", "update-ref", "--stdin")
        // Each unmerged branch has a commit of its own that adds a file, made in one fast-import
        val head = git.run(repository, "rev-parse", "HEAD").trim()
        val stream = unmerged.joinToString("") { name ->
            val file = "${name.substringAfter('/')}.txt"

            "commit refs/heads/$name\ncommitter T <t@example.com> 1700000000 +0000\ndata ${name.length}\n$name\n" +
                "from $head\nM 100644 inline $file\ndata ${name.length}\n$name\n\n"
        }
        git.runWithInput(repository, stream, "fast-import", "--quiet")

        val startedAt = System.nanoTime()
        val infos = read(repository, base = "refs/heads/main", *(merged + unmerged).toTypedArray())
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0

        assertEquals(60, infos.count { it.merged == MergedIntoBase.COMMITS })
        assertEquals(60, infos.count { it.merged == null && it.commitsOnlyHere == 1 })
        assertTrue(seconds < 30, "Reading 120 branches took $seconds s")
    }

    private suspend fun read(repository: File, base: String?, vararg names: String): List<BranchDeletionInfo> =
        when (val result = action(File(repository, ".git").absolutePath, base, names.map { "refs/heads/$it" })) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The action failed: ${result.error}")
        }

    private fun tip(repository: File, name: String) = git.run(repository, "rev-parse", "refs/heads/$name").trim()

    /** A commit that changes a file of its own: a branch of commits that change nothing is merged into anything. */
    private fun commit(repository: File, message: String) {
        File(repository, "file-${message.replace(Regex("[^A-Za-z0-9]"), "-")}.txt").writeText("$message\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", message)
    }

    /** Creates [name] from main with [commits] commits of its own, then checks out main again. */
    private fun createBranchWithCommits(repository: File, name: String, commits: Int) {
        git.run(repository, "switch", "-c", name)

        repeat(commits) { index -> commit(repository, "$name ${index + 1}") }

        git.run(repository, "switch", "main")
    }
}
