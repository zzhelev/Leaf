// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.CheckoutBranchGitAction
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.diff.CanGenerateTextDiffGitAction
import dev.app.leaf.data.git.diff.FormatDiffGitAction
import dev.app.leaf.data.git.diff.FormatHunksGitAction
import dev.app.leaf.data.git.diff.GetCommitDiffEntriesGitAction
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromDiffTypeGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.data.git.diff.TextDiffFromDiffLinesGitAction
import dev.app.leaf.data.git.log.BlameFileGitAction
import dev.app.leaf.data.git.log.CherryPickCommitGitAction
import dev.app.leaf.data.git.log.ResetToCommitGitAction
import dev.app.leaf.data.git.log.RevertCommitGitAction
import dev.app.leaf.data.git.rebase.RebaseBranchGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.git.worktrees.GetBranchComparisonGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.PartialCloneFetchError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.usecases.ResetType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * A partial clone (`git clone --filter=blob:none`) has every commit and tree but only the files that it checked out.
 * JGit can't download the others, so Leaf has git fetch them ([PartialCloneFetch]): before showing a diff or a blame,
 * and before a checkout, a merge or a rebase, which JGit stopped halfway, with files deleted and HEAD where it was.
 * Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class PartialCloneTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var source: File
    private lateinit var clone: File

    private val gitDir get() = File(clone, ".git").absolutePath
    private val partialClone get() = remote.partialClone

    /**
     * The commits of the source: main is one, two, three; other branches off two with addD, then otherA; side branches
     * off one with a file of its own.
     */
    private lateinit var one: String
    private lateinit var two: String
    private lateinit var three: String
    private lateinit var addD: String
    private lateinit var otherA: String

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig, jgit = jgit)

        source = File(tempDir, "source").apply { mkdirs() }
        git.run(source, "init")
        git.run(source, "config", "uploadpack.allowFilter", "true")
        one = commit("one", "a.txt" to "v1\n", "b.txt" to "B line 1\nB line 2\nB line 3\nB line 4\n")
        git.run(source, "branch", "side")
        two = commit("two", "a.txt" to "v2\n")
        git.run(source, "checkout", "-b", "other")
        addD = commit("add d", "d.txt" to "other's d\n")
        otherA = commit("other's a", "a.txt" to "other\n")
        git.run(source, "checkout", "side")
        commit("side", "s.txt" to "side's file\n")
        git.run(source, "checkout", "main")
        git.run(source, "mv", "b.txt", "c.txt")
        File(source, "c.txt").appendText("B line 5\n")
        three = commit("three", "a.txt" to "v3\n")

        git.run(tempDir, "clone", "--filter=blob:none", "file://${source.absolutePath}", "clone")
        clone = File(tempDir, "clone")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a commit's files are listed without downloading them`(): Unit = runBlocking {
        val before = missingObjects()

        val entries = commitDiffEntries(two)

        assertEquals(listOf(DiffEntry.ChangeType.MODIFY to "a.txt"), entries.map { it.changeType to it.newPath })
        assertEquals(before, missingObjects())
    }

    @Test
    fun `a commit's file diff downloads the two versions, and nothing else`(): Unit = runBlocking {
        val before = missingObjects()
        val entry = commitDiffEntries(two).single()

        val diff = formatDiff(DiffType.CommitDiff(entry))

        assertEquals(listOf("-v1", "+v2"), assertInstanceOf(DiffResult.Text::class.java, diff.ok()).lines())
        assertEquals(before - setOf(blob(one, "a.txt"), blob(two, "a.txt")), missingObjects())
    }

    @Test
    fun `blame downloads the file's versions`(): Unit = runBlocking {
        val blame = BlameFileGitAction(jgit, partialClone)(gitDir, "a.txt").ok()

        assertEquals(three, blame.getSourceCommit(0).name)
        assertEquals("v3", blame.resultContents.getString(0))
    }

    @Test
    fun `a branch's review lists what it changes`(): Unit = runBlocking {
        val compare = GetBranchComparisonGitAction(jgit, JGitCommitMapper(JGitIdentityMapper()), partialClone)

        val comparison = compare(gitDir, "main", "origin/other").ok()

        assertEquals(listOf("a.txt", "d.txt"), comparison.changes.map { it.newPath })
    }

    @Test
    fun `a download that fails says why`(): Unit = runBlocking {
        val entry = commitDiffEntries(two).single()
        git.run(clone, "remote", "set-url", "origin", File(tempDir, "gone").absolutePath)

        val error = formatDiff(DiffType.CommitDiff(entry)).err()

        val fetchError = assertInstanceOf(PartialCloneFetchError::class.java, error)
        assertEquals("origin", fetchError.remote)
        assertTrue("does not appear to be a git repository" in fetchError.error.toString(), "$fetchError")
    }

    @Test
    fun `checking out a branch downloads its files first`(): Unit = runBlocking {
        git.run(clone, "branch", "other", "origin/other")

        CheckoutBranchGitAction(jgit, partialClone)(gitDir, branch("other")).ok()

        assertEquals("other's d\n", File(clone, "d.txt").readText())
        assertEquals("other\n", File(clone, "a.txt").readText())
        assertEquals("", status())
    }

    @Test
    fun `a checkout whose download fails changes nothing`(): Unit = runBlocking {
        git.run(clone, "branch", "other", "origin/other")
        git.run(clone, "remote", "set-url", "origin", File(tempDir, "gone").absolutePath)

        val error = CheckoutBranchGitAction(jgit, partialClone)(gitDir, branch("other")).err()

        assertTrue(assertInstanceOf(PartialCloneFetchError::class.java, error).nothingChanged)
        assertEquals("refs/heads/main", git.run(clone, "symbolic-ref", "HEAD").trim())
        assertEquals("", status())
        assertTrue(File(clone, "c.txt").isFile)
    }

    @Test
    fun `a merge with conflicts reads all three versions`(): Unit = runBlocking {
        val hasConflicts = MergeBranchGitAction(jgit, partialClone)(gitDir, remoteBranch("other"), fastForward = true)

        assertEquals(Either.Ok(true), hasConflicts)
        val merged = File(clone, "a.txt").readText()
        assertTrue("v3" in merged && "other" in merged, merged)
        assertEquals("other's d\n", File(clone, "d.txt").readText())
    }

    @Test
    fun `a hard reset downloads the commit's files first`(): Unit = runBlocking {
        ResetToCommitGitAction(jgit, partialClone)(gitDir, commit(one), ResetType.HARD).ok()

        assertEquals("v1\n", File(clone, "a.txt").readText())
        assertTrue(File(clone, "b.txt").isFile)
        assertEquals("", status())
    }

    @Test
    fun `a cherry-pick and a revert download what they apply`(): Unit = runBlocking {
        CherryPickCommitGitAction(jgit, partialClone)(gitDir, commit(addD)).ok()
        assertEquals("other's d\n", File(clone, "d.txt").readText())

        RevertCommitGitAction(jgit, partialClone)(gitDir, commit(git.run(clone, "rev-parse", "HEAD~1").trim())).ok()
        assertEquals("v2\n", File(clone, "a.txt").readText())
        assertEquals("B line 1\nB line 2\nB line 3\nB line 4\n", File(clone, "b.txt").readText())
        assertEquals("", status())
    }

    @Test
    fun `a rebase downloads the commits it replays`(): Unit = runBlocking {
        git.run(clone, "branch", "with-d", addD)

        val stopped = RebaseBranchGitAction(jgit, partialClone)(gitDir, branch("with-d"))

        assertEquals(Either.Ok(false), stopped)
        assertEquals("other's d\n", File(clone, "d.txt").readText())
        assertEquals("v3\n", File(clone, "a.txt").readText())
        assertEquals("", status())
    }

    @Test
    fun `a rebase downloads the versions that only the commits it replays have`(): Unit = runBlocking {
        // two's a.txt, which neither main nor side has, is checked out when two is replayed
        val stopped = RebaseBranchGitAction(jgit, partialClone)(gitDir, remoteBranch("side"))

        assertEquals(Either.Ok(false), stopped)
        assertEquals("v3\n", File(clone, "a.txt").readText())
        assertEquals("side's file\n", File(clone, "s.txt").readText())
        assertEquals("", status())
    }

    @Test
    fun `a pull downloads what it merges`(): Unit = runBlocking {
        git.run(source, "mv", "c.txt", "e.txt")
        commit("four", "a.txt" to "v4\n")

        val pull = GitCliPullBranchGitAction(
            jgit = jgit,
            remoteCommand = remote.command,
            gitLfsFetch = remote.gitLfsFetch,
            partialClone = partialClone,
            checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
            deleteStashGitAction = DeleteStashGitAction(jgit),
            commitMapper = JGitCommitMapper(JGitIdentityMapper()),
        )

        val hasConflicts = pull(gitDir, PullType.MERGE, mergeAutoStash = true, remoteBranch = null, "automatic stash")

        assertEquals(Either.Ok(false), hasConflicts)
        assertEquals("v4\n", File(clone, "a.txt").readText())
        assertTrue(File(clone, "e.txt").isFile)
        assertEquals("", status())
    }

    @Test
    fun `git pull downloads what it merges itself`(): Unit = runBlocking {
        git.run(source, "mv", "c.txt", "e.txt")
        commit("four", "a.txt" to "v4\n")

        val hasConflicts = testGitPullGitAction(jgit, remote.gitCli)(
            gitDir,
            PullType.MERGE,
            mergeAutoStash = true,
            remoteBranch = null,
            "automatic stash",
        )

        assertEquals(Either.Ok(false), hasConflicts)
        assertEquals("v4\n", File(clone, "a.txt").readText())
        assertTrue(File(clone, "e.txt").isFile)
        assertEquals("", status())
    }

    private fun commit(message: String, vararg files: Pair<String, String>): String {
        for ((path, content) in files) {
            File(source, path).writeText(content)
        }

        git.run(source, "add", ".")
        git.run(source, "commit", "-m", message)

        return git.run(source, "rev-parse", "HEAD").trim()
    }

    /** The objects that the clone hasn't downloaded, which `--missing=print` lists without fetching them. */
    private fun missingObjects(): Set<String> = git.run(clone, "rev-list", "--objects", "--all", "--missing=print")
        .lines()
        .filter { it.startsWith("?") }
        .map { it.removePrefix("?") }
        .toSet()

    private fun blob(commit: String, path: String) = git.run(clone, "rev-parse", "$commit:$path").trim()

    private fun status() = git.run(clone, "status", "--porcelain").trim()

    private fun commit(hash: String) = Commit(
        hash = hash,
        message = "",
        committer = Identity("Leaf Test", "test@example.invalid"),
        author = Identity("Leaf Test", "test@example.invalid"),
        date = 0,
        parentsHashes = git.run(clone, "rev-list", "--parents", "-n1", hash).trim().split(" ").drop(1),
    )

    private fun branch(name: String) = Branch(git.run(clone, "rev-parse", name).trim(), "refs/heads/$name", true)

    private fun remoteBranch(name: String) =
        Branch(git.run(clone, "rev-parse", "origin/$name").trim(), "refs/remotes/origin/$name", false)

    private suspend fun commitDiffEntries(hash: String): List<DiffEntry> =
        GetCommitDiffEntriesGitAction(jgit, partialClone)(gitDir, commit(hash)).ok()

    private suspend fun formatDiff(diffType: DiffType): Either<DiffResult, GitError> {
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
            partialClone,
        )(gitDir, diffType, isDisplayFullFile = false)
    }

    private fun DiffResult.Text.lines(): List<String> = hunks.flatMap { it.lines }.map { line ->
        when (line.lineType) {
            LineType.ADDED -> "+"
            LineType.REMOVED -> "-"
            LineType.CONTEXT -> " "
        } + line.text.trimEnd()
    }

    private fun <T> Either<T, GitError>.ok(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> throw AssertionError("Expected success, got $error")
    }

    private fun <T> Either<T, GitError>.err(): GitError = when (this) {
        is Either.Ok -> throw AssertionError("Expected an error, got $value")
        is Either.Err -> error
    }
}
