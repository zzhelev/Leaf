// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.discards.DiscardStore
import dev.app.leaf.domain.discards.DiscardNotices
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
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
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
import dev.app.leaf.data.git.discards.DiscardRestorer
import dev.app.leaf.data.git.discards.RestoreDiscardGitAction
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.editing.EditedFile
import dev.app.leaf.domain.editing.hunkEditOf
import dev.app.leaf.domain.errors.HunkEditError
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Done of a hunk edited in place (fork-only, `docs/fork/inline-editing.md`), with the hunks that the diff pane gets
 * ([FormatDiffGitAction]), checked byte for byte against the working file, and against what git sees in it.
 */
class WriteHunkEditGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val gitCli by lazy { testGitCli(shellVariables = isolatedGit) }
    private val jgit = testJGit()
    private val notices = DiscardNotices()
    private val writeHunkEdit by lazy { WriteHunkEditGitAction(jgit, DiscardStore(notices)) }
    private val restore by lazy { RestoreDiscardGitAction(jgit, DiscardRestorer(gitCli)) }

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

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private val lines = (1..20).map { "line $it – ünïcödé" }

    @Test
    fun `writes the typed lines in place of the hunk's, and git sees them`(): Unit = runBlocking {
        commit("notes.txt", lines.joinToString("") { "$it\n" })
        val changed = lines.toMutableList().apply { this[9] = "line 10 ✓" }
        write("notes.txt", changed.joinToString("") { "$it\n" })

        val hunk = diff("notes.txt").hunks.single()
        val edit = hunkEditOf("notes.txt", hunk)!!
        val typed = edit.text.replace("line 10 ✓", "line 10 ✓✓\nline 10½")

        val edited = assertOk(writeHunkEdit(gitDir(), edit, typed))

        val expected = changed.toMutableList().apply { this[9] = "line 10 ✓✓"; add(10, "line 10½") }
        assertBytes(expected.joinToString("") { "$it\n" }, read("notes.txt"))
        assertEquals(edit.start + 1, edited.firstLine)
        assertEquals(edit.lines.size + 1, edited.lineCount)

        val added = git.run(repository, "diff", "--no-color", "-U0", "notes.txt").lines().filter { it.startsWith("+") && !it.startsWith("+++") }
        assertEquals(listOf("+line 10 ✓✓", "+line 10½"), added)
    }

    @Test
    fun `a file with CRLF keeps them, with core autocrlf`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        commit("notes.txt", lines.joinToString("") { "$it\r\n" })
        val changed = lines.toMutableList().apply { this[4] = "line 5 ✓" }
        write("notes.txt", changed.joinToString("") { "$it\r\n" })

        val edit = hunkEditOf("notes.txt", diff("notes.txt").hunks.single())!!
        assertTrue(!edit.text.contains('\r'), "the field has no \\r")

        assertOk(writeHunkEdit(gitDir(), edit, edit.text.replace("line 5 ✓", "line 5 ✓✓")))

        val expected = lines.toMutableList().apply { this[4] = "line 5 ✓✓" }
        assertBytes(expected.joinToString("") { "$it\r\n" }, read("notes.txt"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `an executable file stays executable`(): Unit = runBlocking {
        commit("run.sh", "#!/bin/sh\necho one\n")
        File(repository, "run.sh").setExecutable(true)
        git.run(repository, "add", "run.sh")
        git.run(repository, "commit", "-m", "Make it executable")
        write("run.sh", "#!/bin/sh\necho two\n")
        val before = Files.getPosixFilePermissions(File(repository, "run.sh").toPath())

        val edit = hunkEditOf("run.sh", diff("run.sh").hunks.single())!!
        assertOk(writeHunkEdit(gitDir(), edit, "#!/bin/sh\necho three"))

        assertBytes("#!/bin/sh\necho three\n", read("run.sh"))
        assertEquals(before, Files.getPosixFilePermissions(File(repository, "run.sh").toPath()))
        assertTrue(PosixFilePermission.OWNER_EXECUTE in before)
        assertEquals("", git.run(repository, "diff", "--summary", "run.sh").trim(), "no mode change for git")
    }

    @Test
    fun `a file that changed after the diff was read isn't written`(): Unit = runBlocking {
        commit("notes.txt", lines.joinToString("") { "$it\n" })
        write("notes.txt", lines.toMutableList().apply { this[9] = "line 10 ✓" }.joinToString("") { "$it\n" })
        val edit = hunkEditOf("notes.txt", diff("notes.txt").hunks.single())!!

        val fromAnAgent = lines.toMutableList().apply { this[9] = "line 10 from an agent" }.joinToString("") { "$it\n" }
        write("notes.txt", fromAnAgent)

        assertEquals(Either.Err(HunkEditError.FileChanged("notes.txt")), writeHunkEdit(gitDir(), edit, "typed"))
        assertBytes(fromAnAgent, read("notes.txt"))
        assertEquals(null, notices.latest.value, "nothing kept, as nothing changed")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link in the file's place isn't written through`(): Unit = runBlocking {
        commit("notes.txt", lines.joinToString("") { "$it\n" })
        write("notes.txt", lines.toMutableList().apply { this[9] = "line 10 ✓" }.joinToString("") { "$it\n" })
        val edit = hunkEditOf("notes.txt", diff("notes.txt").hunks.single())!!
        val target = File(tempDir, "elsewhere.txt").apply { writeText(read("notes.txt").decodeToString()) }
        File(repository, "notes.txt").delete()
        Files.createSymbolicLink(File(repository, "notes.txt").toPath(), target.toPath())

        assertEquals(Either.Err(HunkEditError.NotAFile("notes.txt")), writeHunkEdit(gitDir(), edit, "typed"))
        assertEquals(lines.toMutableList().apply { this[9] = "line 10 ✓" }.joinToString("") { "$it\n" }, target.readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link to another file of the repository isn't written through either`(): Unit = runBlocking {
        commit("notes.txt", lines.joinToString("") { "$it\n" })
        val changed = lines.toMutableList().apply { this[9] = "line 10 ✓" }.joinToString("") { "$it\n" }
        write("notes.txt", changed)
        val edit = hunkEditOf("notes.txt", diff("notes.txt").hunks.single())!!
        write("other.txt", changed)
        File(repository, "notes.txt").delete()
        Files.createSymbolicLink(File(repository, "notes.txt").toPath(), File(repository, "other.txt").toPath())

        assertEquals(Either.Err(HunkEditError.NotAFile("notes.txt")), writeHunkEdit(gitDir(), edit, "typed"))
        assertBytes(changed, read("other.txt"))
    }

    @Test
    fun `Undo gives the lines back, and keeps later edits elsewhere in the file`(): Unit = runBlocking {
        commit("notes.txt", lines.joinToString("") { "$it\n" })
        val changed = lines.toMutableList().apply { this[1] = "line 2 ✓"; this[17] = "line 18 ✓" }
        write("notes.txt", changed.joinToString("") { "$it\n" })

        val first = diff("notes.txt").hunks.first()
        val edit = hunkEditOf("notes.txt", first)!!
        assertOk(writeHunkEdit(gitDir(), edit, edit.text.replace("line 2 ✓", "line 2 edited")))

        val record = notices.latest.value!!.record
        assertEquals(DiscardKind.EDIT, record.kind)
        assertEquals(listOf("notes.txt"), record.paths)

        // An agent changes another line afterwards
        val later = String(read("notes.txt")).replace("line 18 ✓", "line 18 from an agent")
        write("notes.txt", later)

        assertTrue(restore(gitDir(), record.id) is Either.Ok)
        assertBytes(later.replace("line 2 edited", "line 2 ✓"), read("notes.txt"))
    }

    @Test
    fun `an edit that changes nothing still leaves the file as it was`(): Unit = runBlocking {
        val content = lines.toMutableList().apply { this[9] = "line 10 ✓" }.joinToString("") { "$it\n" }
        commit("notes.txt", lines.joinToString("") { "$it\n" })
        write("notes.txt", content)
        val edit = hunkEditOf("notes.txt", diff("notes.txt").hunks.single())!!

        assertOk(writeHunkEdit(gitDir(), edit, edit.text))

        assertBytes(content, read("notes.txt"))
    }

    @Test
    fun `a new file's hunk is the whole file`(): Unit = runBlocking {
        commit("other.txt", "x\n")
        write("new.txt", "one\ntwo\n")
        val edit = hunkEditOf("new.txt", diff("new.txt", StatusType.ADDED).hunks.single())!!

        assertEquals("one\ntwo", edit.text)
        assertOk(writeHunkEdit(gitDir(), edit, "one\ntwo\nthree"))
        assertBytes("one\ntwo\nthree\n", read("new.txt"))
    }

    private fun commit(path: String, content: String) {
        write(path, content)
        git.run(repository, "add", path)
        git.run(repository, "commit", "-m", "Add $path")
    }

    private fun write(path: String, content: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeBytes(content.toByteArray(Charsets.UTF_8))
    }

    private fun read(path: String): ByteArray = File(repository, path).readBytes()

    private fun gitDir() = File(repository, ".git").absolutePath

    private suspend fun diff(path: String, statusType: StatusType = StatusType.MODIFIED): DiffResult.Text {
        val diffType = DiffType.UncommittedDiff(StatusEntry(path, statusType, EntryType.UNSTAGED), EntryType.UNSTAGED)
        val result = formatDiff(gitDir(), diffType, isDisplayFullFile = false)

        return (result as Either.Ok).value as DiffResult.Text
    }

    private fun assertOk(result: Either<EditedFile, GitError>): EditedFile {
        assertTrue(result is Either.Ok, result.toString())
        return (result as Either.Ok).value
    }

    private fun assertBytes(expected: String, actual: ByteArray) {
        assertEquals(expected, actual.toString(Charsets.UTF_8))
        assertEquals(expected.toByteArray(Charsets.UTF_8).toList(), actual.toList())
    }
}
