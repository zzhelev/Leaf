// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.discardable
import dev.app.leaf.domain.sorting.inFolder
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.inject.Provider

/** Discarding a folder from the Unstaged pane, as `StatusPane` does: its discardable entries go to the git action. */
class DiscardEntriesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = JGit(Provider { error("Only used on Windows") })
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
    fun `a conflicted file goes back to the current branch's version`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        write(repository, "src/shared.kt", "base")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "checkout", "-b", "other")
        write(repository, "src/shared.kt", "theirs")
        git.run(repository, "commit", "-am", "Theirs")
        git.run(repository, "checkout", "main")
        write(repository, "src/shared.kt", "ours")
        git.run(repository, "commit", "-am", "Ours")
        runCatching { git.run(repository, "merge", "other") } // Stops with a conflict

        val entries = unstaged(repository).inFolder("src").discardable()

        assertTrue(entries.any { it.statusType == StatusType.CONFLICTING }, "Expected a conflict: $entries")

        assertInstanceOf(Either.Ok::class.java, discard(gitDir(repository), entries, staged = false))

        assertEquals("ours", read(repository, "src/shared.kt"))
        assertTrue(unstaged(repository).none { it.filePath == "src/shared.kt" })
    }

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
