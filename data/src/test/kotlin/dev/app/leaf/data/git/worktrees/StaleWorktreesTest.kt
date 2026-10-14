// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.usecases.GetWorktreesInfoUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.inject.Provider

private const val DAY = 24 * 60 * 60 * 1000L

/**
 * Which worktrees [GetWorktreesInfoUseCase] marks as merged into the base and as idle (Phase 4.3a), in a repository
 * whose worktrees went through what agents and their users do with git 2.54.
 */
@DisabledOnOs(OS.WINDOWS)
class StaleWorktreesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val gitVariables by lazy {
        mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")
    }
    private val gitCli by lazy { testGitCli(gitVariables) }
    private val jgit = testJGit()

    private val main by lazy { File(tempDir, "main") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init", "-b", "main")
        commit(main, "README.md", "Initial commit")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `marks a branch merged once its own work is in the base, whichever way it got there`(): Unit = runBlocking {
        val fresh = worktree("fresh")
        val unmerged = worktree("unmerged")
        val fastForwarded = worktree("fast-forwarded")
        val merged = worktree("merged")
        val squashed = worktree("squashed")
        val rebasedThenMerged = worktree("rebased-then-merged")
        val abandoned = worktree("abandoned")
        val rebasedFresh = worktree("rebased-fresh")
        val synced = worktree("synced")
        val detached = File(tempDir, "detached").also { git.run(main, "worktree", "add", "--detach", it.path) }

        commit(unmerged, "unmerged.txt", "Unmerged work")
        commit(fastForwarded, "fast-forwarded.txt", "Fast-forwarded work")
        commit(merged, "merged.txt", "Merged work")
        commit(squashed, "squashed.txt", "Squashed work")
        commit(squashed, "squashed.txt", "More squashed work")
        commit(rebasedThenMerged, "rebased.txt", "Rebased work")
        commit(abandoned, "abandoned.txt", "Abandoned work")
        git.run(abandoned, "reset", "--hard", "HEAD~1")

        git.run(main, "merge", "--ff-only", "fast-forwarded")
        git.run(main, "merge", "--no-ff", "-m", "Merge merged", "merged")
        git.run(main, "merge", "--squash", "squashed")
        git.run(main, "commit", "-m", "Squash squashed")
        commit(main, "main.txt", "Main work")

        // A fresh branch moved to the base's commits: by "Rebase onto main", and by an agent catching up
        git.run(rebasedFresh, "rebase", "main")
        git.run(synced, "merge", "--ff-only", "main")
        git.run(rebasedThenMerged, "rebase", "main")
        git.run(main, "merge", "--ff-only", "rebased-then-merged")

        val list = worktreesInfo()

        assertEquals(MergedIntoBase.COMMITS, list.merged(fastForwarded))
        assertEquals(MergedIntoBase.COMMITS, list.merged(merged))
        assertEquals(MergedIntoBase.CHANGES, list.merged(squashed))
        assertEquals(MergedIntoBase.COMMITS, list.merged(rebasedThenMerged), "its commits before the rebase aren't")

        assertNull(list.merged(fresh), "in the base by ancestry, without work of its own")
        assertNull(list.merged(unmerged))
        assertNull(list.merged(abandoned), "its commit was reset away, not merged")
        assertNull(list.merged(rebasedFresh))
        assertNull(list.merged(synced))
        assertNull(list.merged(detached))
        assertNull(list.merged(main))
    }

    @Test
    fun `without a reflog, a branch that moved from where Claude Code started it is merged`(): Unit = runBlocking {
        val claude = worktree("claude")
        val claudeFresh = worktree("claude-fresh")
        val noRecord = worktree("no-record")
        val start = git.run(main, "rev-parse", "HEAD").trim()

        commit(claude, "claude.txt", "Claude's work")
        commit(noRecord, "no-record.txt", "Unrecorded work")
        git.run(main, "merge", "--ff-only", "claude")
        git.run(main, "merge", "--no-ff", "-m", "Merge no-record", "no-record")

        for (name in listOf("claude", "claude-fresh", "no-record")) {
            assertTrue(File(main, ".git/logs/refs/heads/$name").delete(), name)
        }
        File(main, ".git/worktrees/claude/CLAUDE_BASE").writeText("$start\n")
        File(main, ".git/worktrees/claude-fresh/CLAUDE_BASE").writeText("$start\n")

        val list = worktreesInfo()

        assertEquals(MergedIntoBase.COMMITS, list.merged(claude))
        assertNull(list.merged(claudeFresh))
        assertNull(list.merged(noRecord), "nothing tells that it moved")
    }

    @Test
    fun `merge-tree runs once for a base's and a branch's commits`(): Unit = runBlocking {
        val log = File(tempDir, "git.log")
        val action = GetMergedIntoBaseGitAction(loggingGitCli(log), jgit)
        val squashed = worktree("squashed")

        commit(squashed, "squashed.txt", "Squashed work")
        git.run(main, "merge", "--squash", "squashed")
        git.run(main, "commit", "-m", "Squash squashed")

        repeat(2) {
            assertEquals(Either.Ok(MergedIntoBase.CHANGES), action(gitDir(main), "refs/heads/main", "refs/heads/squashed", 1, null))
        }
        assertEquals(1, log.readLines().count { it.startsWith("merge-tree") }, log.readText())

        // Another commit of the base is another question
        commit(main, "main.txt", "Main work")
        assertEquals(Either.Ok(MergedIntoBase.CHANGES), action(gitDir(main), "refs/heads/main", "refs/heads/squashed", 1, null))
        assertEquals(2, log.readLines().count { it.startsWith("merge-tree") }, log.readText())
    }

    @Test
    fun `a linked worktree is idle since its last change, which a changed file moves`(): Unit = runBlocking {
        val agent = worktree("agent")
        val recent = worktree("recent")
        val now = System.currentTimeMillis()
        val longAgo = now - 20 * DAY

        File(agent, "README.md").appendText("Edited\n")
        setGitDirTimes("agent", longAgo)
        File(agent, "README.md").setLastModified(longAgo - DAY)
        setGitDirTimes("main", longAgo)

        worktreesInfo().let { list ->
            assertEquals(seconds(longAgo), list.idleSince(agent))
            assertNull(list.idleSince(recent))
            assertNull(list.idleSince(main), "the main worktree isn't marked")
        }

        File(agent, "README.md").setLastModified(now)

        assertNull(worktreesInfo().idleSince(agent), "an edited file is a change")
    }

    @Test
    fun `the git dir's recent change is enough, without running git status`(): Unit = runBlocking {
        val log = File(tempDir, "git.log")
        val action = GetWorktreeActivityGitAction(loggingGitCli(log))
        val agent = worktree("agent")
        val now = System.currentTimeMillis()

        val recentChange = (action(agent.path, notBefore = now - 7 * DAY) as Either.Ok).value!!
        assertTrue(recentChange >= now - 7 * DAY)
        assertFalse(log.exists(), "git didn't run")

        setGitDirTimes("agent", now - 20 * DAY)

        assertEquals(Either.Ok(seconds(now - 20 * DAY)), action(agent.path, notBefore = now - 7 * DAY))
        assertEquals(1, log.readLines().count { it.startsWith("status") }, log.readText())
    }

    /** [time] to the second, as [setGitDirTimes] sets it. */
    private fun seconds(time: Long) = time / 1000 * 1000

    /** Sets back the time of the files that git writes in the worktree's git dir as it works. */
    private fun setGitDirTimes(name: String, time: Long) {
        val gitDir = if (name == "main") File(main, ".git") else File(main, ".git/worktrees/$name")

        for (file in listOf("index", "HEAD", "logs/HEAD")) {
            File(gitDir, file).takeIf { it.exists() }?.setLastModified(seconds(time))
        }
    }

    private fun WorktreeList.merged(worktree: File) = info(worktree).merged

    private fun WorktreeList.idleSince(worktree: File) = info(worktree).idleSince

    private fun WorktreeList.info(worktree: File) = worktrees.single { it.worktree.path == worktree.canonicalPath }

    /** A linked worktree next to main on a new branch of that name, as `git worktree add -b` makes them. */
    private fun worktree(name: String): File {
        val folder = File(tempDir, name)
        git.run(main, "worktree", "add", "-b", name, folder.path)

        return folder
    }

    /** A [GitCli] whose git writes its arguments to [log], a line per command, then runs. */
    private fun loggingGitCli(log: File): GitCli {
        // git isn't on this folder's PATH, so the script's own git is the real one
        val loggingGit = File(tempDir, "bin/git").writeExecutable(
            "#!/bin/sh\necho \"$*\" >> '${log.path}'\nexec git \"$@\"\n"
        )

        return testGitCli(gitVariables, loggingGit.path)
    }

    /** What [GetWorktreesInfoUseCase] gives the main worktree's tab. */
    private suspend fun worktreesInfo(): WorktreeList {
        val repositoryPath = gitDir(main)
        val useCase = GetWorktreesInfoUseCase(
            GetWorktreesGitAction(gitCli, jgit),
            GetWorktreeStatusGitAction(gitCli),
            GetAheadBehindGitAction(gitCli),
            GetWorktreeBaseBranchGitAction(jgit),
            GetCommitTimesGitAction(jgit),
            GetMergedIntoBaseGitAction(gitCli, jgit),
            GetWorktreeActivityGitAction(gitCli),
            UseCaseExecutor(
                mockk<RepositoryDataRepository> { every { this@mockk.repositoryPath } returns repositoryPath },
                InMemoryRepositoryStateRepository(),
                Provider { error("Nothing is refreshed") },
                TabCoroutineScope(),
            ),
        )

        return (useCase() as Either.Ok).value
    }

    private fun commit(directory: File, fileName: String, message: String) {
        File(directory, fileName).appendText("$message\n")
        git.run(directory, "add", fileName)
        git.run(directory, "commit", "-m", message)
    }

    private fun gitDir(worktree: File): String {
        val gitDir = git.run(worktree, "rev-parse", "--git-dir").trim()

        return if (File(gitDir).isAbsolute) gitDir else File(worktree, gitDir).path
    }
}
