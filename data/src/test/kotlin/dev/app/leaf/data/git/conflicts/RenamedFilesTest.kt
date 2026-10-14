// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.WorkingFileState
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Files that each side renamed differently: found in the index, opened as one, and resolved by the name picked. */
class RenamedFilesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val jgit = testJGit()
    private val resolve by lazy { ResolveRenamedFileGitAction(jgit, testGitCli(isolatedGit)) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    private val lines = (1..8).joinToString("") { "line $it\n" }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a file renamed on both sides is found, and any of its paths opens it under its old name`(): Unit =
        runBlocking {
            renameOnBothSides(ours = { it.replace("line 1", "line one") }, theirs = { it.replace("line 8", "line eight") })
            val renamed = RenamedFile("notes/a.txt", "notes/b.txt", "notes/c.txt")

            assertEquals(listOf(renamed), Git.open(repository).use { renamedFiles(it.repository, it.repository.readDirCache()) })

            for (path in renamed.paths) {
                val file = load(path)
                val content = assertInstanceOf(ConflictContent.Renamed::class.java, file.content)

                assertEquals("notes/a.txt", file.path)
                assertEquals(renamed, content.file)
                assertFalse(content.contentConflicts)
                assertEquals(git.run(repository, "rev-parse", "feature").trim(), content.theirsCommit)
                assertEquals(FileConflict(ConflictVersion.FILE, ConflictVersion.FILE), file.conflict)
                assertEquals(git.run(repository, "rev-parse", ":1:notes/a.txt").trim(), file.base?.objectId)
                assertEquals(git.run(repository, "rev-parse", ":2:notes/b.txt").trim(), file.ours?.objectId)
                assertEquals(VersionPreview.File(size = File(repository, "notes/b.txt").length()), file.preview?.ours)
            }

            // git merged both sides' changes into both names
            assertEquals(File(repository, "notes/b.txt").readText(), File(repository, "notes/c.txt").readText())
            assertTrue("line one" in File(repository, "notes/b.txt").readText())
            assertTrue("line eight" in File(repository, "notes/b.txt").readText())
        }

    @Test
    fun `two files renamed on both sides are told apart by their content`(): Unit = runBlocking {
        val other = (1..8).joinToString("") { "other $it\n" }
        git.mergeSides(repository) { side ->
            FileUtils.delete(File(repository, "x"), FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
            val (first, second) = when (side) {
                "base" -> "a.txt" to "p.txt"
                "main" -> "b.txt" to "q.txt"
                else -> "c.txt" to "r.txt"
            }
            File(repository, "x/$first").apply { parentFile.mkdirs() }.writeText(lines)
            File(repository, "x/$second").writeText(other)
        }

        val found = Git.open(repository).use { renamedFiles(it.repository, it.repository.readDirCache()) }.toSet()

        assertEquals(
            setOf(RenamedFile("x/a.txt", "x/b.txt", "x/c.txt"), RenamedFile("x/p.txt", "x/q.txt", "x/r.txt")),
            found,
        )
    }

    @Test
    fun `one renamed file pairs whatever its conflicts did to the content`(): Unit = runBlocking {
        // Each side rewrote the same five of twelve lines its own way: like the old content enough for git to see
        // the renames, but its merge, with both rewrites and the markers, is too unlike it to pair by content
        fun rewritten(side: String) = (1..12).joinToString("") { if (it <= 5) "$side $it\n" else "line $it\n" }

        renameOnBothSides(
            ours = { rewritten("ours") },
            theirs = { rewritten("theirs") },
            base = (1..12).joinToString("") { "line $it\n" },
        )

        val found = Git.open(repository).use { renamedFiles(it.repository, it.repository.readDirCache()) }

        assertEquals(listOf(RenamedFile("notes/a.txt", "notes/b.txt", "notes/c.txt")), found)
    }

    @Test
    fun `renamed files with the same content can't be told apart, and stay conflicts at other paths`(): Unit =
        runBlocking {
            git.mergeSides(repository) { side ->
                FileUtils.delete(File(repository, "x"), FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
                val names = when (side) {
                    "base" -> listOf("a.txt", "p.txt")
                    "main" -> listOf("b.txt", "q.txt")
                    else -> listOf("c.txt", "r.txt")
                }
                names.forEach { File(repository, "x/$it").apply { parentFile.mkdirs() }.writeText(lines) }
            }

            assertEquals(emptyList<RenamedFile>(), Git.open(repository).use { renamedFiles(it.repository, it.repository.readDirCache()) })
            assertEquals(ConflictContent.OtherPaths, load("x/b.txt").content)
        }

    @Test
    fun `a new name whose blob another path alone has too stays out, as it can't be told which pairs`(): Unit =
        runBlocking {
            // As the index would be: two paths of ours alone with the same blob as theirs' new name
            File(tempDir, "old").writeText(lines)
            File(tempDir, "merged").writeText(lines.replace("line 1", "line one"))
            val old = git.run(repository, "hash-object", "-w", File(tempDir, "old").path).trim()
            val merged = git.run(repository, "hash-object", "-w", File(tempDir, "merged").path).trim()
            writeConflictStages(repository, "notes/a.txt", FileMode.REGULAR_FILE to old)
            writeConflictStages(repository, "notes/b.txt", null, FileMode.REGULAR_FILE to merged)
            writeConflictStages(repository, "notes/x.txt", null, FileMode.REGULAR_FILE to merged)
            writeConflictStages(repository, "notes/c.txt", null, null, FileMode.REGULAR_FILE to merged)

            assertEquals(emptyList<RenamedFile>(), Git.open(repository).use { renamedFiles(it.repository, it.repository.readDirCache()) })
            assertEquals(ConflictContent.OtherPaths, load("notes/b.txt").content)
        }

    @Test
    fun `in a rebase, theirs is the commit replayed`(): Unit = runBlocking {
        File(repository, "notes/a.txt").apply { parentFile.mkdirs() }.writeText(lines)
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "switch", "-c", "feature")
        git.run(repository, "mv", "notes/a.txt", "notes/c.txt")
        File(repository, "notes/c.txt").writeText(lines.replace("line 1", "theirs' line"))
        git.run(repository, "commit", "-am", "To c")
        git.run(repository, "switch", "main")
        git.run(repository, "mv", "notes/a.txt", "notes/b.txt")
        File(repository, "notes/b.txt").writeText(lines.replace("line 1", "ours' line"))
        git.run(repository, "commit", "-am", "To b")
        val replayed = git.run(repository, "rev-parse", "feature").trim()
        git.run(repository, "switch", "feature")
        git.runFailing(repository, "rebase", "main")

        val content = assertInstanceOf(ConflictContent.Renamed::class.java, load("notes/a.txt").content)

        // In a rebase, ours is main's name, as the rebase builds on it
        assertEquals(RenamedFile("notes/a.txt", "notes/b.txt", "notes/c.txt"), content.file)
        assertEquals(replayed, content.theirsCommit)
    }

    @Test
    fun `a path with one side alone and nothing to pair with is a conflict at other paths`(): Unit = runBlocking {
        git.mergeSides(repository) { side ->
            when (side) {
                "base" -> File(repository, "base.txt").writeText("base\n")
                "main" -> File(repository, "d").writeText("file\n")
                "feature" -> File(repository, "d/x").apply { parentFile.mkdirs() }.writeText("inner\n")
            }
        }

        val file = load("d~HEAD")

        assertEquals(ConflictContent.OtherPaths, file.content)
        assertEquals(VersionPreview.File(size = 5), file.preview?.ours)
        assertEquals(null, file.preview?.theirs)
    }

    @Test
    fun `keeping ours' name stages it, and removes the old name and theirs' new one`(): Unit = runBlocking {
        renameOnBothSides(ours = { it.replace("line 1", "line one") }, theirs = { it })
        val merged = File(repository, "notes/b.txt").readText()

        resolve(setOf(ConflictSide.OURS))

        assertEquals("", git.run(repository, "ls-files", "-u"))
        assertEquals(merged, git.staged(repository, "notes/b.txt"))
        assertEquals("", git.run(repository, "ls-files", "notes/a.txt", "notes/c.txt"))
        assertFalse(File(repository, "notes/c.txt").exists())
        assertEquals(merged, File(repository, "notes/b.txt").readText())
    }

    @Test
    fun `keeping theirs' name, or both, stages those`(): Unit = runBlocking {
        renameOnBothSides(ours = { it }, theirs = { it.replace("line 8", "line eight") })

        resolve(setOf(ConflictSide.THEIRS))

        assertEquals("", git.run(repository, "ls-files", "notes/a.txt", "notes/b.txt"))
        assertTrue("line eight" in git.staged(repository, "notes/c.txt"))

        git.run(repository, "merge", "--abort")
        git.runFailing(repository, "merge", "feature")

        resolve(setOf(ConflictSide.OURS, ConflictSide.THEIRS))

        assertEquals(listOf("notes/b.txt", "notes/c.txt"), git.run(repository, "ls-files", "notes").trim().lines())
        assertEquals("", git.run(repository, "ls-files", "-u"))
    }

    @Test
    fun `a name kept whose content has conflicts becomes a conflict of its content, which the resolver opens`(): Unit =
        runBlocking {
            renameOnBothSides(ours = { it.replace("line 1", "ours' line") }, theirs = { it.replace("line 1", "theirs' line") })

            assertTrue(assertInstanceOf(ConflictContent.Renamed::class.java, load("notes/b.txt").content).contentConflicts)

            // The base from the old name, ours from HEAD, theirs from the commit merged
            val stages = listOf(":1:notes/a.txt" to 1, "HEAD:notes/b.txt" to 2, "feature:notes/c.txt" to 3)
                .map { (blob, stage) -> "100644 ${git.run(repository, "rev-parse", blob).trim()} $stage\tnotes/b.txt" }

            resolve(setOf(ConflictSide.OURS))

            assertEquals(stages, git.run(repository, "ls-files", "-s", "notes").trim().lines())
            assertFalse(File(repository, "notes/c.txt").exists())

            // Written again with seven-character markers, which the resolver and git rerere count
            val text = assertInstanceOf(ConflictContent.Text::class.java, load("notes/b.txt").content)

            assertTrue(File(repository, "notes/b.txt").readText().startsWith("<<<<<<< HEAD\n"))
            assertEquals(WorkingFileState.MATCHES, text.workingFile.state)
            assertEquals(1, text.merge.openConflicts)
        }

    @Test
    fun `markers fixed by hand are staged as they are`(): Unit = runBlocking {
        renameOnBothSides(ours = { it.replace("line 1", "ours' line") }, theirs = { it.replace("line 1", "theirs' line") })
        // A line that opens like a marker, as in a note about git, isn't a conflict without one that closes it
        File(repository, "notes/b.txt").writeText("fixed\n<<<<<<< as git writes it\n")

        resolve(setOf(ConflictSide.OURS))

        assertEquals("fixed\n<<<<<<< as git writes it\n", git.staged(repository, "notes/b.txt"))
        assertEquals("", git.run(repository, "ls-files", "-u"))
    }

    @Test
    fun `a name kept without its working file is checked out first`(): Unit = runBlocking {
        renameOnBothSides(ours = { it.replace("line 1", "line one") }, theirs = { it })
        val merged = git.run(repository, "show", ":2:notes/b.txt")
        File(repository, "notes/b.txt").delete()

        resolve(setOf(ConflictSide.OURS))

        assertEquals(merged, git.staged(repository, "notes/b.txt"))
        assertEquals(merged, File(repository, "notes/b.txt").readText())
    }

    @Test
    fun `markers edited since stay as they are, in a conflict of the content`(): Unit = runBlocking {
        renameOnBothSides(ours = { it.replace("line 1", "ours' line") }, theirs = { it.replace("line 1", "theirs' line") })
        File(repository, "notes/b.txt").appendText("note\n")
        val edited = File(repository, "notes/b.txt").readText()

        resolve(setOf(ConflictSide.OURS))

        assertEquals(3, git.run(repository, "ls-files", "-u", "notes/b.txt").trim().lines().size)
        assertEquals(edited, File(repository, "notes/b.txt").readText())
    }

    @Test
    fun `content conflicts in an operation that doesn't tell theirs' commit change nothing`(): Unit = runBlocking {
        renameOnBothSides(ours = { it.replace("line 1", "ours' line") }, theirs = { it.replace("line 1", "theirs' line") })
        val before = git.run(repository, "ls-files", "-s")
        File(repository, ".git/MERGE_HEAD").delete()

        val result = resolve(setOf(ConflictSide.OURS))

        assertEquals(ConflictError.RenamedContentUnknown("notes/a.txt"), (result as Either.Err).error)
        assertEquals(before, git.run(repository, "ls-files", "-s"))
        assertEquals(null, assertInstanceOf(ConflictContent.Renamed::class.java, load("notes/a.txt").content).theirsCommit)
    }

    @Test
    fun `a renamed file that isn't one any more changes nothing`(): Unit = runBlocking {
        renameOnBothSides(ours = { it }, theirs = { it })
        git.run(repository, "rm", "-q", "notes/c.txt")

        val result = resolve(setOf(ConflictSide.OURS))

        assertEquals(ConflictError.StagesChanged("notes/a.txt"), (result as Either.Err).error)
        assertTrue(git.run(repository, "ls-files", "-u", "notes/b.txt").isNotEmpty())
    }

    /** `notes/a.txt`, renamed to `b.txt` by main and to `c.txt` by feature, each changing its content as given. */
    private fun renameOnBothSides(ours: (String) -> String, theirs: (String) -> String, base: String = lines) {
        File(repository, "notes/a.txt").apply { parentFile.mkdirs() }.writeText(base)
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "branch", "feature")
        git.run(repository, "mv", "notes/a.txt", "notes/b.txt")
        File(repository, "notes/b.txt").writeText(ours(base))
        git.run(repository, "commit", "-am", "To b")
        git.run(repository, "switch", "feature")
        git.run(repository, "mv", "notes/a.txt", "notes/c.txt")
        File(repository, "notes/c.txt").writeText(theirs(base))
        git.run(repository, "commit", "-am", "To c")
        git.run(repository, "switch", "main")
        git.runFailing(repository, "merge", "feature")
    }

    private suspend fun load(path: String): ConflictedFile {
        val result = GetConflictedFileGitAction(jgit)(File(repository, ".git").path, path)

        return assertInstanceOf(Either.Ok::class.java, result).value as ConflictedFile
    }

    private suspend fun resolve(keep: Set<ConflictSide>) =
        resolve(File(repository, ".git").path, RenamedFile("notes/a.txt", "notes/b.txt", "notes/c.txt"), keep)
}
