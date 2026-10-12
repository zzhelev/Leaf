// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.WorkingFileState
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/** Save: the resolver's result written into the working file, as git's merges write it. */
class WriteConflictResultGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val getConflictedFile = GetConflictedFileGitAction(jgit)
    private val writeConflictResult = WriteConflictResultGitAction(jgit)

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val notes by lazy { File(repository, "notes.txt") }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    /** With nothing resolved, Save writes what git wrote, labels included: so the labels are git's. */
    @ParameterizedTest
    @CsvSource(
        "merge, merge", "merge, diff3",
        "rebase, merge", "rebase, diff3",
        "cherry-pick, merge", "cherry-pick, diff3",
        "revert, merge", "revert, diff3",
        "stash, merge", "stash, diff3",
    )
    fun `with every conflict open, the file is written as git wrote it`(operation: String, style: String): Unit =
        runBlocking {
            git.run(repository, "config", "merge.conflictStyle", style)
            stopAtConflicts(operation)
            val written = notes.readBytes()

            val file = load()
            // So that the file's bytes are the resolver's
            notes.writeText("replaced\n")
            assertWritten(write(file, file.merge, overwriteChanges = true))

            assertEquals(written.decodeToString(), notes.readText())
        }

    @Test
    fun `a resolution is written in place, so the file stays executable`(): Unit = runBlocking {
        git.mergeSides(repository) { side ->
            notes.writeText("$side\n")
            notes.setExecutable(true)
        }

        val file = load()

        assertWritten(write(file, file.merge.resolvingAll(ConflictResolution.Ours)))
        assertEquals("main\n", notes.readText())
        assertTrue(notes.canExecute())
    }

    @Test
    fun `a deleted executable file is written again as executable`(): Unit = runBlocking {
        git.mergeSides(repository) { side ->
            notes.writeText("$side\n")
            notes.setExecutable(true)
        }
        notes.delete()

        val file = load()

        assertWritten(write(file, file.merge.resolvingAll(ConflictResolution.Theirs)))
        assertEquals("feature\n", notes.readText())
        assertTrue(notes.canExecute())
    }

    @Test
    fun `markers have the length of the conflict-marker-size attribute, as git writes them`(): Unit = runBlocking {
        File(repository, ".gitattributes").writeText("*.txt conflict-marker-size=10\n")
        git.mergeWithConflictsIn(repository, "notes.txt")
        val written = notes.readText()

        val file = load()
        notes.writeText("replaced\n")

        assertWritten(write(file, file.merge, overwriteChanges = true))
        assertEquals(written, notes.readText())
        assertTrue(written.startsWith("<<<<<<<<<< HEAD\n"))
    }

    @Test
    fun `line endings are converted as a checkout converts them`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        git.mergeWithConflictsIn(repository, "notes.txt")
        val written = notes.readBytes()

        val file = load()

        // The index has LF, the working tree CRLF
        assertEquals("main\n", file.merge.ours.rawContent.decodeToString())
        assertEquals(WorkingFileState.MATCHES, file.text.workingFile.state)

        assertWritten(write(file, file.merge))
        assertArrayEquals(written, notes.readBytes())

        val again = load()
        assertWritten(write(again, again.merge.resolvingAll(ConflictResolution.Theirs)))
        assertEquals("feature\r\n", notes.readText())
    }

    @Test
    fun `a file whose conflict was resolved elsewhere isn't written`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        val file = load()
        notes.writeText("resolved\n")
        git.run(repository, "add", "notes.txt")

        assertEquals(
            Either.Err(ConflictError.NotConflicted(listOf("notes.txt"))),
            write(file, file.merge.resolvingAll(ConflictResolution.Ours)),
        )
        assertEquals("resolved\n", notes.readText())
    }

    @Test
    fun `a file whose versions changed since it was loaded isn't written`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        val file = load()

        // The same conflict again, with another version on feature
        git.run(repository, "merge", "--abort")
        git.run(repository, "switch", "feature")
        notes.writeText("feature again\n")
        git.run(repository, "commit", "-am", "Feature again")
        git.run(repository, "switch", "main")
        git.runFailing(repository, "merge", "feature")
        val written = notes.readText()

        assertEquals(
            Either.Err(ConflictError.StagesChanged("notes.txt")),
            write(file, file.merge.resolvingAll(ConflictResolution.Ours), overwriteChanges = true),
        )
        assertEquals(written, notes.readText())
    }

    @Test
    fun `a working file that changed since it was loaded is only written when the user keeps the result`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "notes.txt")
            val file = load()
            notes.writeText("an agent's resolution\n")
            val resolved = file.merge.resolvingAll(ConflictResolution.Theirs)

            assertEquals(Either.Err(ConflictError.WorkingFileChanged("notes.txt")), write(file, resolved))
            assertEquals("an agent's resolution\n", notes.readText())

            assertWritten(write(file, resolved, overwriteChanges = true))
            assertEquals("feature\n", notes.readText())
        }

    @Test
    fun `a missing working file is written again`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        notes.delete()
        val file = load()

        assertEquals(WorkingFileState.MISSING, file.text.workingFile.state)
        assertWritten(write(file, file.merge.resolvingAll(ConflictResolution.OursThenTheirs)))
        assertEquals("main\nfeature\n", notes.readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link in the file's place isn't written through`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        val outside = File(tempDir, "outside.txt").apply { writeText("outside\n") }
        notes.delete()
        notes.symlinkTo(outside)
        val file = load()

        assertEquals(
            Either.Err(ConflictError.NotAFile("notes.txt")),
            write(file, file.merge.resolvingAll(ConflictResolution.Ours), overwriteChanges = true),
        )
        assertEquals("outside\n", outside.readText())
    }

    private fun stopAtConflicts(operation: String) {
        when (operation) {
            "merge" -> git.mergeWithConflictsIn(repository, "notes.txt")
            "rebase" -> {
                git.mergeWithConflictsIn(repository, "notes.txt")
                git.run(repository, "merge", "--abort")
                git.run(repository, "switch", "feature")
                git.runFailing(repository, "rebase", "main")
            }

            "cherry-pick" -> {
                git.mergeWithConflictsIn(repository, "notes.txt")
                git.run(repository, "merge", "--abort")
                git.runFailing(repository, "cherry-pick", "feature")
            }

            "revert" -> {
                git.mergeWithConflictsIn(repository, "notes.txt")
                git.run(repository, "merge", "--abort")
                notes.writeText("main again\n")
                git.run(repository, "commit", "-am", "Main again")
                git.runFailing(repository, "revert", "--no-edit", "HEAD~1")
            }

            "stash" -> {
                notes.writeText("base\n")
                git.run(repository, "add", ".")
                git.run(repository, "commit", "-m", "Base")
                notes.writeText("stashed\n")
                git.run(repository, "stash")
                notes.writeText("main\n")
                git.run(repository, "commit", "-am", "Main")
                git.runFailing(repository, "stash", "pop")
            }
        }
    }

    private suspend fun load(): ConflictedFile {
        val result = getConflictedFile(File(repository, ".git").path, "notes.txt")

        return assertInstanceOf(Either.Ok::class.java, result).value as ConflictedFile
    }

    /** Checks that the write succeeded and returned the id of the bytes now in the file. */
    private fun assertWritten(result: Either<String, GitError>) {
        assertEquals(Either.Ok(blobId(notes.readBytes())), result)
    }

    private suspend fun write(file: ConflictedFile, merge: TextMerge, overwriteChanges: Boolean = false) =
        writeConflictResult(File(repository, ".git").path, file, merge, overwriteChanges)
}

private val ConflictedFile.text get() = content as ConflictContent.Text

private val ConflictedFile.merge get() = text.merge

internal fun TextMerge.resolvingAll(resolution: ConflictResolution) =
    copy(chunks = chunks.map { if (it is Chunk.Conflict) it.copy(resolution = resolution) else it })
