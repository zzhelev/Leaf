// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

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
import dev.app.leaf.data.git.log.BlameFileGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.sparse.testSparseCheckoutGit
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Staged's renames (fork-only, upstream issue 347, `docs/fork/status-renames.md`): the files that the index moved, paired
 * as `git status` pairs them, in the cases of the doc's probes, and what unstaging, undoing the move, a hunk, the diff and
 * blame do with one, each against what git does in a twin repository.
 */
class StagedRenamesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val gitCli by lazy { testGitCli(shellVariables = isolatedGit) }
    private val jgit = testJGit()
    private val getStatus = GetStatusGitAction(jgit)
    private val getStagedRenames = GetStagedRenamesGitAction(jgit)

    private val getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper())
    private val getCurrentBranch = GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit)
    private val getDiffEntry = GetDiffEntryFromStatusEntryGitAction(getRepositoryState, getCurrentBranch)
    private val formatDiff by lazy {
        FormatDiffGitAction(
            FormatHunksGitAction(),
            GetDiffContentGitAction(RawFileManager(TempFilesManager(AppFilesManager()))),
            CanGenerateTextDiffGitAction(),
            GetDiffEntryFromDiffTypeGitAction(getDiffEntry),
            GetSubmodulesGitAction(jgit, JGitSubmoduleMapper()),
            TextDiffFromDiffLinesGitAction(),
            jgit,
            testPartialCloneFetch(jgit),
        )
    }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    // Which files pair

    @Test
    fun `a move with git mv is one rename, as git status shows it`(): Unit = runBlocking {
        val repository = repository("plain")
        git.run(repository, "mv", "a.txt", "b.txt")

        val entry = staged(repository).single()

        assertEquals(StatusType.RENAMED, entry.statusType)
        assertEquals(EntryType.STAGED, entry.entryType)
        assertEquals("b.txt", entry.filePath)
        assertEquals("a.txt", entry.renamedFrom?.path)
        assertEquals(100, entry.renamedFrom?.similarity)
        assertEquals(listOf("a.txt", "b.txt"), entry.paths)
        assertEquals(setOf("a.txt" to "b.txt"), gitRenames(repository))
    }

    @Test
    fun `a move with edits pairs at its similarity`(): Unit = runBlocking {
        val repository = repository("edited")
        git.run(repository, "mv", "a.txt", "b.txt")
        editLines(repository, "b.txt", 1, 2, 3)
        git.run(repository, "add", "b.txt")

        val similarity = staged(repository).single().renamedFrom?.similarity ?: 0

        assertTrue(similarity in 50..99, "The similarity is $similarity")
        assertEquals(gitRenames(repository), leafRenames(repository))
    }

    @Test
    fun `a folder moved pairs each of its files`(): Unit = runBlocking {
        val repository = repository("folder")
        for (name in listOf("one", "two", "three")) write(repository, "src/$name.txt", lines(name))
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Folder")
        git.run(repository, "mv", "src", "lib")

        assertEquals(3, leafRenames(repository).size)
        assertEquals(gitRenames(repository), leafRenames(repository))
    }

    @Test
    fun `a move made outside git, or half staged, isn't paired, as git doesn't`(): Unit = runBlocking {
        val outside = repository("outside")
        File(outside, "a.txt").renameTo(File(outside, "b.txt"))

        val newPathStaged = repository("new-path-staged")
        File(newPathStaged, "a.txt").renameTo(File(newPathStaged, "b.txt"))
        git.run(newPathStaged, "add", "b.txt")

        val oldPathStaged = repository("old-path-staged")
        File(oldPathStaged, "a.txt").copyTo(File(oldPathStaged, "b.txt"))
        git.run(oldPathStaged, "rm", "-q", "a.txt")

        for (repository in listOf(outside, newPathStaged, oldPathStaged)) {
            assertEquals(emptySet<Pair<String, String>>(), gitRenames(repository), repository.name)
            assertEquals(emptySet<Pair<String, String>>(), leafRenames(repository), repository.name)
        }
    }

    @Test
    fun `an intent-to-add file isn't paired, as it isn't staged`(): Unit = runBlocking {
        val repository = repository("intent-to-add")
        File(repository, "a.txt").renameTo(File(repository, "b.txt"))
        git.run(repository, "add", "-N", "b.txt")

        assertEquals(emptySet<Pair<String, String>>(), gitRenames(repository))
        assertEquals(emptySet<Pair<String, String>>(), leafRenames(repository))
    }

    @Test
    fun `a move whose old path git add -N added again is still one rename, as git shows it`(): Unit = runBlocking {
        val repository = repository("old-path-intent-to-add")
        git.run(repository, "mv", "a.txt", "b.txt")
        write(repository, "a.txt", "a new file\n")
        git.run(repository, "add", "-N", "a.txt")

        val rename = staged(repository).single()

        assertEquals(setOf("a.txt" to "b.txt"), gitRenames(repository))
        assertEquals(gitRenames(repository), leafRenames(repository))
        assertEquals(
            listOf(StatusEntry("a.txt", StatusType.ADDED, EntryType.UNSTAGED, isIntentToAdd = true)),
            status(repository).unstaged,
        )

        val entry = jgit.provide(gitDir(repository)) { getDiffEntry(it, isCached = true, rename) }.ok()

        assertEquals(DiffEntry.ChangeType.RENAME, entry.changeType)
        assertEquals("a.txt" to "b.txt", entry.oldPath to entry.newPath)
    }

    @Test
    fun `a staged move edited again keeps its rename, and Unstaged lists the new path`(): Unit = runBlocking {
        val repository = repository("edited-again")
        git.run(repository, "mv", "a.txt", "b.txt")
        File(repository, "b.txt").appendText("one more\n")

        assertEquals(gitRenames(repository), leafRenames(repository))
        assertEquals(
            listOf(StatusEntry("b.txt", StatusType.MODIFIED, EntryType.UNSTAGED)),
            status(repository).unstaged,
        )
    }

    @Test
    fun `a clean rename next to a conflict pairs, and the conflicted file doesn't`(): Unit = runBlocking {
        val renamedAndEdited = repository("conflict")
        git.run(renamedAndEdited, "checkout", "-q", "-b", "theirs")
        git.run(renamedAndEdited, "mv", "a.txt", "b.txt")
        editLines(renamedAndEdited, "b.txt", 5, text = "theirs")
        git.run(renamedAndEdited, "commit", "-qam", "Theirs renames")
        git.run(renamedAndEdited, "checkout", "-q", "main")
        editLines(renamedAndEdited, "a.txt", 5, text = "ours")
        git.run(renamedAndEdited, "commit", "-qam", "Ours edits")
        git.runFailing(renamedAndEdited, "merge", "theirs")

        val nextToConflict = repository("conflict-next")
        git.run(nextToConflict, "checkout", "-q", "-b", "theirs")
        git.run(nextToConflict, "mv", "c.txt", "d.txt")
        editLines(nextToConflict, "a.txt", 5, text = "theirs")
        git.run(nextToConflict, "commit", "-qam", "Theirs")
        git.run(nextToConflict, "checkout", "-q", "main")
        editLines(nextToConflict, "a.txt", 5, text = "ours")
        git.run(nextToConflict, "commit", "-qam", "Ours")
        git.runFailing(nextToConflict, "merge", "theirs")

        assertEquals(emptySet<Pair<String, String>>(), leafRenames(renamedAndEdited))
        assertEquals(gitRenames(renamedAndEdited), leafRenames(renamedAndEdited))
        assertEquals(setOf("c.txt" to "d.txt"), leafRenames(nextToConflict))
        assertEquals(gitRenames(nextToConflict), leafRenames(nextToConflict))
        assertEquals(StatusType.CONFLICTING, status(nextToConflict).unstaged.single().statusType)
    }

    @Test
    fun `a copy and a move that rewrote most of the file aren't renames`(): Unit = runBlocking {
        val copy = repository("copy")
        File(copy, "a.txt").copyTo(File(copy, "copy.txt"))
        git.run(copy, "add", "copy.txt")

        val rewritten = repository("rewritten")
        git.run(rewritten, "mv", "a.txt", "b.txt")
        write(rewritten, "b.txt", lines("other").lines().take(15).joinToString("\n") + "\n" + lines("alpha").lines().take(5).joinToString("\n"))
        git.run(rewritten, "add", "b.txt")

        for (repository in listOf(copy, rewritten)) {
            assertEquals(emptySet<Pair<String, String>>(), leafRenames(repository), repository.name)
            assertEquals(gitRenames(repository), leafRenames(repository), repository.name)
        }
    }

    @Test
    fun `two files with the same content pair by their names, as git pairs them`(): Unit = runBlocking {
        val repository = repository("twins")
        File(repository, "a.txt").copyTo(File(repository, "a2.txt"))
        git.run(repository, "add", "a2.txt")
        git.run(repository, "commit", "-qm", "Twin")
        File(repository, "x").mkdirs()
        git.run(repository, "mv", "a.txt", "x/a.txt")
        git.run(repository, "mv", "a2.txt", "x/a2.txt")

        assertEquals(setOf("a.txt" to "x/a.txt", "a2.txt" to "x/a2.txt"), leafRenames(repository))
        assertEquals(gitRenames(repository), leafRenames(repository))
    }

    @Test
    fun `status_renames and diff_renames turn it off, and status_renames wins`(): Unit = runBlocking {
        val statusOff = repository("status-off").also { git.run(it, "config", "status.renames", "false") }
        val diffOff = repository("diff-off").also { git.run(it, "config", "diff.renames", "false") }
        val statusOn = repository("status-on").also {
            git.run(it, "config", "diff.renames", "false")
            git.run(it, "config", "status.renames", "true")
        }

        for (repository in listOf(statusOff, diffOff, statusOn)) {
            git.run(repository, "mv", "a.txt", "b.txt")
            assertEquals(gitRenames(repository), leafRenames(repository), repository.name)
        }

        assertEquals(emptySet<Pair<String, String>>(), leafRenames(statusOff))
        assertEquals(emptySet<Pair<String, String>>(), leafRenames(diffOff))
        assertEquals(setOf("a.txt" to "b.txt"), leafRenames(statusOn))
    }

    @Test
    fun `an empty repository's first files aren't paired, and nothing fails`(): Unit = runBlocking {
        val repository = File(tempDir, "empty").apply { mkdirs() }
        git.run(repository, "init", "-q")
        write(repository, "a.txt", lines("alpha"))
        git.run(repository, "add", "a.txt")

        assertEquals(listOf(StatusEntry("a.txt", StatusType.ADDED, EntryType.STAGED)), staged(repository))
    }

    @Test
    fun `the pairs are kept while the index doesn't change, and found again when it does`(): Unit = runBlocking {
        val repository = repository("kept")
        git.run(repository, "mv", "a.txt", "b.txt")
        editLines(repository, "b.txt", 1)
        git.run(repository, "add", "b.txt")

        assertEquals(setOf("a.txt" to "b.txt"), leafRenames(repository))

        // A file saved in the working tree changes nothing in the index
        write(repository, "b.txt", lines("other"))
        assertEquals(setOf("a.txt" to "b.txt"), leafRenames(repository))

        // Staged, it's no longer alike
        git.run(repository, "add", "b.txt")
        assertEquals(emptySet<Pair<String, String>>(), leafRenames(repository))
        assertEquals(gitRenames(repository), leafRenames(repository))
    }

    // What the actions do with one

    @Test
    fun `unstaging a rename takes both paths out of the index, as git restore --staged does`(): Unit = runBlocking {
        val leaf = repository("unstage-leaf")
        val twin = repository("unstage-git")

        for (repository in listOf(leaf, twin)) {
            git.run(repository, "mv", "a.txt", "b.txt")
            editLines(repository, "b.txt", 2)
        }

        assertOk(UnstageEntryGitAction(jgit)(gitDir(leaf), staged(leaf).single()))
        git.run(twin, "restore", "--staged", "a.txt", "b.txt")

        assertEquals(shortStatus(twin), shortStatus(leaf))
        assertEquals(listOf(" D a.txt", "?? b.txt"), shortStatus(leaf))
    }

    @Test
    fun `unstaging the shown files takes a rename's two paths`(): Unit = runBlocking {
        val repository = repository("unstage-all")
        git.run(repository, "mv", "a.txt", "b.txt")
        editLines(repository, "c.txt", 1)
        git.run(repository, "add", "c.txt")

        val rename = staged(repository).single { it.statusType == StatusType.RENAMED }

        assertOk(UnstageAllGitAction(jgit, testSparseCheckoutGit(jgit))(gitDir(repository), listOf(rename)))

        assertEquals(listOf(" D a.txt", "M  c.txt", "?? b.txt"), shortStatus(repository))
    }

    @Test
    fun `undoing a move brings the old file back and deletes the new one, as git restore -SW from HEAD does`(): Unit =
        runBlocking {
            val leaf = repository("undo-leaf")
            val twin = repository("undo-git")

            for (repository in listOf(leaf, twin)) {
                File(repository, "src").mkdirs()
                git.run(repository, "mv", "a.txt", "src/b.txt")
                editLines(repository, "src/b.txt", 3)
                git.run(repository, "add", "src/b.txt")
                File(repository, "src/b.txt").appendText("not staged\n")
            }

            assertOk(DiscardEntriesGitAction(jgit)(gitDir(leaf), staged(leaf), staged = true))
            git.run(twin, "restore", "--source=HEAD", "--staged", "--worktree", "--", "a.txt", "src/b.txt")

            assertEquals(emptyList<String>(), shortStatus(leaf))
            assertEquals(shortStatus(twin), shortStatus(leaf))
            assertEquals(files(twin), files(leaf))
            assertEquals(lines("alpha"), File(leaf, "a.txt").readText())
            assertFalse(File(leaf, "src").exists(), "The folder that the new file left empty goes, as git's does")
        }

    @Test
    fun `the diff of a staged rename goes from the old path in HEAD to the new one in the index`(): Unit = runBlocking {
        val repository = repository("diff")
        git.run(repository, "mv", "a.txt", "b.txt")
        editLines(repository, "b.txt", 4)
        git.run(repository, "add", "b.txt")

        val rename = staged(repository).single()
        val entry = jgit.provide(gitDir(repository)) { getDiffEntry(it, isCached = true, rename) }.ok()

        assertEquals(DiffEntry.ChangeType.RENAME, entry.changeType)
        assertEquals("a.txt" to "b.txt", entry.oldPath to entry.newPath)
        assertEquals(git.run(repository, "rev-parse", "HEAD:a.txt").trim(), entry.oldId.name())
        assertEquals(git.run(repository, "rev-parse", ":b.txt").trim(), entry.newId.name())

        val diff = formatDiff(gitDir(repository), DiffType.UncommittedDiff(rename, EntryType.STAGED), false).ok()

        assertEquals(1, (diff as DiffResult.Text).hunks.size)
    }

    @Test
    fun `unstaging a hunk of a staged rename keeps the move`(): Unit = runBlocking {
        val repository = repository("hunk")
        git.run(repository, "mv", "a.txt", "b.txt")
        editLines(repository, "b.txt", 2, 18)
        git.run(repository, "add", "b.txt")

        val rename = staged(repository).single()
        val diff = formatDiff(gitDir(repository), DiffType.UncommittedDiff(rename, EntryType.STAGED), false).ok()
            as DiffResult.Text
        val applyHunk = ApplyHunkGitAction(jgit, gitCli)

        assertEquals(2, diff.hunks.size)
        assertOk(UnstageHunkGitAction(applyHunk)(gitDir(repository), diff.diffEntry, diff.hunks.first()))

        assertEquals(setOf("a.txt" to "b.txt"), gitRenames(repository))
        assertEquals(setOf("a.txt" to "b.txt"), leafRenames(repository))
        val inIndex = git.run(repository, "cat-file", "blob", ":b.txt")
        assertTrue("alpha line 2\n" in inIndex, "The first hunk left the index")
        assertFalse("alpha line 18\n" in inIndex, "The second hunk stays staged")
    }

    @Test
    fun `blame of a moved file keeps the commits of the lines that the move kept`(): Unit = runBlocking {
        val repository = repository("blame")
        git.run(repository, "mv", "a.txt", "b.txt")
        editLines(repository, "b.txt", 2)
        git.run(repository, "add", "b.txt")
        val commit = git.run(repository, "rev-parse", "HEAD").trim()
        val blame = BlameFileGitAction(jgit, testPartialCloneFetch(jgit))

        val moved = blame(gitDir(repository), "b.txt", movedFrom = "a.txt").ok()
        val plain = blame(gitDir(repository), "b.txt").ok()

        assertEquals(commit, moved.getSourceCommit(0)?.name)
        assertNull(moved.getSourceCommit(1), "The edited line isn't committed")
        assertEquals(commit, moved.getSourceCommit(19)?.name)
        assertNotNull(moved.resultContents)
        assertEquals(20, moved.resultContents.size())
        // What JGit's blame gives without the old path: nothing committed, as HEAD has no b.txt
        assertTrue((0 until 20).all { plain.getSourceCommit(it) == null })
    }

    // Helpers

    /** A repository with a.txt and c.txt, 20 lines each, committed. */
    private fun repository(name: String): File {
        val repository = git.initRepository(File(tempDir, name))
        write(repository, "a.txt", lines("alpha"))
        write(repository, "c.txt", lines("gamma"))
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-qm", "Files")

        return repository
    }

    private fun lines(word: String) = (1..20).joinToString("") { "$word line $it\n" }

    private fun editLines(repository: File, path: String, vararg numbers: Int, text: String = "changed") {
        val file = File(repository, path)
        val edited = file.readLines().mapIndexed { index, line -> if (index + 1 in numbers) "$text ${index + 1}" else line }
        file.writeText(edited.joinToString("\n", postfix = "\n"))
    }

    private fun write(repository: File, path: String, content: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private suspend fun status(repository: File) = getStatus(gitDir(repository)).ok()

    private suspend fun staged(repository: File): List<StatusEntry> =
        getStagedRenames(gitDir(repository), status(repository).staged).ok()

    private suspend fun leafRenames(repository: File) = staged(repository)
        .filter { it.statusType == StatusType.RENAMED }
        .map { it.renamedFrom?.path to it.filePath }
        .toSet()

    /** The renames in the index's column of `git status --porcelain=v2`: `2 R. … R100 new<TAB>old`. */
    private fun gitRenames(repository: File) = git.run(repository, "status", "--porcelain=v2")
        .lines()
        .filter { it.startsWith("2 R") }
        .map { line ->
            val (head, oldPath) = line.split('\t')
            oldPath to head.substringAfterLast(' ')
        }
        .toSet()

    private fun shortStatus(repository: File) = git.run(repository, "status", "--short").lines().filter { it.isNotBlank() }

    private fun files(repository: File) = repository.walk()
        .onEnter { it.name != ".git" }
        .filter { it.isFile }
        .associate { it.relativeTo(repository).path to it.readText() }

    private fun <T> Either<T, *>.ok(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> throw AssertionError("Failed: $error")
    }

    private fun assertOk(result: Either<Unit, *>) = assertTrue(result is Either.Ok, "Failed: $result")
}
