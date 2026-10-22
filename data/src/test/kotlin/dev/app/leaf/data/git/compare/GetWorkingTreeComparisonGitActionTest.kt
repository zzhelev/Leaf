// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

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
import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonMode.ALL
import dev.app.leaf.domain.models.ComparisonMode.SINCE_SPLIT
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.LineType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A branch compared with the working tree (`docs/fork/compare.md`, 7.2): `feature` checked out, a commit ahead of where
 * it left `main`, which has a commit of its own, and on disk an edit, a staged new file, untracked files (one in a new
 * folder), a deletion, a moved and edited file, and an ignored folder. Each comparison is checked against git: on a
 * twin copy, `git add -A` then `git diff --cached -M50% --name-status` from A or the merge base, and
 * `git ls-files --others --exclude-standard` for the untracked files.
 */
class GetWorkingTreeComparisonGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val compare by lazy {
        GetWorkingTreeComparisonGitAction(jgit, JGitCommitMapper(JGitIdentityMapper()), testPartialCloneFetch(jgit))
    }

    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir by lazy { File(repo, ".git").path }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        repo.mkdirs()
        git.run(repo, "init", "-q")
        write(".gitignore", "build/\n*.log\n")
        write("a.txt", "a1\na2\na3\n")
        write("src/Parser.kt", (1..30).joinToString("\n") { "line $it" } + "\n")
        write("gone.txt", "will be deleted\n")
        write("run.sh", "echo hi\n")
        commit("Initial commit")

        git.run(repo, "checkout", "-q", "-b", "feature")
        write("feature.txt", "feature\n")
        commit("Feature work")

        git.run(repo, "checkout", "-q", "main")
        write("main.txt", "main moved on\n")
        commit("Main's own")
        git.run(repo, "checkout", "-q", "feature")

        // The working tree
        write("a.txt", "a1\nchanged\na3\n")
        write("staged.txt", "staged new file\n")
        git.run(repo, "add", "staged.txt")
        write("untracked.txt", "new\n")
        write("newdir/inside.txt", "in a new folder\n")
        File(repo, "gone.txt").delete()
        File(repo, "src/Parser.kt").delete()
        write("src/parse/Parser.kt", (1..30).joinToString("\n") { if (it == 5) "line five" else "line $it" } + "\n")
        write("build/out.txt", "ignored\n")
        write("debug.log", "ignored too\n")
    }

    @AfterEach
    fun restoreReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `all differences from main are git diff main of everything on disk, untracked files included`(): Unit =
        runBlocking {
            val comparison = compareWith("main", ALL)

            assertSameAsGit(comparison, from = "main")
            assertEquals(setOf("untracked.txt", "newdir/inside.txt", "src/parse/Parser.kt"), comparison.untrackedPaths)
            // main's own file shows as if the working tree had undone it, and nothing ignored is there
            assertTrue(comparison.changes.any { it.oldPath == "main.txt" && it.changeType == ChangeType.DELETE })
            assertTrue(comparison.changes.none { it.newPath.startsWith("build/") || it.newPath.endsWith(".log") })
        }

    @Test
    fun `since they split is from the merge base, and the commits are main's and HEAD's own`(): Unit = runBlocking {
        val comparison = compareWith("main", SINCE_SPLIT)

        assertSameAsGit(comparison, from = git.run(repo, "merge-base", "main", "HEAD").trim())
        assertEquals(ComparisonRelation.DIVERGED, comparison.relation)
        assertEquals(listOf("Feature work"), comparison.commitsOnlyInB.map { it.message.trim() })
        assertEquals(listOf("Main's own"), comparison.commitsOnlyInA.map { it.message.trim() })
        assertTrue(comparison.changes.none { it.oldPath == "main.txt" })
    }

    @Test
    fun `against HEAD it's the uncommitted changes, and a moved file that was edited is a rename`(): Unit = runBlocking {
        val comparison = compareWith("HEAD", ALL)

        assertSameAsGit(comparison, from = "HEAD")
        assertEquals(ComparisonRelation.SAME, comparison.relation)

        val moved = comparison.changes.single { it.changeType == ChangeType.RENAME }
        assertEquals("src/Parser.kt", moved.oldPath)
        assertEquals("src/parse/Parser.kt", moved.newPath)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a mode change on disk is a change, as git sees it`(): Unit = runBlocking {
        File(repo, "run.sh").setExecutable(true)

        val comparison = compareWith("HEAD", ALL)

        assertSameAsGit(comparison, from = "HEAD")
        assertTrue(comparison.changes.any { it.newPath == "run.sh" })
    }

    @Test
    fun `a file's diff reads the old version by its blob and the new one from disk`(): Unit = runBlocking {
        val entry = compareWith("HEAD", ALL).changes.single { it.newPath == "a.txt" }

        val diff = (formatDiff()(gitDir, DiffType.CommitDiff(entry, readsWorkingTree = true), false, DiffWhitespaceMode.SHOW_ALL) as Either.Ok).value
        val lines = (diff as DiffResult.Text).hunks.flatMap { it.lines }

        assertTrue(lines.any { it.lineType == LineType.REMOVED && it.text.trim() == "a2" }, lines.toString())
        assertTrue(lines.any { it.lineType == LineType.ADDED && it.text.trim() == "changed" }, lines.toString())

        val untracked = compareWith("HEAD", ALL).changes.single { it.newPath == "newdir/inside.txt" }
        val added = (formatDiff()(gitDir, DiffType.CommitDiff(untracked, readsWorkingTree = true), false, DiffWhitespaceMode.SHOW_ALL) as Either.Ok).value

        assertEquals(listOf("in a new folder"), (added as DiffResult.Text).hunks.flatMap { it.lines }.map { it.text.trim() })
    }

    @Test
    fun `an index that JGit can't read says so, as the status does`(): Unit = runBlocking {
        git.run(repo, "update-index", "--split-index")

        val result = compare(gitDir, "main", ALL)

        assertInstanceOf(Either.Err::class.java, result)
        assertInstanceOf(UnsupportedIndexError::class.java, (result as Either.Err).error)
    }

    private suspend fun compareWith(a: String, mode: ComparisonMode): Comparison =
        (compare(gitDir, a, mode) as Either.Ok).value

    /**
     * The comparison's files are what git lists from [from] to everything on disk: on a twin copy, untracked files
     * added, so that `git diff --cached` sees them, with git's rename threshold.
     */
    private fun assertSameAsGit(comparison: Comparison, from: String) {
        val twin = File(tempDir, "twin-${System.nanoTime()}")
        copyKeepingModes(repo, twin)
        git.run(twin, "add", "-A")
        val expected = git.run(twin, "diff", "--cached", "-M50%", "--name-status", from).lines()
            .filter { it.isNotBlank() }
            .map { line -> line.split('\t').let { parts -> parts[0].take(1) + " " + parts.drop(1).joinToString(" -> ") } }
            .sorted()

        assertEquals(expected, comparison.changes.map { it.statusLine() }.sorted())

        // Every untracked file that git lists is a new file on this side, so they're all in the comparison
        val untracked = git.run(repo, "ls-files", "--others", "--exclude-standard").lines().filter { it.isNotBlank() }.toSet()
        assertEquals(untracked, comparison.untrackedPaths)
    }

    /** A copy with the files' modes, which Kotlin's `copyRecursively` drops: a mode change must reach the twin. */
    private fun copyKeepingModes(from: File, to: File) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            from.copyRecursively(to)
        } else {
            val process = ProcessBuilder("cp", "-Rp", from.path, to.path).redirectErrorStream(true).start()
            check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
        }
    }

    private fun DiffEntry.statusLine(): String = when (changeType) {
        ChangeType.ADD -> "A $newPath"
        ChangeType.DELETE -> "D $oldPath"
        ChangeType.MODIFY -> "M $newPath"
        ChangeType.RENAME -> "R $oldPath -> $newPath"
        ChangeType.COPY -> "C $oldPath -> $newPath"
        null -> "?"
    }

    private fun write(path: String, text: String) {
        val file = File(repo, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun commit(message: String) {
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", message)
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
