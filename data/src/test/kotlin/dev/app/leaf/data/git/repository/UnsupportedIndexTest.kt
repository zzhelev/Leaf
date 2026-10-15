// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.CheckoutBranchGitAction
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.diff.CanGenerateTextDiffGitAction
import dev.app.leaf.data.git.diff.FormatDiffGitAction
import dev.app.leaf.data.git.diff.FormatHunksGitAction
import dev.app.leaf.data.git.diff.GetCommitDiffEntriesGitAction
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromDiffTypeGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.data.git.diff.TextDiffFromDiffLinesGitAction
import dev.app.leaf.data.git.log.BlameFileGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.GetStatusGitAction
import dev.app.leaf.data.git.workspace.StageEntryGitAction
import dev.app.leaf.data.git.worktrees.RunGitCommandGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.tab.TestTab
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.domain.index.conversionSteps
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.worktrees.WorktreeStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * A split or a sparse index, which git writes for large repositories and JGit can't read ("Invalid path"): what used to
 * fail with that message now says what the index is and how to convert it, and what doesn't read the index works.
 */
class UnsupportedIndexTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(globalConfig) }
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val jgit = testJGit()

    private val repository by lazy {
        File(tempDir, "repo").apply {
            mkdirs()
            git.run(this, "init")

            for (folder in listOf("a", "b")) {
                for (i in 1..3) {
                    File(this, "$folder/f$i").apply { parentFile.mkdirs() }.writeText("$folder $i\n")
                }
            }

            git.run(this, "add", ".")
            git.run(this, "commit", "-m", "Initial commit")
            File(this, "a/f1").appendText("second line\n")
            git.run(this, "commit", "-am", "Second commit")
            git.run(this, "branch", "other", "HEAD~1")
        }
    }

    private val gitDir get() = File(repository, ".git").absolutePath

    /** What keeps the git that Leaf runs away from the developer's config. */
    private val gitConfigVariables get() = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.absolutePath, "GIT_CONFIG_NOSYSTEM" to "1")
    /** The working tree as JGit names it, with symbolic links resolved (`/private/var` on macOS). */
    private val workTree get() = repository.canonicalPath

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a split index is named, with the command that converts it`(): Unit = runBlocking {
        git.run(repository, "update-index", "--split-index")

        val index = unsupportedIndexOfStatus()

        assertEquals(UnsupportedIndex.Split(workTree, splitIndexConfigured = false), index)
        assertEquals(
            listOf(WorktreeStep.Git(workTree, listOf("update-index", "--no-split-index"))),
            index.conversionSteps(),
        )
    }

    @Test
    fun `with core splitIndex on, the conversion turns it off first`(): Unit = runBlocking {
        git.run(repository, "config", "core.splitIndex", "true")
        File(repository, "a/f2").appendText("changed\n")
        git.run(repository, "add", "a/f2")

        val index = unsupportedIndexOfStatus()

        assertEquals(UnsupportedIndex.Split(workTree, splitIndexConfigured = true), index)
        assertEquals(
            listOf(
                WorktreeStep.Git(workTree, listOf("config", "core.splitIndex", "false")),
                WorktreeStep.Git(workTree, listOf("update-index", "--no-split-index")),
            ),
            index.conversionSteps(),
        )
    }

    @Test
    fun `a sparse index is named, with the command that converts it`(): Unit = runBlocking {
        git.run(repository, "sparse-checkout", "set", "--cone", "--sparse-index", "a")

        val index = unsupportedIndexOfStatus()

        assertEquals(UnsupportedIndex.Sparse(workTree), index)
        assertEquals(
            listOf(WorktreeStep.Git(workTree, listOf("sparse-checkout", "reapply", "--no-sparse-index"))),
            index.conversionSteps(),
        )
    }

    @Test
    fun `staging, checking out and blaming give the same explanation`(): Unit = runBlocking {
        git.run(repository, "update-index", "--split-index")
        File(repository, "a/f1").appendText("changed\n")
        val expected = UnsupportedIndexError(UnsupportedIndex.Split(workTree, splitIndexConfigured = false))

        val staged = StageEntryGitAction(jgit)(gitDir, StatusEntry("a/f1", StatusType.MODIFIED, EntryType.UNSTAGED))
        val checkedOut = CheckoutBranchGitAction(jgit, testPartialCloneFetch(jgit))(gitDir, branch("other"))
        val blamed = BlameFileGitAction(jgit, testPartialCloneFetch(jgit))(gitDir, "a/f1")

        assertEquals(Either.Err(expected), staged)
        assertEquals(Either.Err(expected), checkedOut)
        assertEquals(Either.Err(expected), blamed)
    }

    @Test
    fun `a commit's file diff doesn't read the index`(): Unit = runBlocking {
        git.run(repository, "update-index", "--split-index")
        val head = git.run(repository, "rev-parse", "HEAD").trim()
        val entry = GetCommitDiffEntriesGitAction(jgit, testPartialCloneFetch(jgit))(gitDir, commit(head)).ok().single()

        val diff = formatDiff(DiffType.CommitDiff(entry)).ok()

        assertEquals("a/f1", assertInstanceOf(DiffResult.Text::class.java, diff).diffEntry.newPath)
    }

    @Test
    fun `a sparse index's commit diff doesn't read the index either`(): Unit = runBlocking {
        git.run(repository, "sparse-checkout", "set", "--cone", "--sparse-index", "b")
        val head = git.run(repository, "rev-parse", "HEAD").trim()
        val entry = GetCommitDiffEntriesGitAction(jgit, testPartialCloneFetch(jgit))(gitDir, commit(head)).ok().single()

        assertInstanceOf(DiffResult.Text::class.java, formatDiff(DiffType.CommitDiff(entry)).ok())
    }

    @Test
    fun `the split index's conversion keeps staged changes, and status works again`(): Unit = runBlocking {
        git.run(repository, "config", "core.splitIndex", "true")
        File(repository, "a/f2").appendText("staged\n")
        git.run(repository, "add", "a/f2")

        runSteps(unsupportedIndexOfStatus().conversionSteps())
        // git would split it again here, with core.splitIndex still on
        File(repository, "a/f3").appendText("staged too\n")
        git.run(repository, "add", "a/f3")

        val status = GetStatusGitAction(jgit)(gitDir, emptyList()).ok()

        assertEquals(listOf("a/f2", "a/f3"), status.staged.map { it.filePath })
        assertEquals(null, FileRepositoryBuilder.create(File(gitDir)).use { unsupportedIndex(it) })
    }

    @Test
    fun `the sparse index's conversion keeps the sparse checkout, and status works again`(): Unit = runBlocking {
        git.run(repository, "sparse-checkout", "set", "--cone", "--sparse-index", "a")

        runSteps(unsupportedIndexOfStatus().conversionSteps())

        GetStatusGitAction(jgit)(gitDir, emptyList()).ok()
        assertEquals("a", git.run(repository, "sparse-checkout", "list").trim())
        assertEquals(false, File(repository, "b").exists())
    }

    @Test
    fun `a tab shows the log, and its status says why it has none`(): Unit = runBlocking {
        git.run(repository, "update-index", "--split-index")

        TestTab(jgit, testGitCli(gitConfigVariables)).use { tab ->
            tab.openRepository(repository.path)

            val log = tab.awaitLoaded(tab.repositoryData.log)
            val status = withTimeout(10.seconds) { tab.repositoryData.status.first { it is DataState.Error } }

            assertEquals(listOf("Second commit", "Initial commit"), log.values.map { it.message.trim() })
            assertInstanceOf(UnsupportedIndexError::class.java, (status as DataState.Error).error)
        }
    }

    private suspend fun unsupportedIndexOfStatus(): UnsupportedIndex {
        val error = GetStatusGitAction(jgit)(gitDir, emptyList()).err()

        return assertInstanceOf(UnsupportedIndexError::class.java, error).index
    }

    /** Runs the steps as the Convert button does, with the git CLI. */
    private suspend fun runSteps(steps: List<WorktreeStep.Git>) {
        val run = RunGitCommandGitAction(testGitCli(gitConfigVariables))

        for (step in steps) {
            run(step.folder, step.args).ok()
        }
    }

    private fun commit(hash: String) = Commit(
        hash = hash,
        message = "",
        committer = Identity("Leaf Test", "test@example.invalid"),
        author = Identity("Leaf Test", "test@example.invalid"),
        date = 0,
        parentsHashes = git.run(repository, "rev-list", "--parents", "-n1", hash).trim().split(" ").drop(1),
    )

    private fun branch(name: String) = Branch(git.run(repository, "rev-parse", name).trim(), "refs/heads/$name", true)

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
            testPartialCloneFetch(jgit),
        )(gitDir, diffType, isDisplayFullFile = false)
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
