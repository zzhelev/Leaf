// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.diff.CanGenerateTextDiffGitAction
import dev.app.leaf.data.git.diff.FormatDiffGitAction
import dev.app.leaf.data.git.diff.FormatHunksGitAction
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromDiffTypeGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.data.git.diff.TextDiffFromDiffLinesGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkAction
import dev.app.leaf.domain.errors.StaleHunkError
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
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
import java.nio.charset.Charset

private val LATIN_1: Charset = Charsets.ISO_8859_1

/**
 * Staging, unstaging and discarding hunks and lines, with the hunks that the diff pane gets ([FormatDiffGitAction]),
 * checked byte for byte against what git has in the index and what the working tree has.
 */
class HunkActionsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }

    // Keeps the git that Leaf runs away from the developer's config, such as core.autocrlf
    private val gitCli by lazy { testGitCli(shellVariables = isolatedGit) }
    private val jgit = testJGit()
    private val applyHunk by lazy { ApplyHunkGitAction(jgit, gitCli) }

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
        )
    }

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
    fun `stages one hunk of a UTF-8 file and leaves the others unstaged`(): Unit = runBlocking {
        val lines = (1..20).map { "line $it – ünïcödé\n" }
        commit("notes.txt", lines.joinToString(""))
        val changed = lines.toMutableList().apply {
            this[1] = "second line ✓\n"
            this[17] = "eighteenth line ✓\n"
        }.joinToString("")
        write("notes.txt", changed)

        val diff = diff("notes.txt", EntryType.UNSTAGED)
        assertEquals(2, diff.hunks.size)

        assertOk(StageHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks[1]))

        val expected = lines.toMutableList().apply { this[17] = "eighteenth line ✓\n" }.joinToString("")
        assertBytes(expected, staged("notes.txt"), Charsets.UTF_8)
        assertBytes(changed, read("notes.txt"), Charsets.UTF_8)
        assertEquals(1, diff("notes.txt", EntryType.UNSTAGED).hunks.size, "The first hunk is still unstaged")
    }

    @Test
    fun `stages one line of a hunk`(): Unit = runBlocking {
        commit("list.txt", "one\ntwo\nthree\n")
        write("list.txt", "one\nTWO\nthree\nfour\n")

        val diff = diff("list.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()

        assertOk(StageHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "TWO")))
        assertEquals("one\ntwo\nTWO\nthree\n", staged("list.txt").decodeToString(), "The removed line stays")

        val next = diff("list.txt", EntryType.UNSTAGED)
        val nextHunk = next.hunks.single()

        assertOk(StageHunkLineGitAction(applyHunk)(gitDir(), next.diffEntry, nextHunk, nextHunk.line(LineType.REMOVED, "two")))
        assertEquals("one\nTWO\nthree\n", staged("list.txt").decodeToString(), "The added line isn't staged")
        assertEquals("one\nTWO\nthree\nfour\n", read("list.txt").decodeToString())
    }

    @Test
    fun `stages a hunk of a Latin-1 file byte for byte`(): Unit = runBlocking {
        commit("legacy.txt", "café\nnaïve\nfaçade\n", LATIN_1)
        write("legacy.txt", "café\nnaïve über\nfaçade\nrésumé\n", LATIN_1)

        val diff = diff("legacy.txt", EntryType.UNSTAGED)

        assertOk(StageHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks.single()))

        assertBytes("café\nnaïve über\nfaçade\nrésumé\n", staged("legacy.txt"), LATIN_1)
    }

    @Test
    fun `discards a line of a Latin-1 file byte for byte`(): Unit = runBlocking {
        commit("legacy.txt", "café\nnaïve\nfaçade\n", LATIN_1)
        write("legacy.txt", "café\nNAÏVE\nfaçade\nrésumé\n", LATIN_1)

        val diff = diff("legacy.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()
        val removed = hunk.lines.single { it.lineType == LineType.REMOVED }

        assertOk(DiscardUnstagedHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, removed))

        assertBytes("café\nnaïve\nNAÏVE\nfaçade\nrésumé\n", read("legacy.txt"), LATIN_1)
    }

    @Test
    fun `stages a line and discards a hunk of a CRLF file, keeping its line endings`(): Unit = runBlocking {
        commit("windows.txt", "one\r\ntwo\r\nthree\r\n")
        write("windows.txt", "one\r\nTWO\r\nthree\r\nfour\r\n")

        val diff = diff("windows.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()

        assertOk(StageHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "four")))
        assertBytes("one\r\ntwo\r\nthree\r\nfour\r\n", staged("windows.txt"), Charsets.UTF_8)

        val next = diff("windows.txt", EntryType.UNSTAGED)

        assertOk(ResetHunkGitAction(applyHunk)(gitDir(), next.diffEntry, next.hunks.single()))
        assertBytes("one\r\ntwo\r\nthree\r\nfour\r\n", read("windows.txt"), Charsets.UTF_8)
    }

    @Test
    fun `stages and unstages a hunk that gives the last line a line ending`(): Unit = runBlocking {
        commit("short.txt", "a\nb")
        write("short.txt", "a\nb\nc")

        val unstaged = diff("short.txt", EntryType.UNSTAGED)

        assertOk(StageHunkGitAction(applyHunk)(gitDir(), unstaged.diffEntry, unstaged.hunks.single()))
        assertBytes("a\nb\nc", staged("short.txt"), Charsets.UTF_8)

        val staged = diff("short.txt", EntryType.STAGED)

        assertOk(UnstageHunkGitAction(applyHunk)(gitDir(), staged.diffEntry, staged.hunks.single()))
        assertBytes("a\nb", staged("short.txt"), Charsets.UTF_8)
        assertBytes("a\nb\nc", read("short.txt"), Charsets.UTF_8)
    }

    @Test
    fun `stages a line added after a last line without a line ending, which stays last`(): Unit = runBlocking {
        commit("short.txt", "a\r\nb")
        write("short.txt", "a\r\nb\r\nc")

        val diff = diff("short.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()

        assertOk(StageHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "c")))

        // The index's last line can't get a line ending without staging that too, so the new line goes before it
        assertBytes("a\r\nc\r\nb", staged("short.txt"), Charsets.UTF_8)
    }

    @Test
    fun `discards an added last line without a line ending`(): Unit = runBlocking {
        commit("short.txt", "a\nb")
        write("short.txt", "a\nb\nc")

        val diff = diff("short.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()

        assertOk(DiscardUnstagedHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "c")))

        assertBytes("a\nb\n", read("short.txt"), Charsets.UTF_8)
    }

    @Test
    fun `unstages one line of a staged hunk`(): Unit = runBlocking {
        commit("list.txt", "one\ntwo\nthree\n")
        write("list.txt", "one\nTWO\nthree\nfour\n")
        git.run(repository, "add", "list.txt")

        val diff = diff("list.txt", EntryType.STAGED)
        val hunk = diff.hunks.single()

        assertOk(UnstageHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "four")))

        assertEquals("one\nTWO\nthree\n", staged("list.txt").decodeToString())
        assertEquals("one\nTWO\nthree\nfour\n", read("list.txt").decodeToString())
    }

    @Test
    fun `discards a hunk where it moved, as lines were added above it since`(): Unit = runBlocking {
        val lines = (1..12).map { "line $it\n" }
        commit("moved.txt", lines.joinToString(""))
        write("moved.txt", lines.toMutableList().apply { this[9] = "changed\n" }.joinToString(""))

        val diff = diff("moved.txt", EntryType.UNSTAGED)
        write("moved.txt", "added on top\n" + lines.toMutableList().apply { this[9] = "changed\n" }.joinToString(""))

        assertOk(ResetHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks.single()))

        assertEquals("added on top\n" + lines.joinToString(""), read("moved.txt").decodeToString())
    }

    @Test
    fun `discards the copy of a repeated block that the diff showed`(): Unit = runBlocking {
        val block = listOf("p\n", "q\n", "r\n", "NEW\n", "s\n", "t\n", "u\n")
        val filler = (1..12).map { "filler $it\n" }
        val added = (1..10).map { "added $it\n" }
        commit("blocks.txt", (listOf("head\n") + block + filler + (block - "NEW\n")).joinToString(""))
        write("blocks.txt", (added + "head\n" + block + filler + block).joinToString(""))

        val diff = diff("blocks.txt", EntryType.UNSTAGED)
        assertEquals(2, diff.hunks.size)

        // Looking from where the hunk is in the index, git would reach the first copy before the second
        assertOk(ResetHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks[1]))

        val expected = added + "head\n" + block + filler + (block - "NEW\n")
        assertEquals(expected.joinToString(""), read("blocks.txt").decodeToString())
    }

    @Test
    fun `stages the copy of a repeated block that the diff showed`(): Unit = runBlocking {
        val block = listOf("p\n", "q\n", "r\n", "OLD\n", "s\n", "t\n", "u\n")
        val filler = (1..12).map { "filler $it\n" }
        val removed = (1..10).map { "removed $it\n" }
        commit("blocks.txt", (removed + "head\n" + block + filler + block).joinToString(""))
        write("blocks.txt", (listOf("head\n") + block + filler + (block - "OLD\n")).joinToString(""))

        val diff = diff("blocks.txt", EntryType.UNSTAGED)
        assertEquals(2, diff.hunks.size)

        // Looking from where the hunk is in the file, git would reach the first copy in the index before the second
        assertOk(StageHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks[1]))

        val expected = removed + "head\n" + block + filler + (block - "OLD\n")
        assertEquals(expected.joinToString(""), staged("blocks.txt").decodeToString())
    }

    @Test
    fun `stages and discards with core_autocrlf, as git converts the file`(): Unit = runBlocking {
        commit("windows.txt", "one\ntwo\nthree\n")
        git.run(repository, "config", "core.autocrlf", "true")
        // As an editor on Windows saves it
        write("windows.txt", "one\r\nTWO\r\nthree\r\nfour\r\n")

        val diff = diff("windows.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()

        assertOk(StageHunkLineGitAction(applyHunk)(gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "four")))
        assertBytes("one\ntwo\nthree\nfour\n", staged("windows.txt"), Charsets.UTF_8)

        val next = diff("windows.txt", EntryType.UNSTAGED)

        assertOk(ResetHunkGitAction(applyHunk)(gitDir(), next.diffEntry, next.hunks.single()))
        assertBytes("one\r\ntwo\r\nthree\r\nfour\r\n", read("windows.txt"), Charsets.UTF_8)
    }

    @Test
    fun `refuses to discard a line that changed after the diff was loaded, and leaves the file as it is`(): Unit =
        runBlocking {
            commit("agent.txt", "one\ntwo\nthree\n")
            write("agent.txt", "one\nTWO\nthree\n")

            val diff = diff("agent.txt", EntryType.UNSTAGED)
            val hunk = diff.hunks.single()

            // An agent rewrites the line while the diff is shown
            write("agent.txt", "one\nTWO, edited again\nthree\n")

            val result = DiscardUnstagedHunkLineGitAction(applyHunk)(
                gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "TWO"),
            )

            val error = assertStale(result, HunkAction.Discard, "agent.txt")
            assertTrue("patch does not apply" in error.output, error.output)
            assertEquals("one\nTWO, edited again\nthree\n", read("agent.txt").decodeToString())
        }

    @Test
    fun `refuses to stage a hunk whose staged version changed after the diff was loaded`(): Unit = runBlocking {
        commit("list.txt", "one\ntwo\nthree\n")
        write("list.txt", "one\nTWO\nthree\n")

        val diff = diff("list.txt", EntryType.UNSTAGED)

        write("list.txt", "one\nTWO\nTHREE\n")
        git.run(repository, "add", "list.txt")

        assertStale(StageHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks.single()), HunkAction.Stage, "list.txt")
        assertEquals("one\nTWO\nTHREE\n", staged("list.txt").decodeToString())
    }

    @Test
    fun `stages trailing whitespace as it is, whatever apply_whitespace says`(): Unit = runBlocking {
        git.run(repository, "config", "apply.whitespace", "fix")
        commit("list.txt", "one\ntwo\nthree\n")
        write("list.txt", "one\ntwo  \nthree\t\n")

        val diff = diff("list.txt", EntryType.UNSTAGED)

        assertOk(StageHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks.single()))

        assertEquals("one\ntwo  \nthree\t\n", staged("list.txt").decodeToString())
    }

    @Test
    fun `refuses a line whose whitespace changed since, whatever apply_ignoreWhitespace says`(): Unit = runBlocking {
        git.run(repository, "config", "apply.ignoreWhitespace", "change")
        commit("list.txt", "one\ntwo\nthree\n")
        write("list.txt", "one\nTWO 2\nthree\n")

        val diff = diff("list.txt", EntryType.UNSTAGED)
        val hunk = diff.hunks.single()

        // A change that apply.ignoreWhitespace=change ignores: more spaces between words
        write("list.txt", "one\nTWO  2\nthree\n")

        val result = DiscardUnstagedHunkLineGitAction(applyHunk)(
            gitDir(), diff.diffEntry, hunk, hunk.line(LineType.ADDED, "TWO"),
        )

        assertStale(result, HunkAction.Discard, "list.txt")
        assertEquals("one\nTWO  2\nthree\n", read("list.txt").decodeToString())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Windows doesn't allow tabs in file names")
    fun `stages a hunk of a file whose name has spaces, accents and a tab`(): Unit = runBlocking {
        // A tab ends a name in a patch unless the name is quoted
        val path = "my folder/naïve\tfile.txt"
        commit(path, "one\ntwo\n")
        write(path, "one\n2\n")

        val diff = diff(path, EntryType.UNSTAGED)

        assertOk(StageHunkGitAction(applyHunk)(gitDir(), diff.diffEntry, diff.hunks.single()))

        assertEquals("one\n2\n", staged(path).decodeToString())
    }

    @Test
    fun `stages an untracked file's hunk with its mode, and unstaging it leaves the file untracked`(): Unit =
        runBlocking {
            write("run.sh", "#!/bin/sh\necho hi\n")
            File(repository, "run.sh").setExecutable(true)

            val unstaged = diff("run.sh", EntryType.UNSTAGED, StatusType.ADDED)
            assertEquals(DiffEntry.ChangeType.ADD, unstaged.diffEntry.changeType)

            assertOk(StageHunkGitAction(applyHunk)(gitDir(), unstaged.diffEntry, unstaged.hunks.single()))
            assertEquals("#!/bin/sh\necho hi\n", staged("run.sh").decodeToString())
            assertTrue(git.run(repository, "ls-files", "--stage", "run.sh").startsWith("100755 "))

            val staged = diff("run.sh", EntryType.STAGED, StatusType.ADDED)

            assertOk(UnstageHunkGitAction(applyHunk)(gitDir(), staged.diffEntry, staged.hunks.single()))
            assertEquals("", git.run(repository, "ls-files", "run.sh"))
            assertEquals("#!/bin/sh\necho hi\n", read("run.sh").decodeToString())
        }

    @Test
    fun `fails without a usable git, changing nothing`(): Unit = runBlocking {
        commit("list.txt", "one\ntwo\n")
        write("list.txt", "one\nTWO\n")

        val diff = diff("list.txt", EntryType.UNSTAGED)
        val withoutGit = ApplyHunkGitAction(jgit, testGitCli(isolatedGit, configuredPath = "${tempDir}/missing/git"))

        val result = StageHunkGitAction(withoutGit)(gitDir(), diff.diffEntry, diff.hunks.single())

        assertInstanceOf(GitCliError.InvalidConfiguredPath::class.java, (result as Either.Err).error)
        assertEquals("one\ntwo\n", staged("list.txt").decodeToString())
    }

    private fun commit(path: String, content: String, charset: Charset = Charsets.UTF_8) {
        write(path, content, charset)
        git.run(repository, "add", path)
        git.run(repository, "commit", "-m", "Add $path")
    }

    private fun write(path: String, content: String, charset: Charset = Charsets.UTF_8) {
        File(repository, path).apply { parentFile.mkdirs() }.writeBytes(content.toByteArray(charset))
    }

    private fun read(path: String): ByteArray = File(repository, path).readBytes()

    /** What the index has for [path], as git reads it. */
    private fun staged(path: String): ByteArray {
        val process = ProcessBuilder("git", "cat-file", "blob", ":$path")
            .directory(repository)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment().putAll(isolatedGit) }
            .start()

        val bytes = process.inputStream.readBytes()
        check(process.waitFor() == 0) { "$path isn't in the index" }

        return bytes
    }

    private fun gitDir() = File(repository, ".git").absolutePath

    private suspend fun diff(
        path: String,
        entryType: EntryType,
        statusType: StatusType = StatusType.MODIFIED,
    ): DiffResult.Text {
        val diffType = DiffType.UncommittedDiff(StatusEntry(path, statusType, entryType), entryType)
        val result = formatDiff(gitDir(), diffType, isDisplayFullFile = false)

        return (result as Either.Ok).value as DiffResult.Text
    }

    private fun Hunk.line(type: LineType, textStart: String): Line =
        lines.single { it.lineType == type && it.text.startsWith(textStart) }

    private fun assertOk(result: Either<Unit, GitError>) = assertEquals(Either.Ok(Unit), result)

    private fun assertStale(result: Either<Unit, GitError>, action: HunkAction, path: String): StaleHunkError {
        val error = assertInstanceOf(StaleHunkError::class.java, (result as Either.Err).error)
        assertEquals(action, error.action)
        assertEquals(path, error.path)

        return error
    }

    /** Compares bytes as text in [charset], so that a failure shows the text, and as bytes. */
    private fun assertBytes(expected: String, actual: ByteArray, charset: Charset) {
        assertEquals(expected, actual.toString(charset))
        assertEquals(expected.toByteArray(charset).toList(), actual.toList())
    }
}
