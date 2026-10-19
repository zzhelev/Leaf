// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.conflicts.mergeWithConflicts
import dev.app.leaf.data.git.conflicts.stages
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.DiscardAllPlan
import dev.app.leaf.domain.sorting.allDiscard
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * "Discard all" of the Unstaged pane, as `StatusPane` does it: the plan comes from the status
 * ([allDiscard]), the dialog's box says whether the new files go, and the action restores and deletes.
 */
class DiscardAllGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val getStatus = GetStatusGitAction(jgit)
    private val discardAll = DiscardAllGitAction(getStatus, DiscardEntriesGitAction(jgit), jgit)

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `restores changed and deleted files and keeps the new ones by default`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        val plan = plan(repository)

        assertEquals(setOf("a.txt", "src/b.txt", "src/gone.txt"), plan.restored.map { it.filePath }.toSet())
        assertEquals(setOf("new.txt", "src/new/c.txt"), plan.newFiles.map { it.filePath }.toSet())

        assertOk(discard(repository, plan, deleteNewFiles = false))

        assertEquals("a", read(repository, "a.txt"))
        assertEquals("b", read(repository, "src/b.txt"))
        assertEquals("gone", read(repository, "src/gone.txt"))
        assertEquals("new", read(repository, "new.txt"))
        assertEquals("c", read(repository, "src/new/c.txt"))
        assertEquals(
            setOf("new.txt", "src/new/c.txt"),
            unstaged(repository).map { it.filePath }.toSet(),
            "Only the new files are still listed",
        )
    }

    @Test
    fun `deletes the new files too when asked, and the folders that they leave empty`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        write(repository, "src/keep/tracked-neighbour.txt", "t")
        write(repository, "build/out/deep/x.bin", "x")
        git.run(repository, "add", "src/keep/tracked-neighbour.txt")
        git.run(repository, "commit", "-m", "Neighbour")
        write(repository, "src/keep/extra.txt", "extra")

        assertOk(discard(repository, plan(repository), deleteNewFiles = true))

        assertFalse(File(repository, "new.txt").exists())
        assertFalse(File(repository, "src/new").exists(), "The folder that only had a new file is gone")
        assertFalse(File(repository, "build").exists(), "So is every folder above a new file that is now empty")
        assertFalse(File(repository, "src/keep/extra.txt").exists())
        assertEquals("t", read(repository, "src/keep/tracked-neighbour.txt"), "A tracked neighbour stays")
        assertTrue(File(repository, "src/keep").isDirectory)
        assertEquals("a", read(repository, "a.txt"))
        assertEquals("", git.run(repository, "status", "--porcelain", "--untracked-files=all"))
    }

    @Test
    fun `leaves a folder alone while an ignored file is still in it`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        write(repository, ".gitignore", "*.log\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Ignore logs")
        write(repository, "out/new.txt", "new")
        write(repository, "out/debug.log", "ignored")

        val plan = plan(repository)

        assertEquals(listOf("out/new.txt"), plan.newFiles.map { it.filePath }, "Ignored files aren't listed")

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertFalse(File(repository, "out/new.txt").exists())
        assertEquals("ignored", read(repository, "out/debug.log"), "An ignored file is never deleted")
    }

    @Test
    fun `does as git checkout and git clean`(): Unit = runBlocking {
        val leaf = repositoryWithChanges()
        val twin = repositoryWithChanges(File(tempDir, "twin"))

        assertOk(discard(leaf, plan(leaf), deleteNewFiles = true))
        git.run(twin, "checkout", "--", ".")
        git.run(twin, "clean", "-fd")

        assertEquals(tree(twin), tree(leaf))
        assertEquals(git.run(twin, "status", "--porcelain"), git.run(leaf, "status", "--porcelain"))
    }

    @Test
    fun `leaves staged changes and puts a half staged file back to its staged version`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        write(repository, "a.txt", "staged")
        git.run(repository, "add", "a.txt")
        write(repository, "a.txt", "staged, then edited")

        val plan = plan(repository)

        assertTrue("a.txt" in plan.restored.map { it.filePath })

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertEquals("staged", read(repository, "a.txt"))
        assertEquals("staged", git.run(repository, "show", ":a.txt"), "The staged version stays staged")
    }

    @Test
    fun `leaves files with conflicts as they are, and everything else is discarded`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.mergeWithConflicts(repository)
        write(repository, "README.md", "readme changed")
        write(repository, "extra.txt", "extra")
        val markers = read(repository, "notes.txt")

        val plan = plan(repository)

        assertEquals(listOf("README.md"), plan.restored.map { it.filePath })
        assertEquals(listOf("extra.txt"), plan.newFiles.map { it.filePath })
        assertEquals(
            setOf("notes.txt", "kept.txt", "dropped.txt", "added.txt"),
            plan.keptConflicted.map { it.filePath }.toSet(),
        )

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertEquals("Test repository\n", read(repository, "README.md"))
        assertFalse(File(repository, "extra.txt").exists())
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"), "The conflict stays")
        assertEquals(markers, read(repository, "notes.txt"))
    }

    @Test
    fun `a file that became conflicted since the dialog opened is left alone`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.mergeWithConflicts(repository)
        val markers = read(repository, "notes.txt")
        // The state that a dialog opened before the merge showed
        val staleStatus = listOf("notes.txt", "kept.txt", "dropped.txt", "added.txt")
            .map { StatusEntry(it, StatusType.MODIFIED) }
        val stale = DiscardAllPlan(restored = staleStatus, newFiles = emptyList(), keptConflicted = emptyList())

        assertOk(discard(repository, stale, deleteNewFiles = true))

        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
        assertEquals(markers, read(repository, "notes.txt"))
    }

    @Test
    fun `a stale status doesn't touch files that were staged, committed or created since`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        val plan = plan(repository)

        // After the dialog opened: one change staged, one committed, one new file staged, one more new file created
        git.run(repository, "add", "a.txt")
        git.run(repository, "add", "new.txt")
        git.run(repository, "commit", "-m", "Commit a and the new file")
        git.run(repository, "add", "src/b.txt")
        write(repository, "src/gone.txt", "gone, then rewritten")
        write(repository, "later.txt", "created later")

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertEquals("a changed", read(repository, "a.txt"), "Committed since")
        assertEquals("new", read(repository, "new.txt"), "A new file that was committed since")
        assertEquals("b changed", read(repository, "src/b.txt"), "Staged since")
        assertEquals("b changed", git.run(repository, "show", ":src/b.txt"))
        assertEquals("created later", read(repository, "later.txt"), "Created after the dialog opened")
        assertEquals("gone, then rewritten", read(repository, "src/gone.txt"), "Changed kind since")
        assertFalse(File(repository, "src/new").exists(), "The new file still listed went")
    }

    @Test
    fun `a new file that was staged since isn't deleted`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        write(repository, "tracked.txt", "t")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Tracked")
        write(repository, "new.txt", "new")

        val plan = plan(repository)
        git.run(repository, "add", "new.txt")

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertEquals("new", read(repository, "new.txt"))
        assertEquals("new", git.run(repository, "show", ":new.txt"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `deletes a link to a folder as a link and leaves what it points to`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        val outside = File(tempDir, "outside").apply { mkdirs() }
        write(outside, "precious.txt", "precious")
        File(repository, "link").symlinkTo(outside)

        val plan = plan(repository)

        assertTrue("link" in plan.newFiles.map { it.filePath })

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertFalse(java.nio.file.Files.exists(File(repository, "link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("precious", read(outside, "precious.txt"))
    }

    @Test
    fun `never deletes a folder with a repository of its own`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        val nested = git.initRepository(File(repository, "nested"))
        write(nested, "inner.txt", "inner")
        git.run(nested, "add", ".")
        git.run(nested, "commit", "-m", "Inner")
        // A linked worktree inside the repository, as agents' tools make them
        git.run(repository, "worktree", "add", "-b", "agent", ".agent/wt")
        write(repository, ".agent/wt/work.txt", "agent's work")

        assertOk(discard(repository, plan(repository), deleteNewFiles = true))

        assertEquals("inner", read(repository, "nested/inner.txt"))
        assertTrue(File(repository, "nested/.git").exists())
        assertEquals("agent's work", read(repository, ".agent/wt/work.txt"))
        assertTrue(File(repository, ".agent/wt/.git").exists())
        assertFalse(File(repository, "new.txt").exists(), "Its other new files still go")
    }

    @Test
    fun `a submodule that moved on stays as it is`(): Unit = runBlocking {
        val library = git.initRepository(File(tempDir, "library"))
        write(library, "lib.txt", "lib")
        git.run(library, "add", ".")
        git.run(library, "commit", "-m", "Library")

        val repository = repositoryWithChanges()
        git.run(repository, "-c", "protocol.file.allow=always", "submodule", "add", library.absolutePath, "sub")
        git.run(repository, "commit", "-m", "Add submodule")
        write(repository, "a.txt", "a changed again")
        git.run(File(repository, "sub"), "commit", "--allow-empty", "-m", "Moves on")
        write(File(repository, "sub"), "inside.txt", "untracked in the submodule")

        val plan = plan(repository)
        val before = git.run(File(repository, "sub"), "rev-parse", "HEAD")

        assertEquals(listOf("sub"), plan.keptSubmodules.map { it.filePath }, "The status flags it")
        assertFalse("sub" in plan.restored.map { it.filePath })

        assertOk(discard(repository, plan, deleteNewFiles = true))

        assertEquals("a", read(repository, "a.txt"))
        assertEquals(before, git.run(File(repository, "sub"), "rev-parse", "HEAD"), "The submodule's commit is kept")
        assertEquals("untracked in the submodule", read(File(repository, "sub"), "inside.txt"))
    }

    @Test
    fun `nothing to discard changes nothing`(): Unit = runBlocking {
        val repository = repositoryWithChanges()
        val before = tree(repository) to git.run(repository, "status", "--porcelain")
        val none = DiscardAllPlan(emptyList(), emptyList(), emptyList())

        assertOk(discard(repository, none, deleteNewFiles = true))

        assertEquals(before, tree(repository) to git.run(repository, "status", "--porcelain"))
    }

    private fun assertOk(result: Either<Unit, *>) = assertTrue(result is Either.Ok, "Discarding failed: $result")

    /** Two folders, tracked files changed and deleted, and new files in the root and in a new folder. */
    private fun repositoryWithChanges(directory: File = File(tempDir, "repo")): File {
        val repository = git.initRepository(directory)
        write(repository, "a.txt", "a")
        write(repository, "src/b.txt", "b")
        write(repository, "src/gone.txt", "gone")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")

        write(repository, "a.txt", "a changed")
        write(repository, "src/b.txt", "b changed")
        File(repository, "src/gone.txt").delete()
        write(repository, "new.txt", "new")
        write(repository, "src/new/c.txt", "c")

        return repository
    }

    private suspend fun plan(repository: File) = unstaged(repository).allDiscard()

    private suspend fun discard(repository: File, plan: DiscardAllPlan, deleteNewFiles: Boolean) =
        discardAll(
            gitDir(repository),
            restore = plan.restored,
            newFiles = if (deleteNewFiles) plan.newFiles else emptyList(),
        )

    /** The files and folders of [repository], with the files' content. */
    private fun tree(repository: File) = repository.walk()
        .filter { ".git" !in it.relativeTo(repository).path.split('/') && it != repository }
        .sorted()
        .map { "${it.relativeTo(repository)}${if (it.isDirectory) "/" else ": " + it.readText()}" }
        .toList()

    private suspend fun unstaged(repository: File): List<StatusEntry> {
        val status = getStatus(gitDir(repository))

        check(status is Either.Ok) { "Status failed: $status" }

        return status.value.unstaged
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun write(directory: File, path: String, text: String) {
        File(directory, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun read(directory: File, path: String) = File(directory, path).readText()
}
