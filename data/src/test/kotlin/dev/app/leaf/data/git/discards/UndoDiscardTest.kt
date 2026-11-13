// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.discards

import dev.app.leaf.common.storage.AppStorage
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
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.ApplyHunkGitAction
import dev.app.leaf.data.git.workspace.DeleteFileGitAction
import dev.app.leaf.data.git.workspace.DiscardAllGitAction
import dev.app.leaf.data.git.workspace.DiscardEntriesGitAction
import dev.app.leaf.data.git.workspace.DiscardUnstagedHunkLineGitAction
import dev.app.leaf.data.git.workspace.GetStatusGitAction
import dev.app.leaf.data.git.workspace.ResetHunkGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.discards.DISCARD_KEPT_DAYS
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardNotices
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.discards.DiscardRestore
import dev.app.leaf.domain.discards.MAX_DISCARDED_BYTES
import dev.app.leaf.domain.discards.RestoreWay
import dev.app.leaf.domain.errors.DiscardRecordError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.RenamedFrom
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Undo of each kind of discard (fork-only, upstream issue 39), in real repositories: what the discard threw away is
 * kept, and Undo gives back the working tree, the index and the status as they were before it, byte for byte, unless
 * a file changed since in a way that can't be merged.
 */
@DisabledOnOs(OS.WINDOWS)
class UndoDiscardTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val gitCli by lazy { testGitCli(shellVariables = isolatedGit) }
    private val jgit = testJGit()

    private val notices = DiscardNotices()
    private val store = DiscardStore(notices)
    private val restorer by lazy { DiscardRestorer(gitCli) }
    private val discardEntries = DiscardEntriesGitAction(jgit, store)
    private val discardAll = DiscardAllGitAction(GetStatusGitAction(jgit), DiscardEntriesGitAction(jgit, store), jgit, store)
    private val deleteFile = DeleteFileGitAction(jgit, store)
    private val applyHunk by lazy { ApplyHunkGitAction(jgit, gitCli, store) }
    private val planRestore by lazy { PlanDiscardRestoreGitAction(jgit, restorer) }
    private val restore by lazy { RestoreDiscardGitAction(jgit, restorer) }
    private val records = GetDiscardRecordsGitAction(jgit, store)

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
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a file's unstaged changes come back as they were`(): Unit = runBlocking {
        commit("a.txt" to "one\ntwo\n", "b.txt" to "b\n")
        write("a.txt", "one\ntwo, edited\n")
        val before = snapshot()

        val record = discard(listOf(unstaged("a.txt")))

        assertEquals("one\ntwo\n", read("a.txt"))
        assertEquals(DiscardKind.CHANGES, record.kind)
        assertEquals(listOf("a.txt"), record.paths)
        assertEquals(record, notices.latest.value?.record, "The tab's notice is the discard")

        assertEquals(mapOf("a.txt" to RestoreWay.AS_IT_WAS), plan(record).ways)
        assertRestored(record, listOf("a.txt"))
        assertEquals(before, snapshot())
        assertEquals(emptyList<DiscardRecord>(), records(), "The record went with the files")
    }

    @Test
    fun `CRLF line endings that core autocrlf converts come back byte for byte`(): Unit = runBlocking {
        git.run(repository, "config", "core.autocrlf", "true")
        commit("win.txt" to "one\r\ntwo\r\n")
        write("win.txt", "one\r\ntwo\r\nthree\r\n")
        val before = snapshot()

        val record = discard(listOf(unstaged("win.txt")))

        assertEquals("one\r\ntwo\r\n", read("win.txt"), "git's checkout writes CRLF")
        assertRestored(record, listOf("win.txt"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `a binary file, its executable bit and a link come back`(): Unit = runBlocking {
        val bytes = ByteArray(256) { it.toByte() }
        commitBytes("data.bin", bytes)
        commit("run.sh" to "echo hi\n", "target.txt" to "t\n", "other.txt" to "o\n")
        File(repository, "link").symlinkTo(File("target.txt"))
        git.run(repository, "add", "link")
        git.run(repository, "commit", "-m", "Link")

        File(repository, "data.bin").writeBytes(bytes.reversedArray())
        setExecutable("run.sh")
        Files.delete(File(repository, "link").toPath())
        File(repository, "link").symlinkTo(File("other.txt"))
        val before = snapshot()

        val record = discard(listOf(unstaged("data.bin"), unstaged("run.sh"), unstaged("link")))

        assertEquals(bytes.toList(), File(repository, "data.bin").readBytes().toList())
        assertFalse(isExecutable("run.sh"))
        assertEquals("target.txt", Files.readSymbolicLink(File(repository, "link").toPath()).toString())

        assertRestored(record, listOf("data.bin", "run.sh", "link"))
        assertEquals(before, snapshot())
        assertTrue(isExecutable("run.sh"))
    }

    @Test
    fun `a file deleted in the working tree is deleted again`(): Unit = runBlocking {
        commit("gone.txt" to "gone\n")
        File(repository, "gone.txt").delete()
        val before = snapshot()

        val record = discard(listOf(unstaged("gone.txt", StatusType.REMOVED)))

        assertEquals("gone\n", read("gone.txt"))
        assertRestored(record, listOf("gone.txt"))
        assertFalse(File(repository, "gone.txt").exists())
        assertEquals(before, snapshot())
    }

    @Test
    fun `untracked files in a selection aren't copied, and a discard that changes nothing keeps no record`(): Unit =
        runBlocking {
            commit("a.txt" to "a\n")
            write("a.txt", "a, edited\n")
            write("new.txt", "new\n")

            val record = discard(listOf(unstaged("a.txt"), unstaged("new.txt", StatusType.ADDED)))

            assertEquals(listOf("a.txt"), record.paths)
            assertEquals(setOf("a, edited\n", "a\n"), copiesOf(record).toSet(), "a.txt, before and after, and not new.txt")

            assertOk(discardEntries(gitDir(), listOf(unstaged("new.txt", StatusType.ADDED)), staged = false))
            assertEquals(listOf(record.id), records().map { it.id }, "Discarding only a new file changes nothing")
        }

    @Test
    fun `a staged discard puts back the index entry and the file`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, staged\n")
        git.run(repository, "add", "a.txt")
        write("a.txt", "a, staged, then edited\n")
        val before = snapshot()

        val record = discard(listOf(staged("a.txt")), staged = true)

        assertEquals(DiscardKind.STAGED_CHANGES, record.kind)
        assertEquals("", git.run(repository, "status", "--porcelain"))

        assertRestored(record, listOf("a.txt"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `a staged new file goes back into the index, and a staged deletion out of it`(): Unit = runBlocking {
        commit("old.txt" to "old\n")
        write("added.txt", "added\n")
        git.run(repository, "add", "added.txt")
        git.run(repository, "rm", "-q", "old.txt")
        val before = snapshot()

        val record = discard(listOf(staged("added.txt", StatusType.ADDED), staged("old.txt", StatusType.REMOVED)), staged = true)

        assertEquals("added\n", read("added.txt"), "Unstaging a new file leaves it")
        assertEquals("old\n", read("old.txt"))
        assertEquals(setOf("added.txt", "old.txt"), record.paths.toSet())

        assertRestored(record, record.paths)
        assertEquals(before, snapshot())
    }

    @Test
    fun `an undone move comes back, the file at its new path with its edits, and the old path goes again`(): Unit =
        runBlocking {
            commit("src/status/Foo.kt" to "foo\n")
            File(repository, "src/changes").mkdir()
            git.run(repository, "mv", "src/status/Foo.kt", "src/changes/Foo.kt")
            write("src/changes/Foo.kt", "foo, staged\n")
            git.run(repository, "add", "src/changes/Foo.kt")
            write("src/changes/Foo.kt", "foo, staged, then edited\n")
            val before = snapshot()
            val moved = StatusEntry(
                "src/changes/Foo.kt",
                StatusType.RENAMED,
                EntryType.STAGED,
                renamedFrom = RenamedFrom("src/status/Foo.kt", 90),
            )

            val record = discard(listOf(moved), staged = true)

            assertEquals("foo\n", read("src/status/Foo.kt"))
            assertFalse(File(repository, "src/changes").exists(), "The move's folder goes with its file")
            assertEquals("", git.run(repository, "status", "--porcelain"))

            assertRestored(record, record.paths)
            assertEquals(before, snapshot())
        }

    @Test
    fun `a submodule's staged commit goes back into the index, and its folder is left alone`(): Unit = runBlocking {
        val library = git.initRepository(File(tempDir, "library"))
        git.run(repository, "-c", "protocol.file.allow=always", "submodule", "add", "-q", library.path, "libs/core")
        git.run(repository, "commit", "-m", "Submodule")
        File(library, "more.txt").writeText("more\n")
        git.run(library, "add", ".")
        git.run(library, "commit", "-m", "More")
        val submodule = File(repository, "libs/core")
        git.run(submodule, "-c", "protocol.file.allow=always", "pull", "-q", "origin", "HEAD")
        git.run(repository, "add", "libs/core")
        val before = snapshot()

        val record = discard(listOf(staged("libs/core")), staged = true)

        assertEquals(listOf("libs/core"), record.paths)
        assertTrue(File(submodule, "more.txt").exists(), "The submodule's checkout is left as it was")

        assertRestored(record, listOf("libs/core"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `Discard all brings back the restored files and the new files it deleted, with their folders`(): Unit =
        runBlocking {
            commit("a.txt" to "a\n", "src/b.txt" to "b\n")
            write("a.txt", "a, edited\n")
            File(repository, "src/b.txt").delete()
            write("build/out/deep/x.bin", "x\n")
            write("notes.txt", "notes\n")
            val before = snapshot()

            val status = GetStatusGitAction(jgit)(gitDir()).let { (it as Either.Ok).value }.unstaged
            assertOk(discardAll(gitDir(), status.filter { it.statusType != StatusType.ADDED }, status.filter { it.statusType == StatusType.ADDED }))
            val record = latest()

            assertEquals(DiscardKind.ALL, record.kind)
            assertEquals(setOf("build/out/deep/x.bin", "notes.txt"), record.deletedPaths.toSet())
            assertFalse(File(repository, "build").exists())
            assertEquals("", git.run(repository, "status", "--porcelain", "--untracked-files=all"))

            assertRestored(record, record.paths)
            assertEquals(before, snapshot())
        }

    @Test
    fun `a discarded hunk and a discarded line come back`(): Unit = runBlocking {
        val lines = (1..20).map { "line $it\n" }
        commit("notes.txt" to lines.joinToString(""))
        write("notes.txt", lines.toMutableList().apply { this[1] = "second ✓\n"; this[17] = "eighteenth ✓\n" }.joinToString(""))
        val before = snapshot()

        val diff = diff("notes.txt")
        assertOk(ResetHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks[0]))
        val hunk = latest()

        assertEquals(DiscardKind.HUNK, hunk.kind)

        val next = diff("notes.txt")
        val line = next.hunks.single().lines.single { it.lineType == LineType.ADDED }
        assertOk(DiscardUnstagedHunkLineGitAction(applyHunk)(gitDir(), next.diffEntry, next.hunks.single(), line))
        val lineRecord = latest()

        assertEquals(DiscardKind.LINE, lineRecord.kind)
        assertEquals(listOf(lineRecord.id, hunk.id), records().map { it.id }, "Newest first")

        assertRestored(lineRecord, listOf("notes.txt"))
        assertRestored(hunk, listOf("notes.txt"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `the first of two discarded hunks comes back on its own, merged, and the second stays discarded`(): Unit =
        runBlocking {
            val lines = (1..20).map { "line $it\n" }
            commit("notes.txt" to lines.joinToString(""))
            write("notes.txt", lines.toMutableList().apply { this[1] = "second ✓\n"; this[17] = "eighteenth ✓\n" }.joinToString(""))

            val diff = diff("notes.txt")
            assertOk(ResetHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks[0]))
            val first = latest()
            val next = diff("notes.txt")
            assertOk(ResetHunkGitAction(applyHunk)(gitDir(), next.diffEntry, next.hunks.single()))

            assertEquals(mapOf("notes.txt" to RestoreWay.MERGED), plan(first).ways)
            assertRestored(first, listOf("notes.txt"))
            assertEquals(lines.toMutableList().apply { this[1] = "second ✓\n" }.joinToString(""), read("notes.txt"))
        }

    @Test
    fun `an agent's edit elsewhere in the file is kept, and the discarded lines come back`(): Unit = runBlocking {
        val lines = (1..20).map { "line $it\n" }
        commit("notes.txt" to lines.joinToString(""))
        write("notes.txt", lines.toMutableList().apply { this[1] = "mine\n" }.joinToString(""))

        val record = discard(listOf(unstaged("notes.txt")))
        write("notes.txt", (lines + "the agent's line\n").joinToString(""))

        assertRestored(record, listOf("notes.txt"))
        assertEquals(lines.toMutableList().apply { this[1] = "mine\n"; add("the agent's line\n") }.joinToString(""), read("notes.txt"))
    }

    @Test
    fun `the executable bit comes back with the lines when the file was edited since`(): Unit = runBlocking {
        val lines = (1..12).map { "echo $it\n" }
        commit("run.sh" to lines.joinToString(""), "tool.sh" to lines.joinToString(""))
        setExecutable("run.sh")
        setExecutable("tool.sh")
        write("run.sh", lines.toMutableList().apply { this[1] = "echo mine\n" }.joinToString(""))
        val record = discard(listOf(unstaged("run.sh"), unstaged("tool.sh")))

        assertFalse(isExecutable("run.sh"))
        assertFalse(isExecutable("tool.sh"))
        // The agent edits both at the end
        write("run.sh", (lines + "echo agent\n").joinToString(""))
        write("tool.sh", (lines + "echo agent\n").joinToString(""))

        assertEquals(mapOf("run.sh" to RestoreWay.MERGED, "tool.sh" to RestoreWay.MERGED), plan(record).ways)
        assertRestored(record, listOf("run.sh", "tool.sh"))

        assertTrue(isExecutable("run.sh"), "The discarded bit is back")
        assertEquals(lines.toMutableList().apply { this[1] = "echo mine\n"; add("echo agent\n") }.joinToString(""), read("run.sh"))
        assertTrue(isExecutable("tool.sh"), "tool.sh's discard only changed its bit, which is back")
        assertEquals((lines + "echo agent\n").joinToString(""), read("tool.sh"))
    }

    @Test
    fun `a file changed since where it was discarded stays, and Undo changes nothing when every file did`(): Unit =
        runBlocking {
            commit("notes.txt" to "one\ntwo\nthree\n", "data.bin" to "\u0000binary\n")
            write("notes.txt", "one\nTWO\nthree\n")
            write("data.bin", "\u0000binary, edited\n")
            val record = discard(listOf(unstaged("notes.txt"), unstaged("data.bin")))

            write("notes.txt", "one\nTwo, by the agent\nthree\n")
            write("data.bin", "\u0000binary, by the agent\n")
            val changed = snapshot()

            assertEquals(
                mapOf("notes.txt" to RestoreWay.CHANGED_SINCE, "data.bin" to RestoreWay.CHANGED_SINCE),
                plan(record).ways,
            )

            val result = restore(gitDir(), record.id)
            val error = assertInstanceOf(DiscardRecordError.ChangedSince::class.java, (result as Either.Err).error)

            assertEquals(listOf("notes.txt", "data.bin"), error.paths)
            assertEquals(changed, snapshot(), "Nothing changed")
            assertEquals(listOf(record.id), records().map { it.id }, "The record stays")
        }

    @Test
    fun `the files that can come back do, those that changed stay in the record for later`(): Unit = runBlocking {
        commit("a.txt" to "a\n", "b.txt" to "b\n")
        write("a.txt", "a, mine\n")
        write("b.txt", "b, mine\n")
        val record = discard(listOf(unstaged("a.txt"), unstaged("b.txt")))
        write("b.txt", "b, the agent's\n")

        val plan = plan(record)

        assertEquals(listOf("a.txt"), plan.restorable)
        assertEquals(listOf("b.txt"), plan.changedSince)

        val first = assertRestored(record, listOf("a.txt"), left = listOf("b.txt"))
        assertEquals(first.record.id, records().single().id)
        assertEquals(listOf("b.txt"), records().single().paths)

        // Once b.txt is as the discard left it again, it can come back too
        write("b.txt", "b\n")
        assertRestored(record, listOf("b.txt"))
        assertEquals("a, mine\n", read("a.txt"))
        assertEquals("b, mine\n", read("b.txt"))
        assertEquals(emptyList<DiscardRecord>(), records())
    }

    @Test
    fun `a file that is as it was before the discard already counts as back`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, mine\n")
        val record = discard(listOf(unstaged("a.txt")))
        write("a.txt", "a, mine\n")

        assertEquals(mapOf("a.txt" to RestoreWay.ALREADY_BACK), plan(record).ways)
        assertRestored(record, listOf("a.txt"))
        assertEquals("a, mine\n", read("a.txt"))
    }

    @Test
    fun `Delete file of a new file and of a nested repository's folder can be undone`(): Unit = runBlocking {
        write("build.log", "log\n")
        val nested = git.initRepository(File(repository, "vendor/tool"))
        File(nested, "local.txt").writeText("unpushed work\n")
        val before = snapshot()

        assertOk(deleteFile(gitDir(), "build.log"))
        val fileRecord = latest()
        assertOk(deleteFile(gitDir(), "vendor/tool"))
        val folderRecord = latest()

        assertEquals(DiscardKind.DELETE, folderRecord.kind)
        assertEquals(listOf("vendor/tool"), folderRecord.deletedPaths)
        assertFalse(File(repository, "build.log").exists())
        assertFalse(nested.exists())

        assertRestored(folderRecord, listOf("vendor/tool"))
        assertRestored(fileRecord, listOf("build.log"))
        assertEquals(before, snapshot())
        assertEquals("unpushed work\n", File(nested, "local.txt").readText())
        assertTrue(File(nested, ".git/HEAD").isFile, "The nested repository's git dir came back")
    }

    @Test
    fun `a file made since where a deleted one was isn't written over`(): Unit = runBlocking {
        write("build.log", "log\n")
        assertOk(deleteFile(gitDir(), "build.log"))
        val record = latest()
        write("build.log", "a new log\n")

        assertEquals(mapOf("build.log" to RestoreWay.CHANGED_SINCE), plan(record).ways)
        assertInstanceOf(DiscardRecordError.ChangedSince::class.java, (restore(gitDir(), record.id) as Either.Err).error)
        assertEquals("a new log\n", read("build.log"))
    }

    @Test
    fun `a staged discard whose index entry changed since stays`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, staged\n")
        git.run(repository, "add", "a.txt")
        val record = discard(listOf(staged("a.txt")), staged = true)

        write("a.txt", "a, staged by the agent\n")
        git.run(repository, "add", "a.txt")

        assertEquals(mapOf("a.txt" to RestoreWay.CHANGED_SINCE), plan(record).ways)
        assertInstanceOf(DiscardRecordError.ChangedSince::class.java, (restore(gitDir(), record.id) as Either.Err).error)
        assertEquals("a, staged by the agent", indexContent("a.txt"))
    }

    @Test
    fun `a discard in a linked worktree is kept in that worktree's git dir`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        val linked = File(tempDir, "linked")
        git.run(repository, "worktree", "add", "-q", "-b", "agent", linked.path)
        File(linked, "a.txt").writeText("a, by the agent\n")
        val linkedGitDir = File(repository, ".git/worktrees/linked")

        assertOk(discardEntries(linkedGitDir.path, listOf(unstaged("a.txt")), staged = false))
        val record = latest()

        assertEquals(linkedGitDir.path, record.gitDir)
        assertTrue(File(linkedGitDir, "${AppStorage.DISCARDED_FOLDER_NAME}/${record.id}/record.json").isFile)
        assertFalse(File(repository, ".git/${AppStorage.DISCARDED_FOLDER_NAME}").exists())

        assertRestored(record, listOf("a.txt"))
        assertEquals("a, by the agent\n", File(linked, "a.txt").readText())
    }

    @Test
    fun `a file that can't be copied stops the discard, which changes nothing`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, mine\n")
        val file = File(repository, "a.txt")
        file.setReadable(false)

        try {
            val result = discardEntries(gitDir(), listOf(unstaged("a.txt")), staged = false)
            val error = assertInstanceOf(DiscardRecordError.NotKept::class.java, (result as Either.Err).error)

            assertEquals("a.txt", error.path)
        } finally {
            file.setReadable(true)
        }

        assertEquals("a, mine\n", read("a.txt"))
        assertEquals(emptyList<DiscardRecord>(), records())
        assertNull(notices.latest.value)
    }

    @Test
    fun `records older than two weeks go, and the oldest go while they take too much`(): Unit = runBlocking {
        commit("a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n")
        val ids = listOf("a.txt", "b.txt", "c.txt").map { path ->
            write(path, "$path, mine\n")
            discard(listOf(unstaged(path))).id
        }
        val root = File(repository, ".git/${AppStorage.DISCARDED_FOLDER_NAME}")

        // a.txt's discard was made more than two weeks ago
        rewrite(File(root, ids[0])) { it.copy(time = it.time - TimeUnit.DAYS.toMillis(DISCARD_KEPT_DAYS + 1L)) }
        assertEquals(listOf(ids[2], ids[1]), records().map { it.id })
        assertFalse(File(root, ids[0]).exists())

        // b.txt's discard takes nearly all that discards may take, and c.txt's is the newest
        rewrite(File(root, ids[1])) { it.copy(size = MAX_DISCARDED_BYTES - 1) }
        assertEquals(listOf(ids[2]), records().map { it.id })

        // The newest is kept, whatever it takes
        rewrite(File(root, ids[2])) { it.copy(size = MAX_DISCARDED_BYTES * 2) }
        assertEquals(listOf(ids[2]), records().map { it.id })
    }

    @Test
    fun `a discard that never finished goes after a day`() {
        val root = File(tempDir, "discarded").apply { mkdirs() }
        val unfinished = File(root, "0000000000001-0000").apply { mkdirs() }

        store.prune(root, keep = null, now = unfinished.lastModified() + TimeUnit.HOURS.toMillis(1))
        assertTrue(unfinished.exists())

        store.prune(root, keep = null, now = unfinished.lastModified() + TimeUnit.DAYS.toMillis(2))
        assertFalse(unfinished.exists())
    }

    @Test
    fun `a staged blob that git's gc removed since comes back from its copy`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, staged\n")
        git.run(repository, "add", "a.txt")
        write("a.txt", "a, staged, then edited\n")
        val blob = git.run(repository, "rev-parse", ":a.txt").trim()
        val before = snapshot()

        val record = discard(listOf(staged("a.txt")), staged = true)
        git.run(repository, "gc", "-q", "--prune=now")
        git.runFailing(repository, "cat-file", "-e", blob)

        assertRestored(record, listOf("a.txt"))
        assertEquals(before, snapshot())
        assertEquals("a, staged", indexContent("a.txt"))
    }

    @Test
    fun `without git, a staged blob that is gone is written by JGit`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, staged\n")
        git.run(repository, "add", "a.txt")
        val blob = git.run(repository, "rev-parse", ":a.txt").trim()
        val before = snapshot()

        val record = discard(listOf(staged("a.txt")), staged = true)
        git.run(repository, "gc", "-q", "--prune=now")
        git.runFailing(repository, "cat-file", "-e", blob)

        // A JGit that hasn't seen the blob yet, and no git to write it
        val withoutGit = DiscardRestorer(testGitCli(isolatedGit, configuredPath = "${tempDir}/missing/git"))
        val result = RestoreDiscardGitAction(testJGit(), withoutGit)(gitDir(), record.id)

        assertEquals(listOf("a.txt"), (result as Either.Ok).value.restored)
        assertEquals(before, snapshot())
        assertEquals("a, staged", indexContent("a.txt"), "The blob is there again")
    }

    @Test
    fun `a link made where a deleted file's folder was isn't written through`(): Unit = runBlocking {
        write("out/x.txt", "x\n")
        val status = GetStatusGitAction(jgit)(gitDir()).let { (it as Either.Ok).value }.unstaged
        assertOk(discardAll(gitDir(), emptyList(), status))
        val record = latest()
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(repository, "out").symlinkTo(outside)

        assertEquals(mapOf("out/x.txt" to RestoreWay.CHANGED_SINCE), plan(record).ways)
        assertInstanceOf(DiscardRecordError.ChangedSince::class.java, (restore(gitDir(), record.id) as Either.Err).error)
        assertEquals(emptyList<String>(), outside.list().orEmpty().toList())
    }

    @Test
    fun `a discard that fails half way keeps what it changed`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, mine\n")
        write("locked/new.txt", "new\n")
        val locked = File(repository, "locked")
        val status = GetStatusGitAction(jgit)(gitDir()).let { (it as Either.Ok).value }.unstaged
        locked.setWritable(false)

        try {
            val result = discardAll(
                gitDir(),
                status.filter { it.statusType != StatusType.ADDED },
                status.filter { it.statusType == StatusType.ADDED },
            )

            assertInstanceOf(Either.Err::class.java, result, "The new file couldn't be moved")
        } finally {
            locked.setWritable(true)
        }

        val record = latest()

        assertEquals(listOf("a.txt"), record.paths, "The restored file, and not the new one that stayed")
        assertEquals("new\n", read("locked/new.txt"))
        assertRestored(record, listOf("a.txt"))
        assertEquals("a, mine\n", read("a.txt"))
    }

    @Test
    fun `a file whose changes went before the discard, as the status was stale, isn't recorded`(): Unit = runBlocking {
        commit("a.txt" to "a\n", "b.txt" to "b\n")
        write("a.txt", "a, mine\n")
        write("b.txt", "b, mine\n")
        // An agent puts b.txt back while the pane still lists it
        write("b.txt", "b\n")

        val record = discard(listOf(unstaged("a.txt"), unstaged("b.txt")))

        assertEquals(listOf("a.txt"), record.paths)
    }

    @Test
    fun `a submodule that moved on, which an unstaged discard leaves alone, isn't recorded`(): Unit = runBlocking {
        val library = git.initRepository(File(tempDir, "library"))
        git.run(repository, "-c", "protocol.file.allow=always", "submodule", "add", "-q", library.path, "libs/core")
        commit("a.txt" to "a\n")
        File(library, "more.txt").writeText("more\n")
        git.run(library, "add", ".")
        git.run(library, "commit", "-m", "More")
        git.run(File(repository, "libs/core"), "-c", "protocol.file.allow=always", "pull", "-q", "origin", "HEAD")
        write("a.txt", "a, mine\n")

        val record = discard(listOf(unstaged("a.txt"), unstaged("libs/core")))

        assertEquals(listOf("a.txt"), record.paths)
        assertTrue(File(repository, "libs/core/more.txt").exists(), "The submodule stays where it moved")
    }

    @Test
    fun `an untracked file in an unstaged selection isn't read, so one that can't be doesn't stop the discard`(): Unit =
        runBlocking {
            commit("a.txt" to "a\n")
            write("a.txt", "a, mine\n")
            write("secret.txt", "secret\n")
            val secret = File(repository, "secret.txt")
            secret.setReadable(false)

            try {
                val record = discard(listOf(unstaged("a.txt"), unstaged("secret.txt", StatusType.ADDED)))

                assertEquals(listOf("a.txt"), record.paths)
                assertEquals("a\n", read("a.txt"))
            } finally {
                secret.setReadable(true)
            }
        }

    @Test
    fun `an index entry staged since, with the file as the discard left it, stays`(): Unit = runBlocking {
        commit("a.txt" to "a\n")
        write("a.txt", "a, staged\n")
        git.run(repository, "add", "a.txt")
        val record = discard(listOf(staged("a.txt")), staged = true)

        // The agent stages other content without touching the file
        val other = git.runWithInput(repository, "a, staged by the agent\n", "hash-object", "-w", "--stdin").trim()
        git.run(repository, "update-index", "--cacheinfo", "100644,$other,a.txt")

        assertEquals(mapOf("a.txt" to RestoreWay.CHANGED_SINCE), plan(record).ways)
        assertInstanceOf(DiscardRecordError.ChangedSince::class.java, (restore(gitDir(), record.id) as Either.Err).error)
        assertEquals("a, staged by the agent", indexContent("a.txt"))
    }

    @Test
    fun `a file, or a link inside the working tree, made where a deleted file's folder was isn't written into`(): Unit =
        runBlocking {
            commit("src/kept.txt" to "kept\n")
            write("out/x.txt", "x\n")
            val status = GetStatusGitAction(jgit)(gitDir()).let { (it as Either.Ok).value }.unstaged
            assertOk(discardAll(gitDir(), emptyList(), status))
            val record = latest()

            write("out", "now a file\n")

            assertEquals(mapOf("out/x.txt" to RestoreWay.CHANGED_SINCE), plan(record).ways)
            assertInstanceOf(DiscardRecordError.ChangedSince::class.java, (restore(gitDir(), record.id) as Either.Err).error)

            File(repository, "out").delete()
            File(repository, "out").symlinkTo(File("src"))

            assertEquals(mapOf("out/x.txt" to RestoreWay.CHANGED_SINCE), plan(record).ways)
            assertFalse(File(repository, "src/x.txt").exists())
        }

    @Test
    fun `an index entry put back has no stat data, so a file of the same size and time isn't taken as unchanged`(): Unit =
        runBlocking {
            commit("a.txt" to "ef\n")
            write("a.txt", "ab\n")
            git.run(repository, "add", "a.txt")
            write("a.txt", "cd\n")
            val before = snapshot()

            val record = discard(listOf(staged("a.txt")), staged = true)

            // The kept file, of the same size, gets the time of the file that the discard left, to the nanosecond
            val folder = File(repository, ".git/${AppStorage.DISCARDED_FOLDER_NAME}/${record.id}")
            val copy = File(folder, "files/${readStored(folder)!!.entries.single().before.copy}").toPath()
            Files.setLastModifiedTime(copy, Files.getLastModifiedTime(File(repository, "a.txt").toPath()))

            assertRestored(record, listOf("a.txt"))

            // Leaf's own status, which compares only the size and the time, as git also compares the inode
            val status = (GetStatusGitAction(jgit)(gitDir()) as Either.Ok).value
            assertEquals(listOf("a.txt"), status.staged.map { it.filePath })
            assertEquals(listOf("a.txt"), status.unstaged.map { it.filePath }, "The file differs from its index entry")
            assertEquals(before, snapshot())
        }

    @Test
    fun `Undo of a record that is gone says so`(): Unit = runBlocking {
        commit("a.txt" to "a\n")

        assertEquals(Either.Err(DiscardRecordError.NotFound), restore(gitDir(), "0000000000001-0000"))
        assertEquals(Either.Err(DiscardRecordError.NotFound), planRestore(gitDir(), "../../config"))
    }

    // Helpers

    private suspend fun discard(entries: List<StatusEntry>, staged: Boolean = false): DiscardRecord {
        assertOk(discardEntries(gitDir(), entries, staged))

        return latest()
    }

    private fun latest(): DiscardRecord = checkNotNull(notices.latest.value) { "No discard was kept" }.record

    private suspend fun plan(record: DiscardRecord) = (planRestore(gitDir(), record.id) as Either.Ok).value

    private suspend fun records() = (records(gitDir()) as Either.Ok).value

    private suspend fun assertRestored(
        record: DiscardRecord,
        restored: List<String>,
        left: List<String> = emptyList(),
    ): DiscardRestore {
        val result = restore(record.gitDir, record.id)
        val value = (result as? Either.Ok)?.value ?: error("Undo failed: $result")

        assertEquals(restored, value.restored)
        assertEquals(left, value.left)

        return value
    }

    /** The contents of the copies that [record] keeps. */
    private fun copiesOf(record: DiscardRecord): List<String> =
        File(repository, ".git/${AppStorage.DISCARDED_FOLDER_NAME}/${record.id}/files").listFiles().orEmpty().map { it.readText() }

    private fun rewrite(folder: File, change: (StoredDiscard) -> StoredDiscard) {
        writeStored(folder, change(readStored(folder)!!))
    }

    private suspend fun diff(path: String): DiffResult.Text {
        val diffType = DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED)

        return (formatDiff(gitDir(), diffType, isDisplayFullFile = false) as Either.Ok).value as DiffResult.Text
    }

    private fun unstaged(path: String, type: StatusType = StatusType.MODIFIED) = StatusEntry(path, type, EntryType.UNSTAGED)

    private fun staged(path: String, type: StatusType = StatusType.MODIFIED) = StatusEntry(path, type, EntryType.STAGED)

    private fun commit(vararg files: Pair<String, String>) {
        for ((path, content) in files) {
            write(path, content)
        }

        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")
    }

    private fun commitBytes(path: String, bytes: ByteArray) {
        File(repository, path).writeBytes(bytes)
        git.run(repository, "add", path)
        git.run(repository, "commit", "-m", "Add $path")
    }

    private fun write(path: String, content: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun read(path: String) = File(repository, path).readText()

    private fun setExecutable(path: String) {
        val file = File(repository, path).toPath()
        Files.setPosixFilePermissions(file, Files.getPosixFilePermissions(file) + PosixFilePermission.OWNER_EXECUTE)
    }

    private fun isExecutable(path: String) =
        PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(File(repository, path).toPath())

    private fun indexContent(path: String): String = git.run(repository, "cat-file", "blob", ":$path").trim()

    private fun gitDir() = File(repository, ".git").absolutePath

    private fun assertOk(result: Either<Unit, GitError>) = assertEquals(Either.Ok(Unit), result)

    /**
     * The working tree file by file (kind, mode, content), outside `.git`, with the index and git's status: what a
     * discard and its Undo must give back.
     */
    private fun snapshot(): String {
        val root = repository.toPath()
        val files = Files.walk(root).use { paths ->
            paths
                .filter { it != root && !root.relativize(it).startsWith(".git") }
                .map { path -> "${root.relativize(path)} ${describe(path)}" }
                .sorted()
                .toList()
        }

        return (files + git.run(repository, "ls-files", "-s") + git.run(repository, "status", "--porcelain"))
            .joinToString("\n")
    }

    private fun describe(path: Path): String = when {
        Files.isSymbolicLink(path) -> "link to ${Files.readSymbolicLink(path)}"
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "folder"
        else -> {
            val digest = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path))
            val mode = Files.getPosixFilePermissions(path).sortedBy { it.ordinal }
            "file ${digest.joinToString("") { "%02x".format(it) }} $mode"
        }
    }
}
