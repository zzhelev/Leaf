// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.conflicts.mergeWithConflicts
import dev.app.leaf.data.git.conflicts.mergeWithConflictsIn
import dev.app.leaf.data.git.conflicts.stages
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.discardable
import dev.app.leaf.domain.sorting.folderDiscard
import dev.app.leaf.domain.sorting.inFolder
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Discarding a folder from the Unstaged pane, as `StatusPane` does: its discardable entries go to the git action. Files
 * with conflicts are never discarded.
 */
class DiscardEntriesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val discard = DiscardEntriesGitAction(jgit)
    private val getStatus = GetStatusGitAction(jgit)

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `restores a folder's tracked files and leaves new files and other folders alone`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        write(repository, "src/a.kt", "a")
        write(repository, "src/deep/b.kt", "b")
        write(repository, "src/gone.kt", "gone")
        write(repository, "other/c.kt", "c")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")

        write(repository, "src/a.kt", "a changed")
        write(repository, "src/deep/b.kt", "b changed")
        File(repository, "src/gone.kt").delete()
        write(repository, "src/new.kt", "new")
        write(repository, "other/c.kt", "c changed")

        val entries = unstaged(repository).inFolder("src").discardable()

        assertEquals(setOf("src/a.kt", "src/deep/b.kt", "src/gone.kt"), entries.map { it.filePath }.toSet())

        assertInstanceOf(Either.Ok::class.java, discard(gitDir(repository), entries, staged = false))

        assertEquals("a", read(repository, "src/a.kt"))
        assertEquals("b", read(repository, "src/deep/b.kt"))
        assertEquals("gone", read(repository, "src/gone.kt"))
        assertEquals("new", read(repository, "src/new.kt"), "New files are kept")
        assertEquals("c changed", read(repository, "other/c.kt"), "Files outside the folder are kept")
        assertEquals(
            setOf("src/new.kt" to StatusType.ADDED, "other/c.kt" to StatusType.MODIFIED),
            unstaged(repository).map { it.filePath to it.statusType }.toSet(),
        )
    }

    @Test
    fun `leaves a folder's files with conflicts as they are`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        write(repository, "src/other.kt", "other")
        git.mergeWithConflictsIn(repository, "src/shared.kt")
        write(repository, "src/other.kt", "other changed")
        val withMarkers = read(repository, "src/shared.kt")

        val folder = unstaged(repository).inFolder("src").folderDiscard()

        assertEquals(listOf("src/other.kt"), folder.entries.map { it.filePath })
        assertEquals(1, folder.keptConflicted)

        assertInstanceOf(Either.Ok::class.java, discard(gitDir(repository), folder.entries, staged = false))

        assertEquals("other", read(repository, "src/other.kt"))
        assertEquals(listOf(1, 2, 3), git.stages(repository, "src/shared.kt"), "The conflict stays")
        assertEquals(withMarkers, read(repository, "src/shared.kt"))
    }

    @Test
    fun `refuses files with conflicts and changes nothing, whatever the status said`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.mergeWithConflicts(repository)
        write(repository, "README.md", "readme changed")
        val before = snapshot(repository)

        // As a stale status lists them: the conflicts came after it, so the menus offered Discard
        val conflicted = listOf("notes.txt", "kept.txt", "dropped.txt", "added.txt")
        val entries = (listOf("README.md") + conflicted).map { StatusEntry(it, StatusType.MODIFIED) }

        for (staged in listOf(false, true)) {
            assertEquals(
                Either.Err(ConflictError.CantDiscard(conflicted)),
                discard(gitDir(repository), entries, staged),
                "staged = $staged",
            )
            assertEquals(before, snapshot(repository), "staged = $staged")
        }
    }

    @Test
    fun `discarding no files changes nothing`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        write(repository, "README.md", "staged")
        git.run(repository, "add", "README.md")
        write(repository, "README.md", "unstaged")
        val before = snapshot(repository)

        for (staged in listOf(false, true)) {
            assertInstanceOf(Either.Ok::class.java, discard(gitDir(repository), emptyList(), staged))
            assertEquals(before, snapshot(repository), "staged = $staged")
        }
    }

    /** What discarding could change: the index with its stages, the status, and the files. */
    private fun snapshot(repository: File) = listOf(
        git.run(repository, "ls-files", "--stage"),
        git.run(repository, "status", "--porcelain", "--untracked-files=all"),
    ) + repository.walk().filter { it.isFile && ".git" !in it.relativeTo(repository).path.split('/') }
        .sorted()
        .map { "${it.relativeTo(repository)}: ${it.readText()}" }

    private suspend fun unstaged(repository: File): List<StatusEntry> {
        val status = getStatus(gitDir(repository))

        check(status is Either.Ok) { "Status failed: $status" }

        return status.value.unstaged
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun write(repository: File, path: String, text: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun read(repository: File, path: String) = File(repository, path).readText()
}
