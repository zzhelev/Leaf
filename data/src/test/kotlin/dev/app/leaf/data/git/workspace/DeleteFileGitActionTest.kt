// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.DeleteFileError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.NestedRepository
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
import java.nio.file.LinkOption
import kotlin.io.path.notExists

/**
 * "Delete" in the Status pane. Kotlin's deleteRecursively, which it used before, follows symbolic links: it emptied the
 * folder that a link pointed to, even outside the repository.
 */
@DisabledOnOs(OS.WINDOWS) // Creating symbolic links needs Developer Mode or admin rights on Windows
class DeleteFileGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val delete = DeleteFileGitAction(jgit)
    private val getStatus = GetStatusGitAction(jgit)

    /** A folder outside the repository, which links in the repository point to. */
    private val outside by lazy {
        File(tempDir, "outside").apply { mkdirs() }.also { File(it, "keep.txt").writeText("keep") }
    }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `deleting a link to a folder removes the link, not the folder's files`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val link = File(repository, "link").symlinkTo(outside)

        val entry = unstaged(repository).single()

        assertEquals("link" to StatusType.ADDED, entry.filePath to entry.statusType)

        assertInstanceOf(Either.Ok::class.java, delete(gitDir(repository), entry.filePath))

        assertTrue(link.toPath().notExists(LinkOption.NOFOLLOW_LINKS), "The link is deleted")
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertTrue(unstaged(repository).isEmpty())
    }

    @Test
    fun `deleting a folder removes the links in it, not the files they point to`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        // The Status pane lists an untracked repository as one entry, so deleting it deletes a folder
        val nested = git.initRepository(File(repository, "nested"))
        File(nested, "deep/link").symlinkTo(outside)

        val entry = unstaged(repository).single()

        assertEquals("nested" to StatusType.ADDED, entry.filePath to entry.statusType)

        assertInstanceOf(Either.Ok::class.java, delete(gitDir(repository), entry.filePath))

        assertTrue(nested.toPath().notExists(LinkOption.NOFOLLOW_LINKS), "The folder is deleted")
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertTrue(unstaged(repository).isEmpty())
    }

    @Test
    fun `a linked worktree inside the repository is refused and left whole`(): Unit = runBlocking {
        val repository = committedRepository()
        git.run(repository, "worktree", "add", "-b", "agent", ".agent/wt")
        File(repository, ".agent/wt/work.txt").writeText("uncommitted work")

        val entry = unstaged(repository).single()

        assertEquals(".agent/wt", entry.filePath)
        assertEquals(NestedRepository.LINKED_WORKTREE, entry.nestedRepository)

        val result = delete(gitDir(repository), entry.filePath)

        assertEquals(DeleteFileError.LinkedWorktree(".agent/wt"), (result as Either.Err).error)
        assertEquals("uncommitted work", File(repository, ".agent/wt/work.txt").readText())
        assertTrue(File(repository, ".agent/wt/.git").isFile)
        assertTrue(git.run(repository, "worktree", "list").contains(".agent/wt"))
    }

    @Test
    fun `a nested clone is flagged as a repository and still deleted`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val clone = git.initRepository(File(repository, "vendor/clone"))
        File(clone, "a.txt").writeText("a")

        val entry = unstaged(repository).single { it.filePath == "vendor/clone" }

        assertEquals(NestedRepository.REPOSITORY, entry.nestedRepository)
        assertInstanceOf(Either.Ok::class.java, delete(gitDir(repository), entry.filePath))
        assertTrue(clone.toPath().notExists(LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a worktree of another repository is a repository, not a linked worktree`(): Unit = runBlocking {
        val repository = committedRepository()
        val other = committedRepository(File(tempDir, "other"))
        git.run(other, "worktree", "add", "-b", "elsewhere", File(repository, "wt").absolutePath)

        val entry = unstaged(repository).single { it.filePath == "wt" }

        assertEquals(NestedRepository.REPOSITORY, entry.nestedRepository)
    }

    @Test
    fun `new files, in a folder or not, are not flagged and still delete`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        File(repository, "newdir/sub").mkdirs()
        File(repository, "newdir/sub/x.txt").writeText("x")
        File(repository, "plain.txt").writeText("p")

        val entries = unstaged(repository)

        assertEquals(listOf("newdir/sub/x.txt", "plain.txt"), entries.map { it.filePath })
        assertTrue(entries.all { it.nestedRepository == null })

        for (entry in entries) {
            assertInstanceOf(Either.Ok::class.java, delete(gitDir(repository), entry.filePath))
        }

        assertTrue(unstaged(repository).isEmpty())
    }

    @Test
    fun `a linked worktree is refused even when the status that listed it is stale`(): Unit = runBlocking {
        val repository = committedRepository()
        git.run(repository, "worktree", "add", "-b", "agent", "wt")

        // Asked about by path alone, as a stale selection does
        val result = delete(gitDir(repository), "wt")

        assertEquals(DeleteFileError.LinkedWorktree("wt"), (result as Either.Err).error)
        assertTrue(File(repository, "wt/.git").isFile)
    }

    @Test
    fun `a file that's already gone counts as deleted`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))

        assertInstanceOf(Either.Ok::class.java, delete(gitDir(repository), "gone.txt"))
    }

    @Test
    fun `a file that can't be deleted is reported`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val folder = File(repository, "locked")
        val file = File(folder, "file.txt").apply { parentFile.mkdirs() }.also { it.writeText("file") }

        // A file can't be removed from a folder without write permission
        check(folder.setWritable(false))

        try {
            val result = delete(gitDir(repository), "locked/file.txt")

            assertInstanceOf(Either.Err::class.java, result)
            assertEquals("Delete file recursively failed", ((result as Either.Err).error as GenericError).message)
            assertTrue(file.exists())
        } finally {
            folder.setWritable(true)
        }
    }

    private fun committedRepository(folder: File = File(tempDir, "repo")): File {
        val repository = git.initRepository(folder)
        File(repository, "a.txt").writeText("a")
        git.run(repository, "add", "a.txt")
        git.run(repository, "commit", "-m", "init")

        return repository
    }

    private suspend fun unstaged(repository: File): List<StatusEntry> {
        val status = getStatus(gitDir(repository))

        check(status is Either.Ok) { "Status failed: $status" }

        return status.value.unstaged
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath
}
