// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.repository.ResetRepositoryStateGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The diff of a file with merge conflicts, as the diff pane gets it ([FormatDiffGitAction]): the working tree file
 * against ours (stage 2 in the index), never against the common ancestor (stage 1).
 */
class ConflictDiffTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()

    private val formatDiff by lazy {
        val getCurrentBranch = GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit)
        val getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper())

        FormatDiffGitAction(
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

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    /** The working file of a conflict in notes.txt, compared to ours: main's line, with what the merge added. */
    private val conflictAgainstOurs = listOf(
        "+<<<<<<<",
        " main's line",
        "+=======",
        "+feature's line",
        "+>>>>>>>",
    )

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a merge conflict shows the working file against ours, not the common ancestor`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        git.runFailing(repository, "merge", "feature")

        val diff = conflictDiff("notes.txt")

        assertEquals(ChangeType.MODIFY, diff.diffEntry.changeType)
        assertEquals(stageId(2, "notes.txt"), diff.diffEntry.oldId.name())
        assertEquals(conflictAgainstOurs, diff.lines())
    }

    @Test
    fun `a merge that Leaf ran with JGit shows the working file against ours`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        val feature = Branch(git.run(repository, "rev-parse", "feature").trim(), "refs/heads/feature", isLocal = true)

        assertEquals(Either.Ok(true), MergeBranchGitAction(jgit, testPartialCloneFetch(jgit))(gitDir(), feature, fastForward = true))

        val diff = conflictDiff("notes.txt")

        assertEquals(stageId(2, "notes.txt"), diff.diffEntry.oldId.name())
        assertEquals(conflictAgainstOurs, diff.lines())
    }

    @Test
    fun `a rebase conflict shows the working file against the commit it rebases onto`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        git.run(repository, "checkout", "feature")
        git.runFailing(repository, "rebase", "main")

        val diff = conflictDiff("notes.txt")

        assertEquals(git.run(repository, "rev-parse", "main:notes.txt").trim(), diff.diffEntry.oldId.name())
        assertEquals(conflictAgainstOurs, diff.lines())
    }

    @Test
    fun `a cherry-pick conflict shows the working file against ours`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        git.runFailing(repository, "cherry-pick", "feature")

        val diff = conflictDiff("notes.txt")

        assertEquals(stageId(2, "notes.txt"), diff.diffEntry.oldId.name())
        assertEquals(conflictAgainstOurs, diff.lines())
    }

    @Test
    fun `a file that we deleted and they changed is all added`(): Unit = runBlocking {
        branchesChangingNotes(main = null, feature = "feature's line\n")
        git.runFailing(repository, "merge", "feature")

        val diff = conflictDiff("notes.txt")

        assertEquals(ChangeType.ADD, diff.diffEntry.changeType)
        assertEquals(listOf("+feature's line"), diff.lines())
    }

    @Test
    fun `a file that they deleted and we changed is ours unchanged, and still has a diff`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = null)
        git.runFailing(repository, "merge", "feature")

        val diff = conflictDiff("notes.txt")

        assertEquals(ChangeType.MODIFY, diff.diffEntry.changeType)
        assertEquals(stageId(2, "notes.txt"), diff.diffEntry.oldId.name())
        assertEquals(emptyList<String>(), diff.lines())
    }

    @Test
    fun `a conflicted file deleted from the working tree shows ours removed`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        git.runFailing(repository, "merge", "feature")
        File(repository, "notes.txt").delete()

        val diff = conflictDiff("notes.txt")

        assertEquals(ChangeType.DELETE, diff.diffEntry.changeType)
        assertEquals(listOf("-main's line"), diff.lines())
    }

    @Test
    fun `a conflicted file put back to the common ancestor shows ours replaced by it`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        git.runFailing(repository, "merge", "feature")
        File(repository, "notes.txt").writeText("base\n")

        val diff = conflictDiff("notes.txt")

        assertEquals(listOf("-main's line", "+base"), diff.lines())
    }

    @Test
    fun `a file whose merge was aborted has no diff left`(): Unit = runBlocking {
        branchesChangingNotes(main = "main's line\n", feature = "feature's line\n")
        git.runFailing(repository, "merge", "feature")

        assertEquals(Either.Ok(Unit), ResetRepositoryStateGitAction(jgit)(gitDir()))

        val error = assertInstanceOf(GenericError::class.java, (format("notes.txt") as Either.Err).error)
        assertInstanceOf(MissingDiffEntryException::class.java, error.exception)
    }

    /** Commits notes.txt as "base", then changes it on `feature` and on `main`, a null content deleting it. */
    private fun branchesChangingNotes(main: String?, feature: String?) {
        commit("notes.txt", "base\n")
        git.run(repository, "checkout", "-b", "feature")
        commit("notes.txt", feature)
        git.run(repository, "checkout", "main")
        commit("notes.txt", main)
    }

    private fun commit(path: String, content: String?) {
        if (content == null) {
            git.run(repository, "rm", path)
        } else {
            File(repository, path).writeText(content)
            git.run(repository, "add", path)
        }

        git.run(repository, "commit", "-m", "Change $path")
    }

    private fun gitDir() = File(repository, ".git").absolutePath

    private fun stageId(stage: Int, path: String) = git.run(repository, "rev-parse", ":$stage:$path").trim()

    private suspend fun format(path: String): Either<DiffResult, GitError> {
        val entry = StatusEntry(path, StatusType.CONFLICTING, EntryType.UNSTAGED)

        return formatDiff(gitDir(), DiffType.UncommittedDiff(entry, EntryType.UNSTAGED), isDisplayFullFile = false)
    }

    private suspend fun conflictDiff(path: String): DiffResult.Text {
        val result = format(path)

        return assertInstanceOf(DiffResult.Text::class.java, (result as? Either.Ok)?.value, "$result")
    }

    /** The diff's lines as `+added`, `-removed` and ` context`, with conflict markers shortened to the marker. */
    private fun DiffResult.Text.lines(): List<String> = hunks.flatMap { it.lines }.map { line ->
        val prefix = when (line.lineType) {
            LineType.ADDED -> "+"
            LineType.REMOVED -> "-"
            LineType.CONTEXT -> " "
        }

        prefix + line.text.trimEnd().replace(Regex("^(<<<<<<<|>>>>>>>) .*"), "$1")
    }
}
