// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.discards.DiscardRestorer
import dev.app.leaf.data.git.discards.DiscardStore
import dev.app.leaf.data.git.discards.RestoreDiscardGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardNotices
import dev.app.leaf.domain.editing.ChangeKind
import dev.app.leaf.domain.editing.FileForEdit
import dev.app.leaf.domain.editing.changeMarkers
import dev.app.leaf.domain.editing.fileEditOf
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkEditError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * The whole file's editor (fork-only, `docs/fork/inline-editing.md`, B2): the working file and its index version as it
 * reads them, and Save, checked byte for byte against the working file and against what git sees in it.
 */
class FileEditGitActionsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val gitCli by lazy { testGitCli(shellVariables = isolatedGit) }
    private val jgit = testJGit()
    private val notices = DiscardNotices()
    private val read by lazy { ReadFileForEditGitAction(jgit) }
    private val write by lazy { WriteFileEditGitAction(jgit, DiscardStore(notices)) }
    private val restore by lazy { RestoreDiscardGitAction(jgit, DiscardRestorer(gitCli)) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private val lines = (1..12).map { "line $it – ünïcödé" }

    @Test
    fun `reads the working file and its version in the index, which the markers compare with`(): Unit = runBlocking {
        commit("Notes.kt", lines.joinToString("") { "$it\n" })
        write("Notes.kt", lines.toMutableList().apply { this[3] = "line 4 ✓"; add("line 13") }.joinToString("") { "$it\n" })

        val file = assertOk(read(gitDir(), "Notes.kt"))
        val edit = fileEditOf("Notes.kt", file.bytes)!!

        assertEquals(lines.joinToString("\n"), file.indexText)
        assertEquals(
            listOf(ChangeKind.CHANGED to 3, ChangeKind.ADDED to 12),
            changeMarkers(file.indexText, edit.text).map { it.kind to it.start },
        )
    }

    @Test
    fun `a file with CRLF compares with its LF index version line for line, with core autocrlf`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        commit("Notes.kt", lines.joinToString("") { "$it\r\n" })
        write("Notes.kt", lines.toMutableList().apply { this[3] = "line 4 ✓" }.joinToString("") { "$it\r\n" })

        val file = assertOk(read(gitDir(), "Notes.kt"))

        assertEquals(
            listOf(ChangeKind.CHANGED to 3),
            changeMarkers(file.indexText, fileEditOf("Notes.kt", file.bytes)!!.text).map { it.kind to it.start },
        )
    }

    @Test
    fun `a file that the index doesn't have compares with nothing, and every line is added`(): Unit = runBlocking {
        commit("Other.kt", "x\n")
        write("New.kt", "one\ntwo\n")

        val file = assertOk(read(gitDir(), "New.kt"))

        assertEquals("", file.indexText)
        assertEquals(listOf(ChangeKind.ADDED to (0 until 2)), changeMarkers(file.indexText, "one\ntwo").map { it.kind to (it.start until it.end) })
    }

    @Test
    fun `Save writes the text, keeps the lines that didn't change byte for byte, and git sees it`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        commit("Notes.kt", lines.joinToString("") { "$it\r\n" })
        val edit = fileEditOf("Notes.kt", read("Notes.kt"))!!

        val written = assertOk(write(gitDir(), edit, edit.text.replace("line 4 – ünïcödé", "line 4 ✓\nline 4½")))

        val expected = lines.toMutableList().apply { this[3] = "line 4 ✓"; add(4, "line 4½") }.joinToString("") { "$it\r\n" }
        assertEquals(expected, read("Notes.kt").decodeToString())
        assertTrue(written.contentEquals(read("Notes.kt")))

        val added = git.run(repository, "diff", "--no-color", "-U0", "Notes.kt").lines().filter { it.startsWith("+") && !it.startsWith("+++") }
        assertEquals(listOf("+line 4 ✓", "+line 4½"), added)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `Save keeps an executable file executable`(): Unit = runBlocking {
        commit("run.sh", "#!/bin/sh\necho one\n")
        File(repository, "run.sh").setExecutable(true)
        val before = Files.getPosixFilePermissions(File(repository, "run.sh").toPath())
        val edit = fileEditOf("run.sh", read("run.sh"))!!

        assertOk(write(gitDir(), edit, "#!/bin/sh\necho two"))

        assertEquals("#!/bin/sh\necho two\n", read("run.sh").decodeToString())
        assertEquals(before, Files.getPosixFilePermissions(File(repository, "run.sh").toPath()))
    }

    @Test
    fun `a file that changed after it was read isn't written`(): Unit = runBlocking {
        commit("Notes.kt", lines.joinToString("") { "$it\n" })
        val edit = fileEditOf("Notes.kt", read("Notes.kt"))!!
        val fromAnAgent = lines.joinToString("") { "$it\n" } + "from an agent\n"
        write("Notes.kt", fromAnAgent)

        assertEquals(Either.Err(HunkEditError.FileChanged("Notes.kt")), write(gitDir(), edit, "typed"))
        assertEquals(fromAnAgent, read("Notes.kt").decodeToString())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link isn't read or written through`(): Unit = runBlocking {
        commit("Other.kt", "x\n")
        Files.createSymbolicLink(File(repository, "Link.kt").toPath(), File(repository, "Other.kt").toPath())

        assertEquals(Either.Err(HunkEditError.NotAFile("Link.kt")), read(gitDir(), "Link.kt"))
    }

    @Test
    fun `Undo gives the file back as it was before the Save`(): Unit = runBlocking {
        val content = lines.joinToString("") { "$it\n" }
        commit("Notes.kt", content)
        val edit = fileEditOf("Notes.kt", read("Notes.kt"))!!

        assertOk(write(gitDir(), edit, "everything replaced"))
        val record = notices.latest.value!!.record
        assertEquals(DiscardKind.EDIT, record.kind)

        assertTrue(restore(gitDir(), record.id) is Either.Ok)
        assertEquals(content, read("Notes.kt").decodeToString())
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

    private fun <T> assertOk(result: Either<T, GitError>): T {
        assertTrue(result is Either.Ok, result.toString())
        return (result as Either.Ok).value
    }

    @Suppress("unused")
    private fun FileForEdit.text() = bytes.decodeToString()
}
