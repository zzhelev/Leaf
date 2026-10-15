// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.CommitRelation
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictPreview
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.KeptChanges
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.PreviewImage
import dev.app.leaf.domain.models.PreviewLine
import dev.app.leaf.domain.models.PreviewLineType
import dev.app.leaf.domain.models.SubmodulePreview
import dev.app.leaf.domain.models.VersionPreview
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration

/** What the choice cards show of conflicts that three panes can't show (`conflict-resolver.md`, 2.4, step 5). */
class ConflictPreviewsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()

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
    fun `a file that one side deleted shows the kept version's diff against the base`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        val preview = preview("kept.txt")

        assertEquals(VersionPreview.File(size = 12), preview.ours)
        assertNull(preview.theirs)
        assertEquals(
            KeptChanges(
                added = 1,
                removed = 1,
                lines = listOf(
                    PreviewLine(PreviewLineType.HUNK, "@@ -1 +1 @@"),
                    PreviewLine(PreviewLineType.REMOVED, "base"),
                    PreviewLine(PreviewLineType.ADDED, "main's kept"),
                ),
                more = 0,
            ),
            preview.kept,
        )
        assertEquals(false, preview.workingFileEdited)
    }

    @Test
    fun `a diff that takes longer than the limit to compare is left out, and the versions still show`(): Unit =
        runBlocking {
            // Theirs deleted a lock file in which ours bumped every other package
            git.mergeSides(repository) { side ->
                val lock = File(repository, "lock.txt")

                if (side == "feature") {
                    lock.delete()
                } else {
                    lock.writeText((0 until 3_000).joinToString("") { if (it % 2 == 0) "pkg-$it $side\n" else "pkg-$it\n" })
                }
            }

            fun previewWithin(maxCompareTime: Duration) = Git.open(repository).use {
                loadConflictedFile(it.repository, "lock.txt", maxCompareTime = maxCompareTime)
            }?.preview

            val cut = previewWithin(Duration.ZERO)

            assertNull(cut?.kept)
            assertInstanceOf(VersionPreview.File::class.java, cut?.ours)
            assertEquals(1_500, previewWithin(MAX_RESOLVER_COMPARE_TIME)?.kept?.added)
        }

    @Test
    fun `the kept version's diff is the working file's, which Keep stages, and says that it was edited`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            File(repository, "dropped.txt").appendText("edited\n")

            val preview = preview("dropped.txt")

            assertEquals(2, preview.kept?.added)
            assertEquals(
                listOf("@@ -1 +1,2 @@", "base", "feature's dropped", "edited"),
                preview.kept?.lines?.map { it.text },
            )
            assertEquals(true, preview.workingFileEdited)

            // Without a working file, the side's version, as Keep then checks it out
            File(repository, "dropped.txt").delete()

            assertEquals(1, preview("dropped.txt").kept?.added)
            assertEquals(false, preview("dropped.txt").workingFileEdited)
        }

    @Test
    fun `a filter attribute makes the working file something else, so the diff is the side's version`(): Unit =
        runBlocking {
            File(repository, ".gitattributes").writeText("dropped.txt filter=crypt\n")
            git.mergeWithConflicts(repository)
            File(repository, "dropped.txt").writeText("decrypted, as the filter would leave it\n")

            val preview = preview("dropped.txt")

            assertEquals(listOf("@@ -1 +1 @@", "base", "feature's dropped"), preview.kept?.lines?.map { it.text })
            assertEquals(false, preview.workingFileEdited)
        }

    @Test
    fun `a binary file that one side deleted has no diff, and an image its bytes`(): Unit = runBlocking {
        git.mergeSides(repository) { side ->
            val logo = File(repository, "logo.png")
            when (side) {
                "main" -> logo.delete()
                else -> logo.writeText("\u0000png $side")
            }
        }

        val preview = preview("logo.png")

        assertEquals(ConflictContent.Deleted, load("logo.png").content)
        assertNull(preview.ours)
        assertEquals(
            VersionPreview.File(size = 12, image = PreviewImage("\u0000png feature".toByteArray())),
            preview.theirs,
        )
        assertNull(preview.kept)
    }

    @Test
    fun `a working file with CRLF line endings that git add converts isn't an edit`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        git.mergeWithConflicts(repository)
        val file = File(repository, "kept.txt")

        // git checked it out with CRLF
        assertEquals("main's kept\r\n", file.readText())
        assertEquals(false, preview("kept.txt").workingFileEdited)
        assertEquals("main's kept", preview("kept.txt").kept?.lines?.last()?.text)
    }

    @Test
    fun `a binary file shows each version's size, and an image its bytes`(): Unit = runBlocking {
        git.mergeContents(repository, "logo.png" to { "\u0000png $it" }, "data.bin" to { "\u0000bin $it" })

        val image = preview("logo.png")
        val binary = preview("data.bin")

        assertEquals(ConflictContent.Binary, load("logo.png").content)
        assertEquals(VersionPreview.File(size = 9, image = PreviewImage("\u0000png main".toByteArray())), image.ours)
        assertEquals(
            VersionPreview.File(size = 12, image = PreviewImage("\u0000png feature".toByteArray())),
            image.theirs,
        )
        assertEquals(VersionPreview.File(size = 9, image = PreviewImage("\u0000png base".toByteArray())), image.base)
        assertEquals(VersionPreview.File(size = 9), binary.ours)
        assertNull(binary.kept)
    }

    @Test
    fun `an image larger than the limit shows its size alone`(): Unit = runBlocking {
        val large = MAX_PREVIEW_IMAGE_SIZE.toInt() + 1
        git.mergeSides(repository) { side ->
            File(repository, "huge.png").writeBytes(ByteArray(large) { 0 } + side.toByteArray())
        }

        assertEquals(VersionPreview.File(size = large + 4L), preview("huge.png").ours)
    }

    @Test
    fun `Git LFS pointers show each object, and its bytes once it's downloaded and an image`(): Unit = runBlocking {
        val objects = mapOf("base" to "b", "main" to "a", "feature" to "f")
            .mapValues { (_, digit) -> LfsObject(digit.repeat(64), 4) }

        fun pointer(side: String) = "version https://git-lfs.github.com/spec/v1\noid sha256:${objects.getValue(side).oid}\n" +
            "size ${objects.getValue(side).size}\n"

        git.mergeContents(repository, "video.mp4" to ::pointer, "photo.png" to ::pointer)
        // Only main's object is in the store
        File(repository, ".git/lfs/objects/aa/aa/${"a".repeat(64)}").apply { parentFile.mkdirs() }.writeText("main")

        val video = preview("video.mp4")
        val photo = preview("photo.png")

        assertEquals(VersionPreview.Lfs(objects.getValue("main"), downloaded = true), video.ours)
        assertEquals(VersionPreview.Lfs(objects.getValue("feature"), downloaded = false), video.theirs)
        assertEquals(VersionPreview.Lfs(objects.getValue("base"), downloaded = false), video.base)
        assertEquals(
            VersionPreview.Lfs(objects.getValue("main"), downloaded = true, image = PreviewImage("main".toByteArray())),
            photo.ours,
        )
    }

    @Test
    fun `an LFS object of another size than its pointer says isn't downloaded`(): Unit = runBlocking {
        val digits = mapOf("base" to "0", "main" to "1", "feature" to "2")
        git.mergeContents(
            repository,
            "video.mp4" to { side ->
                "version https://git-lfs.github.com/spec/v1\noid sha256:${digits.getValue(side).repeat(64)}\nsize 9\n"
            },
        )
        File(repository, ".git/lfs/objects/11/11/${"1".repeat(64)}").apply { parentFile.mkdirs() }.writeText("cut")

        assertEquals(false, (preview("video.mp4").ours as VersionPreview.Lfs).downloaded)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link shows its target, and whether it points to something`(): Unit = runBlocking {
        git.mergeLinks(repository, "link")
        File(repository, "main-target").writeText("here")

        val preview = preview("link")

        assertEquals(VersionPreview.Link("main-target", resolves = true), preview.ours)
        assertEquals(VersionPreview.Link("feature-target", resolves = false), preview.theirs)
        assertEquals(VersionPreview.Link("base-target", resolves = false), preview.base)
    }

    @Test
    fun `a submodule names each commit from its repository, and how they relate`(): Unit = runBlocking {
        val sub = git.initRepository(File(repository, "sub"))
        val commits = listOf("Base", "Main", "Feature").associateWith { message ->
            if (message == "Feature") {
                git.run(sub, "switch", "-c", "feature", "HEAD~1")
            }

            File(sub, "file.txt").writeText(message)
            git.run(sub, "add", ".")
            git.run(sub, "commit", "-m", message)
            git.run(sub, "rev-parse", "HEAD").trim()
        }
        writeConflictStages(
            repository,
            "sub",
            *listOf("Base", "Main", "Feature").map { FileMode.GITLINK to commits.getValue(it) }.toTypedArray(),
        )

        val preview = preview("sub")
        val ours = assertInstanceOf(VersionPreview.Commit::class.java, preview.ours)

        assertEquals(commits.getValue("Main"), ours.commit)
        assertEquals("Main", ours.summary?.subject)
        assertEquals("Leaf Test", ours.summary?.author)
        assertEquals("Feature", (preview.theirs as VersionPreview.Commit).summary?.subject)
        assertEquals(
            SubmodulePreview(initialized = true, CommitRelation.DIVERGED, checkedOut = commits.getValue("Feature")),
            preview.submodule,
        )

        // Theirs at the base: ours already has it
        git.run(repository, "update-index", "--force-remove", "sub")
        writeConflictStages(
            repository,
            "sub",
            *listOf("Base", "Main", "Base").map { FileMode.GITLINK to commits.getValue(it) }.toTypedArray(),
        )

        assertEquals(CommitRelation.OURS_CONTAINS_THEIRS, preview("sub").submodule?.relation)

        // Ours at the base: theirs has it
        git.run(repository, "update-index", "--force-remove", "sub")
        writeConflictStages(
            repository,
            "sub",
            *listOf("Base", "Base", "Feature").map { FileMode.GITLINK to commits.getValue(it) }.toTypedArray(),
        )

        assertEquals(CommitRelation.THEIRS_CONTAINS_OURS, preview("sub").submodule?.relation)
    }

    @Test
    fun `a submodule without a repository has its commits' ids alone`(): Unit = runBlocking {
        val commits = listOf("1", "2", "3").map { it.repeat(40) }
        writeConflictStages(repository, "sub", *commits.map { FileMode.GITLINK to it }.toTypedArray())

        val preview = preview("sub")

        assertEquals(VersionPreview.Commit(commits[1], summary = null), preview.ours)
        assertEquals(SubmodulePreview(initialized = false, relation = null, checkedOut = null), preview.submodule)
    }

    @Test
    fun `text too large to merge shows each version's size and lines`(): Unit = runBlocking {
        git.mergeContents(repository, "notes.txt" to { side -> "$side\n".repeat(3) + "last" })

        val file = Git.open(repository).use { loadConflictedFile(it.repository, "notes.txt", maxTextSize = 7) }
        val preview = file?.preview

        assertInstanceOf(ConflictContent.TooLarge::class.java, file?.content)
        assertEquals(VersionPreview.File(size = 19, lines = 4), preview?.ours)
        assertEquals(VersionPreview.File(size = 28, lines = 4), preview?.theirs)
    }

    @Test
    fun `text has no preview`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        assertInstanceOf(ConflictContent.Text::class.java, load("notes.txt").content)
        assertNull(load("notes.txt").preview)
    }

    @Test
    fun `a file renamed on both sides shows each new name's version, and the old name's as the base`(): Unit =
        runBlocking {
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

            assertEquals(
                ConflictPreview(VersionPreview.File(6), VersionPreview.File(6), VersionPreview.File(6)),
                preview("b.txt"),
            )
        }

    @Test
    fun `the diff has git's hunks, with three lines around each change, and stops at its limit`() {
        val lines = (1..30).map { "$it" }
        val base = lines.joinToString("") { "$it\n" }
        val kept = lines.mapNotNull { mapOf("5" to "five", "9" to "nine", "25" to null).getOrDefault(it, it) }
            .joinToString("") { "$it\n" }

        val changes = keptChanges(RawText(base.toByteArray()), RawText(kept.toByteArray()))

        // 5 and 9 are 3 lines apart, so they share a hunk; 25 has one of its own
        assertEquals(
            listOf("@@ -2,11 +2,11 @@", "@@ -22,7 +22,6 @@"),
            changes.lines.filter { it.type == PreviewLineType.HUNK }.map { it.text },
        )
        assertEquals(2, changes.added)
        assertEquals(3, changes.removed)
        assertEquals(
            listOf("2", "3", "4", "5", "five", "6", "7", "8", "9", "nine", "10", "11", "12"),
            changes.lines.drop(1).take(13).map { it.text },
        )

        val cut = keptChanges(RawText(base.toByteArray()), RawText(kept.toByteArray()), maxLines = 5)

        assertEquals(5, cut.lines.size)
        assertEquals(changes.lines.size - 5, cut.more)
    }

    @Test
    fun `changes six lines apart share a hunk, and seven apart don't, as in git diff`() {
        val base = (1..30).joinToString("") { "$it\n" }

        fun headers(second: Int): List<String> {
            val kept = (1..30).joinToString("") { if (it == 5 || it == second) "x\n" else "$it\n" }

            return keptChanges(RawText(base.toByteArray()), RawText(kept.toByteArray())).lines
                .filter { it.type == PreviewLineType.HUNK }
                .map { it.text }
        }

        // From git diff --no-index -U3
        assertEquals(listOf("@@ -2,14 +2,14 @@"), headers(12))
        assertEquals(listOf("@@ -2,7 +2,7 @@", "@@ -10,7 +10,7 @@"), headers(13))
    }

    @Test
    fun `a file added to an empty base is one hunk from line 0`() {
        val changes = keptChanges(RawText.EMPTY_TEXT, RawText("a\nb\n".toByteArray()))

        assertEquals(listOf("@@ -0,0 +1,2 @@", "a", "b"), changes.lines.map { it.text })
    }

    private suspend fun load(path: String): ConflictedFile {
        val result = GetConflictedFileGitAction(jgit, testPartialCloneFetch(jgit))(File(repository, ".git").path, path)

        return assertInstanceOf(Either.Ok::class.java, result).value as ConflictedFile
    }

    private suspend fun preview(path: String): ConflictPreview = load(path).preview!!
}
