// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.usecases.GetWorktreesInfoUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import javax.inject.Provider

private const val INITIAL_DATE = "2026-01-02T03:04:05Z"
private const val FEATURE_DATE = "2026-01-03T03:04:05Z"
private const val MAIN_DATE = "2026-01-04T03:04:05Z"

/**
 * The worktree git actions and [GetWorktreesInfoUseCase] against a repository whose worktrees were made with the git
 * CLI: main, feature (one commit ahead of main, one behind, with changes), detached, locked, prunable (folder deleted)
 * and agent (nested in main, as Claude Code places them).
 */
@DisabledOnOs(OS.WINDOWS)
class WorktreesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }

    // Keeps the git that Leaf runs away from the developer's config too, as it could change what status reports
    private val gitCli by lazy {
        testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }
    private val jgit = testJGit()

    private val main by lazy { File(tempDir, "main") }
    private val feature by lazy { File(tempDir, "feature") }
    private val detached by lazy { File(tempDir, "detached") }
    private val locked by lazy { File(tempDir, "locked") }
    private val gone by lazy { File(tempDir, "gone") }
    private val agent by lazy { File(main, ".claude/worktrees/agent") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init")
        File(main, ".git/info/exclude").appendText(".claude/\n")
        commit(main, "README.md", "Initial commit", INITIAL_DATE)

        git.run(main, "worktree", "add", feature.path, "-b", "feature")
        commit(feature, "feature.txt", "Feature work", FEATURE_DATE)

        git.run(main, "worktree", "add", "--detach", detached.path)
        git.run(main, "worktree", "add", locked.path, "-b", "locked-branch")
        git.run(main, "worktree", "lock", "--reason", "agent running", locked.path)
        git.run(main, "worktree", "add", gone.path, "-b", "gone-branch")
        gone.deleteRecursively()
        git.run(main, "worktree", "add", agent.path, "-b", "agent")

        commit(main, "main.txt", "Main work", MAIN_DATE)

        git.run(main, "branch", "--set-upstream-to=main", "feature")
        File(feature, "staged.txt").writeText("staged\n")
        git.run(feature, "add", "staged.txt")
        File(feature, "README.md").appendText("unstaged\n")
        File(feature, "untracked.txt").writeText("untracked\n")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lists the worktrees, main first, with the tab's one as current`(): Unit = runBlocking {
        val worktrees = (GetWorktreesGitAction(gitCli, jgit)(gitDir(main)) as Either.Ok).value

        assertEquals(main.canonicalPath, worktrees.first().path)
        assertEquals(
            setOf(main, feature, detached, locked, gone, agent).map { it.canonicalPath }.toSet(),
            worktrees.map { it.path }.toSet(),
        )

        val byPath = worktrees.associateBy { it.path }
        val mainWorktree = byPath.getValue(main.canonicalPath)
        assertEquals(true, mainWorktree.isMain)
        assertEquals(true, mainWorktree.isCurrent)
        assertEquals("refs/heads/main", mainWorktree.branch)
        assertEquals(1, worktrees.count { it.isMain })
        assertEquals(1, worktrees.count { it.isCurrent })

        assertEquals("refs/heads/feature", byPath.getValue(feature.canonicalPath).branch)
        assertEquals(true, byPath.getValue(detached.canonicalPath).isDetached)
        assertNull(byPath.getValue(detached.canonicalPath).branch)
        assertEquals("agent running", byPath.getValue(locked.canonicalPath).locked)
        assertEquals("gitdir file points to non-existent location", byPath.getValue(gone.canonicalPath).prunable)
        assertEquals("refs/heads/agent", byPath.getValue(agent.canonicalPath).branch)
    }

    @Test
    fun `marks a linked worktree as current when the tab shows it`(): Unit = runBlocking {
        val worktrees = (GetWorktreesGitAction(gitCli, jgit)(gitDir(feature)) as Either.Ok).value

        assertEquals(listOf(feature.canonicalPath), worktrees.filter { it.isCurrent }.map { it.path })
    }

    @Test
    fun `tells which branch a detached worktree rebases or bisects from`(): Unit = runBlocking {
        val rebasing = File(tempDir, "rebasing")
        git.run(main, "worktree", "add", rebasing.path, "-b", "rebased", "HEAD~1")
        // main.txt is also on main, with other content
        commit(rebasing, "main.txt", "Rebased work", FEATURE_DATE)
        git.runFailing(rebasing, "rebase", "main")
        repeat(3) { commit(agent, "agent.txt", "Agent work $it", FEATURE_DATE) }
        git.run(agent, "bisect", "start", "HEAD", "HEAD~3")

        // From a linked worktree, whose own folder git lists like the others
        val worktrees = (GetWorktreesGitAction(gitCli, jgit)(gitDir(detached)) as Either.Ok).value
        val byPath = worktrees.associateBy { it.path }

        byPath.getValue(rebasing.canonicalPath).let {
            assertNull(it.branch, "git lists a rebasing worktree as detached")
            assertEquals("refs/heads/rebased", it.rebasingBranch)
            assertNull(it.bisectingBranch)
        }
        byPath.getValue(agent.canonicalPath).let {
            assertNull(it.branch, "git lists a bisecting worktree as detached")
            assertNull(it.rebasingBranch)
            assertEquals("refs/heads/agent", it.bisectingBranch)
        }
        for (worktree in listOf(main, feature, detached, locked, gone)) {
            byPath.getValue(worktree.canonicalPath).let {
                assertNull(it.rebasingBranch, worktree.name)
                assertNull(it.bisectingBranch, worktree.name)
            }
        }

        // Compared to main by the branch, not by the commit they stopped at
        val infos = worktreesInfo(gitDir(main)).worktrees.associateBy { it.worktree.path }
        assertEquals(AheadBehind(ahead = 1, behind = 1), infos.getValue(rebasing.canonicalPath).aheadBehindBase)
        assertEquals(AheadBehind(ahead = 3, behind = 1), infos.getValue(agent.canonicalPath).aheadBehindBase)
    }

    @Test
    fun `reads a worktree's changes and how it compares to its upstream`(): Unit = runBlocking {
        val status = GetWorktreeStatusGitAction(gitCli)(feature.path)

        assertEquals(Either.Ok(WorktreeStatus(1, 1, 1, 0, "main", AheadBehind(ahead = 1, behind = 1))), status)
    }

    @Test
    fun `compares a branch or a commit to the base branch`(): Unit = runBlocking {
        val aheadBehind = GetAheadBehindGitAction(gitCli)
        val initialCommit = git.run(main, "rev-parse", "HEAD~1").trim()

        assertEquals(
            Either.Ok(AheadBehind(ahead = 1, behind = 1)),
            aheadBehind(gitDir(main), "refs/heads/main", "refs/heads/feature"),
        )
        assertEquals(Either.Ok(AheadBehind(ahead = 0, behind = 1)), aheadBehind(gitDir(main), "refs/heads/main", initialCommit))
    }

    @Test
    fun `picks origin's default branch, then main, then master as the base`(): Unit = runBlocking {
        val getDefaultBaseBranch = GetDefaultBaseBranchGitAction(jgit)
        assertEquals(Either.Ok("refs/heads/main"), getDefaultBaseBranch(gitDir(main)))

        git.run(main, "branch", "develop")
        git.run(main, "update-ref", "refs/remotes/origin/develop", "HEAD")
        git.run(main, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/develop")
        assertEquals(Either.Ok("refs/heads/develop"), getDefaultBaseBranch(gitDir(main)))

        val master = File(tempDir, "master").apply { mkdirs() }
        git.run(master, "init", "-b", "master")
        commit(master, "README.md", "Initial commit", INITIAL_DATE)
        assertEquals(Either.Ok("refs/heads/master"), getDefaultBaseBranch(gitDir(master)))

        val trunk = File(tempDir, "trunk").apply { mkdirs() }
        git.run(trunk, "init", "-b", "trunk")
        commit(trunk, "README.md", "Initial commit", INITIAL_DATE)
        assertEquals(Either.Ok(null), getDefaultBaseBranch(gitDir(trunk)))
    }

    @Test
    fun `reads commit times and leaves out commits it can't find`(): Unit = runBlocking {
        val mainCommit = git.run(main, "rev-parse", "HEAD").trim()
        val missingCommit = "0123456789abcdef0123456789abcdef01234567"

        val times = GetCommitTimesGitAction(jgit)(gitDir(main), listOf(mainCommit, missingCommit))

        assertEquals(Either.Ok(mapOf(mainCommit to millis(MAIN_DATE))), times)
    }

    @Test
    fun `gives every worktree its changes, comparison to the base and last commit`(): Unit = runBlocking {
        val list = worktreesInfo(gitDir(main))
        val byPath = list.worktrees.associateBy { it.worktree.path }
        val clean = WorktreeStatus(0, 0, 0, 0, null, null)
        val behindByOne = AheadBehind(ahead = 0, behind = 1)

        assertEquals("refs/heads/main", list.baseBranch)
        assertEquals(main.canonicalPath, list.worktrees.first().worktree.path)

        byPath.getValue(main.canonicalPath).let {
            assertEquals(clean, it.status)
            assertNull(it.aheadBehindBase, "main is the base")
            assertEquals(millis(MAIN_DATE), it.lastCommitTime)
        }

        byPath.getValue(feature.canonicalPath).let {
            assertEquals(WorktreeStatus(1, 1, 1, 0, "main", AheadBehind(ahead = 1, behind = 1)), it.status)
            assertEquals(AheadBehind(ahead = 1, behind = 1), it.aheadBehindBase)
            assertEquals(millis(FEATURE_DATE), it.lastCommitTime)
        }

        for (worktree in listOf(detached, locked, agent)) {
            byPath.getValue(worktree.canonicalPath).let {
                assertEquals(clean, it.status, worktree.name)
                assertEquals(behindByOne, it.aheadBehindBase, worktree.name)
                assertEquals(millis(INITIAL_DATE), it.lastCommitTime, worktree.name)
            }
        }

        byPath.getValue(gone.canonicalPath).let {
            assertNull(it.status, "A prunable worktree has no folder to read")
            assertEquals(behindByOne, it.aheadBehindBase)
        }
    }

    /** What [GetWorktreesInfoUseCase] gives a tab that holds [repositoryPath]. */
    private suspend fun worktreesInfo(repositoryPath: String): WorktreeList {
        val useCase = GetWorktreesInfoUseCase(
            GetWorktreesGitAction(gitCli, jgit),
            GetWorktreeStatusGitAction(gitCli),
            GetAheadBehindGitAction(gitCli),
            GetDefaultBaseBranchGitAction(jgit),
            GetCommitTimesGitAction(jgit),
            UseCaseExecutor(
                mockk<RepositoryDataRepository> { every { this@mockk.repositoryPath } returns repositoryPath },
                InMemoryRepositoryStateRepository(),
                Provider { error("Nothing is refreshed") },
                TabCoroutineScope(),
            ),
        )

        return (useCase() as Either.Ok).value
    }

    private fun commit(directory: File, fileName: String, message: String, date: String) {
        File(directory, fileName).appendText("$message\n")
        git.run(directory, "add", fileName)
        git.run(directory, mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date), "commit", "-m", message)
    }

    /** The git dir a tab would hold for [worktree]. */
    private fun gitDir(worktree: File): String {
        val gitDir = git.run(worktree, "rev-parse", "--git-dir").trim()

        return if (File(gitDir).isAbsolute) gitDir else File(worktree, gitDir).path
    }

    private fun millis(date: String) = Instant.parse(date).toEpochMilli()
}
