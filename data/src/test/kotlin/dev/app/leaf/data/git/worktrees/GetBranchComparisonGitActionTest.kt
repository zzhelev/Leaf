// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.diff.CanGenerateTextDiffGitAction
import dev.app.leaf.data.git.diff.FormatDiffGitAction
import dev.app.leaf.data.git.diff.FormatHunksGitAction
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromDiffTypeGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.data.git.diff.TextDiffFromDiffLinesGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.BranchComparison
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.MAX_COMPARED_COMMITS
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Compares branches as the review of a worktree does: `agent1` forked from `main`, which got a commit of its own
 * afterwards, as the base moves on while an agent works.
 */
class GetBranchComparisonGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val compare by lazy { GetBranchComparisonGitAction(jgit, JGitCommitMapper(JGitIdentityMapper()), testPartialCloneFetch(jgit)) }

    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir by lazy { File(repo, ".git").path }
    private lateinit var forkPoint: String

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        repo.mkdirs()
        git.run(repo, "init")
        write("a.txt", "line1\nline2\nline3\n")
        write("b.txt", "b\n")
        commit("Initial commit")
        forkPoint = git.run(repo, "rev-parse", "HEAD").trim()

        git.run(repo, "checkout", "-q", "-b", "agent1")
        write("a.txt", "login\nline2\nline3\n")
        write("retry.txt", "retry\n")
        commit("Add the login retry")
        write("test/retry_test.txt", "test\n")
        commit("Test the retry limit")

        git.run(repo, "checkout", "-q", "main")
        write("a.txt", "line1\nline2\nmain\n")
        write("b.txt", "b on main\n")
        commit("Update main")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lists the branch's commits oldest first, and its changes since the merge base without the base's own`(): Unit =
        runBlocking {
            val comparison = comparison("refs/heads/main", "refs/heads/agent1")

            assertEquals(forkPoint, comparison.mergeBase)
            assertEquals(listOf("Add the login retry", "Test the retry limit"), comparison.commits.map { it.shortMessage })
            assertEquals(2, comparison.ahead)
            assertEquals(1, comparison.behind)
            assertEquals(
                listOf("a.txt" to ChangeType.MODIFY, "retry.txt" to ChangeType.ADD, "test/retry_test.txt" to ChangeType.ADD),
                comparison.changes.map { it.filePath to it.changeType },
            )
        }

    @Test
    fun `a file's diff is from the merge base, so the base's later change to it isn't shown`(): Unit = runBlocking {
        val entry = comparison("refs/heads/main", "refs/heads/agent1").changes.first { it.filePath == "a.txt" }

        val diff = formatDiff()(gitDir, DiffType.CommitDiff(entry), isDisplayFullFile = false)

        val lines = assertInstanceOf(DiffResult.Text::class.java, (diff as Either.Ok).value).hunks.flatMap { it.lines }
        assertEquals(
            listOf(
                LineType.REMOVED to "line1",
                LineType.ADDED to "login",
                LineType.CONTEXT to "line2",
                LineType.CONTEXT to "line3",
            ),
            lines.map { it.lineType to it.text.trimEnd('\n') },
        )
    }

    @Test
    fun `compares with a remote base as with a local one`(): Unit = runBlocking {
        git.run(repo, "update-ref", "refs/remotes/origin/main", "refs/heads/main")

        val local = comparison("refs/heads/main", "refs/heads/agent1")
        val remote = comparison("refs/remotes/origin/main", "refs/heads/agent1")

        // JGit's DiffEntry has no equals of its own
        assertEquals(
            local.copy(base = "refs/remotes/origin/main", changes = emptyList()),
            remote.copy(changes = emptyList()),
        )
        assertEquals(local.changes.map { it.described() }, remote.changes.map { it.described() })
    }

    @Test
    fun `a branch that the base already has brings nothing`(): Unit = runBlocking {
        git.run(repo, "branch", "done", forkPoint)

        val comparison = comparison("refs/heads/main", "refs/heads/done")

        assertEquals(forkPoint, comparison.mergeBase)
        assertEquals(emptyList<Any>(), comparison.commits)
        assertEquals(0, comparison.ahead)
        assertEquals(1, comparison.behind)
        assertEquals(emptyList<Any>(), comparison.changes)
    }

    @Test
    fun `branches without a commit in common have no changes to compare`(): Unit = runBlocking {
        git.run(repo, "checkout", "-q", "--orphan", "pages")
        git.run(repo, "rm", "-rq", "--cached", ".")
        write("index.html", "<html>\n")
        git.run(repo, "add", "index.html")
        commit("Start the pages")

        val comparison = comparison("refs/heads/main", "refs/heads/pages")

        assertNull(comparison.mergeBase)
        assertEquals(listOf("Start the pages"), comparison.commits.map { it.shortMessage })
        assertEquals(2, comparison.behind)
        assertEquals(emptyList<Any>(), comparison.changes)
    }

    @Test
    fun `a branch that doesn't exist is an error that names it`(): Unit = runBlocking {
        assertEquals(
            Either.Err(GenericError("gone doesn't exist.")),
            compare(gitDir, "refs/heads/main", "refs/heads/gone"),
        )
        assertEquals(
            Either.Err(GenericError("origin/main doesn't exist.")),
            compare(gitDir, "refs/remotes/origin/main", "refs/heads/agent1"),
        )
    }

    @Test
    fun `lists the newest commits when there are too many, and counts them all`(): Unit = runBlocking {
        val total = MAX_COMPARED_COMMITS + 3
        createCommits("refs/heads/long", from = "refs/heads/main", count = total)

        val comparison = comparison("refs/heads/main", "refs/heads/long")

        assertEquals(total, comparison.ahead)
        assertEquals(MAX_COMPARED_COMMITS, comparison.commits.size)
        assertEquals("Commit 4", comparison.commits.first().shortMessage)
        assertEquals("Commit $total", comparison.commits.last().shortMessage)
    }

    private fun DiffEntry.described() = "$changeType $oldPath ${oldId.name()} $newPath ${newId.name()}"

    private suspend fun comparison(base: String, branch: String): BranchComparison =
        (compare(gitDir, base, branch) as Either.Ok).value

    private fun write(path: String, text: String) {
        val file = File(repo, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun commit(message: String) {
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", message)
    }

    /** Commits [count] empty commits on [ref], from [from], with JGit: a thousand `git commit` runs take too long. */
    private fun createCommits(ref: String, from: String, count: Int) {
        Git.open(repo).use { git ->
            val repository = git.repository
            var parent = repository.resolve(from)
            val tree = repository.parseCommit(parent).tree

            repository.newObjectInserter().use { inserter ->
                for (number in 1..count) {
                    val ident = PersonIdent("Leaf Test", "test@example.invalid", Instant.ofEpochSecond(1_700_000_000L + number), ZoneOffset.UTC)

                    parent = inserter.insert(
                        CommitBuilder().apply {
                            setTreeId(tree)
                            setParentId(parent)
                            author = ident
                            committer = ident
                            message = "Commit $number"
                        }
                    )
                }

                inserter.flush()
            }

            repository.updateRef(ref).apply { setNewObjectId(parent) }.forceUpdate()
        }
    }

    private fun formatDiff(): FormatDiffGitAction {
        val getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper())
        val getCurrentBranch = GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit)

        return FormatDiffGitAction(
            FormatHunksGitAction(),
            GetDiffContentGitAction(RawFileManager(TempFilesManager(AppFilesManager()))),
            CanGenerateTextDiffGitAction(),
            GetDiffEntryFromDiffTypeGitAction(GetDiffEntryFromStatusEntryGitAction(getRepositoryState, getCurrentBranch)),
            GetSubmodulesGitAction(jgit, JGitSubmoduleMapper()),
            TextDiffFromDiffLinesGitAction(),
            jgit,
            testPartialCloneFetch(jgit),
        )
    }
}
