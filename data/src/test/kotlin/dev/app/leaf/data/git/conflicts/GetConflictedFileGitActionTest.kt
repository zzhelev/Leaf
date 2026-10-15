// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStages
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.TextLimit
import dev.app.leaf.domain.conflicts.conflictChunks
import dev.app.leaf.domain.conflicts.resolving
import dev.app.leaf.domain.conflicts.typing
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
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
import kotlin.time.Duration

/** Loading a conflicted file for the resolver, from conflicts that git's merges leave (conflict-resolver.md 2.4). */
class GetConflictedFileGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val getConflictedFile = GetConflictedFileGitAction(jgit, testPartialCloneFetch(jgit))

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a file that both sides changed is merged, with the sides named and the working file as git left it`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "notes.txt")

            val file = load("notes.txt")
            val text = assertInstanceOf(ConflictContent.Text::class.java, file.content)

            assertEquals(ConflictStages.BOTH_MODIFIED, file.stages)
            assertEquals(FileConflict(ConflictVersion.FILE, ConflictVersion.FILE), file.conflict)
            assertEquals(listOf(Chunk.Conflict(lines(0, 1), lines(0, 1), lines(0, 1))), text.merge.chunks)
            assertEquals("base\n", text.merge.base.string())
            assertEquals("main\n", text.merge.ours.string())
            assertEquals("feature\n", text.merge.theirs.string())
            assertEquals(
                WorkingFileCheck(WorkingFileState.MATCHES, conflicts = 1, blobId(workingFile("notes.txt"))),
                text.workingFile,
            )
            assertEquals(
                ConflictSides(ConflictOperation.MERGE, commit("main", "HEAD"), commit("feature", "feature")),
                file.sides,
            )
            assertEquals(git.run(repository, "rev-parse", ":2:notes.txt").trim(), file.ours?.objectId)
            assertEquals(FileMode.REGULAR_FILE.bits, file.ours?.mode)
        }

    @Test
    fun `a file that both sides added has an empty base`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        val file = load("added.txt")
        val text = assertInstanceOf(ConflictContent.Text::class.java, file.content)

        assertEquals(ConflictStages.BOTH_ADDED, file.stages)
        assertEquals(null, file.base)
        assertEquals(0, text.merge.base.size())
        assertEquals(WorkingFileState.MATCHES, text.workingFile.state)
    }

    @Test
    fun `a file that one side deleted is a choice between keeping and deleting it`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        val keptByUs = load("kept.txt")
        val keptByThem = load("dropped.txt")

        assertEquals(ConflictStages.DELETED_BY_THEM, keptByUs.stages)
        assertEquals(ConflictContent.Deleted, keptByUs.content)
        assertEquals(ConflictStages.DELETED_BY_US, keptByThem.stages)
        assertEquals(ConflictContent.Deleted, keptByThem.content)
    }

    @Test
    fun `a binary file, or one that the merge attribute merges as binary, is a choice of side`(): Unit = runBlocking {
        File(repository, ".gitattributes").writeText("*.dat -merge\n")
        git.mergeContents(repository, "image.bin" to { "\u0000$it\n" }, "table.dat" to { "$it\n" })

        assertEquals(ConflictContent.Binary, load("image.bin").content)
        assertEquals(ConflictContent.Binary, load("table.dat").content)
    }

    @Test
    fun `Git LFS pointers are a choice of side, with each side's object`(): Unit = runBlocking {
        val objects = mapOf("base" to LfsObject("b".repeat(64), 100), "main" to LfsObject("a".repeat(64), 200))
            .plus("feature" to LfsObject("f".repeat(64), 300))

        git.mergeContents(
            repository,
            "video.mp4" to { side ->
                "version https://git-lfs.github.com/spec/v1\noid sha256:${objects.getValue(side).oid}\n" +
                    "size ${objects.getValue(side).size}\n"
            },
            // Looks like a pointer, but its id isn't hex: text
            "notes.md" to { side -> "version https://git-lfs.github.com/spec/v1\noid sha256:$side\nsize 1\n" },
        )

        assertEquals(ConflictContent.Lfs(objects["main"], objects["feature"]), load("video.mp4").content)
        assertInstanceOf(ConflictContent.Text::class.java, load("notes.md").content)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link is a choice of target`(): Unit = runBlocking {
        git.mergeLinks(repository, "link")

        assertEquals(
            ConflictContent.SymbolicLink(oursTarget = "main-target", theirsTarget = "feature-target"),
            load("link").content,
        )
    }

    @Test
    fun `a submodule is a choice of commit`(): Unit = runBlocking {
        // git leaves a submodule's conflict with the commits at stages 1 to 3, here written straight into the index
        val commits = listOf("1", "2", "3").map { it.repeat(40) }
        writeConflictStages(repository, "sub", *commits.map { FileMode.GITLINK to it }.toTypedArray())

        val file = load("sub")

        assertEquals(ConflictContent.Submodule, file.content)
        assertEquals(commits, listOf(file.base, file.ours, file.theirs).map { it?.objectId })
        assertEquals(ConflictSides(ConflictOperation.OTHER, commit("main", "HEAD"), theirs = null), file.sides)
    }

    @Test
    fun `a submodule that one side deleted is a submodule, and a deleted symbolic link a deletion`(): Unit =
        runBlocking {
            // git mergetool's order: a submodule first, then a deletion, then a symbolic link
            val commit = "1".repeat(40)
            File(tempDir, "target").writeText("target")
            val link = git.run(repository, "hash-object", "-w", File(tempDir, "target").path).trim()
            writeConflictStages(repository, "sub", FileMode.GITLINK to commit, FileMode.GITLINK to commit, null)
            writeConflictStages(repository, "link", FileMode.SYMLINK to link, null, FileMode.SYMLINK to link)

            assertEquals(ConflictContent.Submodule, load("sub").content)
            assertEquals(ConflictStages.DELETED_BY_THEM, load("sub").stages)
            assertEquals(ConflictContent.Deleted, load("link").content)
            assertEquals(ConflictStages.DELETED_BY_US, load("link").stages)
        }

    @Test
    fun `a file renamed differently on each side has its versions at other paths, and opens as one`(): Unit = runBlocking {
        File(repository, "a.txt").writeText("a\nb\nc\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "branch", "feature")
        git.run(repository, "mv", "a.txt", "b.txt")
        git.run(repository, "commit", "-m", "To b")
        git.run(repository, "switch", "feature")
        git.run(repository, "mv", "a.txt", "c.txt")
        git.run(repository, "commit", "-m", "To c")
        git.run(repository, "switch", "main")
        git.runFailing(repository, "merge", "feature")

        // Each path opens the file as one, under its old name
        val renamed = RenamedFile("a.txt", "b.txt", "c.txt")

        for (path in renamed.paths) {
            val file = load(path)

            assertEquals("a.txt", file.path)
            assertEquals(renamed, (file.content as ConflictContent.Renamed).file)
        }
    }

    @Test
    fun `text larger than the limit is a choice of side`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")

        val file = Git.open(repository).use { loadConflictedFile(it.repository, "notes.txt", maxTextSize = 7) }

        // "feature\n" is 8 bytes
        assertEquals(ConflictContent.TooLarge(size = 8, limit = TextLimit.Size(7)), file?.content)
    }

    @Test
    fun `text whose versions take longer than the limit to compare is a choice of side`(): Unit = runBlocking {
        // Every other line changed on each side, as both sides bumping packages in a lock file
        git.mergeContents(
            repository,
            "lock.txt" to { side -> (0 until 3_000).joinToString("") { if (it % 2 == 0) "pkg-$it $side\n" else "pkg-$it\n" } },
        )

        fun load(maxCompareTime: Duration) =
            Git.open(repository).use { loadConflictedFile(it.repository, "lock.txt", maxCompareTime = maxCompareTime) }

        // Theirs is the largest side: "feature" is the longest name
        val size = Git.open(repository).use { it.repository.open(it.repository.resolve("feature:lock.txt")).size }

        assertEquals(ConflictContent.TooLarge(size, TextLimit.Time(Duration.ZERO)), load(Duration.ZERO)?.content)
        assertEquals(1_500, (load(MAX_RESOLVER_COMPARE_TIME)?.content as ConflictContent.Text).merge.conflicts)
    }

    @Test
    fun `a working file that git rerere resolved has no markers`(): Unit = runBlocking {
        git.run(repository, "config", "rerere.enabled", "true")
        git.mergeWithConflictsIn(repository, "notes.txt")
        File(repository, "notes.txt").writeText("resolved\n")
        git.run(repository, "commit", "-am", "Merge")
        git.run(repository, "reset", "--hard", "HEAD~1")
        // rerere writes the recorded resolution, and leaves the conflict in the index
        git.runFailing(repository, "merge", "feature")

        val text = assertInstanceOf(ConflictContent.Text::class.java, load("notes.txt").content)

        assertEquals("resolved\n", workingFile("notes.txt").decodeToString())
        assertEquals(WorkingFileState.NO_MARKERS, text.workingFile.state)
    }

    @Test
    fun `a working file with another number of conflicts, or none at all, is different or missing`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "edited.txt", "deleted.txt")
            File(repository, "edited.txt").appendText("<<<<<<< HEAD\nmore\n=======\nother\n>>>>>>> x\n")
            File(repository, "deleted.txt").delete()

            suspend fun check(path: String) =
                assertInstanceOf(ConflictContent.Text::class.java, load(path).content).workingFile

            assertEquals(
                WorkingFileCheck(WorkingFileState.DIFFERENT, conflicts = 2, blobId(workingFile("edited.txt"))),
                check("edited.txt"),
            )
            assertEquals(
                WorkingFileCheck(WorkingFileState.MISSING, conflicts = 0, contentId = null),
                check("deleted.txt"),
            )
        }

    @Test
    fun `markers have the length of the conflict-marker-size attribute`(): Unit = runBlocking {
        File(repository, ".gitattributes").writeText("*.txt conflict-marker-size=10\n")
        git.mergeWithConflictsIn(repository, "notes.txt")

        val text = assertInstanceOf(ConflictContent.Text::class.java, load("notes.txt").content)

        assertTrue(workingFile("notes.txt").decodeToString().startsWith("<<<<<<<<<< HEAD\n"))
        assertEquals(WorkingFileState.MATCHES, text.workingFile.state)
    }

    @Test
    fun `a file without conflicts isn't loaded`(): Unit = runBlocking {
        assertEquals(
            Either.Err(ConflictError.NotConflicted(listOf("README.md"))),
            getConflictedFile(File(repository, ".git").path, "README.md"),
        )
    }

    @Test
    fun `a file that the resolver saved opens as it was saved, with its picks and the lines typed`(): Unit =
        runBlocking {
            git.mergeContents(repository, "app.txt" to { side -> sevenLines(side) })

            val loaded = load("app.txt")
            val merge = assertInstanceOf(ConflictContent.Text::class.java, loaded.content).merge
            val conflicts = merge.conflictChunks()
            val middle = merge.chunks.indexOfFirst { it is Chunk.Unchanged && it.base == lines(2, 5) }
            // Theirs for the first conflict, the second left open, and a line typed in the middle
            val saved = merge
                .resolving(conflicts.first(), ConflictResolution.Theirs)
                .typing(middle, TypedLines(RawText("c\nd\ntyped\ne\n".toByteArray())))

            save(loaded, saved)

            val reloaded = assertInstanceOf(ConflictContent.Text::class.java, load("app.txt").content)

            assertEquals(saved.chunks, reloaded.merge.chunks)
            assertEquals(WorkingFileState.MATCHES, reloaded.workingFile.state)
            assertEquals(1, reloaded.merge.openConflicts)
        }

    @Test
    fun `a file saved through core autocrlf, with CRLF on disk, opens as it was saved`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        git.mergeContents(repository, "app.txt" to { side -> sevenLines(side) })

        val loaded = load("app.txt")
        val merge = assertInstanceOf(ConflictContent.Text::class.java, loaded.content).merge
        val saved = merge.resolving(merge.conflictChunks().last(), ConflictResolution.OursThenTheirs)

        assertTrue("\r\n" in workingFile("app.txt").decodeToString())

        save(loaded, saved)

        assertTrue("F-main\r\nF-feature\r\n" in workingFile("app.txt").decodeToString())
        assertEquals(saved.chunks, assertInstanceOf(ConflictContent.Text::class.java, load("app.txt").content).merge.chunks)
    }

    @Test
    fun `markers edited by hand open as the merge, as no state of it writes them`(): Unit = runBlocking {
        git.mergeContents(repository, "app.txt" to { side -> sevenLines(side) })
        File(repository, "app.txt").writeText(workingFile("app.txt").decodeToString().replace("B-main", "B-edited"))

        val text = assertInstanceOf(ConflictContent.Text::class.java, load("app.txt").content)

        assertEquals(2, text.merge.openConflicts)
        assertTrue(text.merge.chunks.none { it is Chunk.Unchanged && it.edited != null })
    }

    /** Seven lines, of which the second and the sixth are [side]'s own: two conflicts. */
    private fun sevenLines(side: String) = when (side) {
        "base" -> "a\nb\nc\nd\ne\nf\ng\n"
        else -> "a\nB-$side\nc\nd\ne\nF-$side\ng\n"
    }

    /** Writes [merge] for [file] as the resolver's Save does. */
    private suspend fun save(file: ConflictedFile, merge: TextMerge) {
        assertInstanceOf(
            Either.Ok::class.java,
            WriteConflictResultGitAction(jgit)(File(repository, ".git").path, file, merge, overwriteChanges = false),
        )
    }

    private suspend fun load(path: String): ConflictedFile {
        val result = getConflictedFile(File(repository, ".git").path, path)

        return assertInstanceOf(Either.Ok::class.java, result).value as ConflictedFile
    }

    private fun workingFile(path: String) = File(repository, path).readBytes()

    private fun commit(branch: String, revision: String) = ConflictCommit(
        branch = branch,
        hash = git.run(repository, "rev-parse", "--short=7", revision).trim(),
        subject = git.run(repository, "log", "-1", "--format=%s", revision).trim(),
    )
}

private fun lines(start: Int, end: Int) = LineRange(start, end)

private fun org.eclipse.jgit.diff.RawText.string() = rawContent.decodeToString()
