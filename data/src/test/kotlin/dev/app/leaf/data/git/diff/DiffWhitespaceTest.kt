// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.ApplyHunkGitAction
import dev.app.leaf.data.git.workspace.DiscardUnstagedHunkLineGitAction
import dev.app.leaf.data.git.workspace.ResetHunkGitAction
import dev.app.leaf.data.git.workspace.StageHunkGitAction
import dev.app.leaf.data.git.workspace.StageHunkLineGitAction
import dev.app.leaf.data.git.workspace.UnstageHunkGitAction
import dev.app.leaf.data.git.workspace.UnstageHunkLineGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkAction
import dev.app.leaf.domain.errors.WhitespaceIgnoredHunkError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
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
import kotlin.random.Random

/** One hunk as git prints it: its header up to the closing `@@`, and each line with its sign. */
private data class RefHunk(val header: String, val lines: List<Pair<Char, String>>)

private val LATIN_1 = Charsets.ISO_8859_1

/**
 * The diff that ignores whitespace ([DiffWhitespaceMode.IGNORE_ALL]) against `git diff -w`, in the three places that
 * Leaf shows a file's diff: Unstaged, Staged and a commit. Fixed pairs for each kind of whitespace change, and 120
 * random ones. The same pairs without ignoring whitespace are the control, so that a difference that isn't about
 * whitespace shows as one.
 */
class DiffWhitespaceTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit = testJGit()
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

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

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    /** What each change that is only whitespace looks like, and one with a real change among them. */
    private val fixedPairs = mapOf(
        "indent.txt" to ("a\n  b\n  c\nd\n" to "a\n    b\n    c\nd\n"),
        "tabs.txt" to ("a\n\tb\nc\n" to "a\n    b\nc\n"),
        "trailing.txt" to ("a\nb\nc\n" to "a  \nb\t\nc\n"),
        "crlf.txt" to ("a\nb\nc\n" to "a\r\nb\r\nc\r\n"),
        "crlf-back.txt" to ("a\r\nb\r\nc\r\n" to "a\nb\nc\n"),
        "inner.txt" to ("x = a  b\ny\n" to "x = a b\ny\n"),
        "joined.txt" to ("foo bar\nz\n" to "foobar\nz\n"),
        "lead.txt" to ("a\nb\nc\n" to "a\n  b\nc\n"),
        "blank.txt" to ("a\n\nb\n" to "a\n   \nb\n"),
        "no-eol.txt" to ("a\nb" to "a\nb  "),
        "added-blank.txt" to ("a\nb\n" to "a\n\n\nb\n"),
        "real-and-ws.txt" to (
            "a\n  b\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\n" to "a\n    b\nc\nD\ne\nf\ng\nh\ni \nj\nk\nl\nm\n"
        ),
        "context.txt" to ("a\n  b\nc\nd\n" to "a\n    b\nC\nd\n"),
        "far-apart.txt" to (
            (1..30).joinToString("") { "line $it\n" } to
                (1..30).joinToString("") { if (it == 3 || it == 28) "line $it changed\n" else "  line $it\n" }
        ),
    )

    private val randomPairs: Map<String, Pair<String, String>> by lazy {
        val random = Random(20261021)

        (0 until 120).associate { index -> "random-%03d.txt".format(index) to randomPair(random) }
    }

    private val allPairs get() = fixedPairs + randomPairs

    @Test
    fun `hunks match git diff -w in Unstaged, Staged and a commit`(): Unit = runBlocking {
        commitAll("Old versions") { it.first }

        // Unstaged: the index has the old text, the working tree the new
        writeAll { it.second }
        compareAll(
            EntryType.UNSTAGED,
            { mode -> gitDiff(mode) },
            { path -> DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED) },
        )

        // Staged: HEAD has the old text, the index the new
        git.run(repository, "add", "-A")
        compareAll(
            EntryType.STAGED,
            { mode -> gitDiff(mode, "--cached") },
            { path -> DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.STAGED), EntryType.STAGED) },
        )

        // A commit: the same change, from the commit's two trees
        git.run(repository, "commit", "-m", "New versions")
        val commit = commit(git.run(repository, "rev-parse", "HEAD").trim())
        val entries = GetCommitDiffEntriesGitAction(jgit, testPartialCloneFetch(jgit))(gitDir(), commit)
            .let { (it as Either.Ok).value }
            .associateBy { it.newPath }

        compareAll(
            null,
            { mode -> gitDiff(mode, "HEAD~1", "HEAD") },
            { path -> DiffType.CommitDiff(entries.getValue(path)) },
        )
    }

    @Test
    fun `a file with no change but whitespace has no hunks, and says so`(): Unit = runBlocking {
        commitAll("Old versions") { it.first }
        writeAll { it.second }

        val whitespaceOnly = listOf("indent.txt", "tabs.txt", "trailing.txt", "crlf.txt", "crlf-back.txt", "inner.txt", "joined.txt", "lead.txt", "blank.txt", "no-eol.txt")
        val withRealChange = listOf("added-blank.txt", "real-and-ws.txt", "context.txt", "far-apart.txt")

        for (path in whitespaceOnly) {
            val ignored = unstaged(path, DiffWhitespaceMode.IGNORE_ALL)
            assertTrue(ignored.hunks.isEmpty(), "$path: no hunks while whitespace is ignored")
            assertTrue(ignored.onlyWhitespaceChanged, "$path: says that only whitespace changed")

            val shown = unstaged(path, DiffWhitespaceMode.SHOW_ALL)
            assertTrue(shown.hunks.isNotEmpty(), "$path: has hunks while whitespace is shown")
            assertFalse(shown.onlyWhitespaceChanged, "$path: nothing to say when whitespace is shown")
        }

        for (path in withRealChange) {
            val ignored = unstaged(path, DiffWhitespaceMode.IGNORE_ALL)
            assertTrue(ignored.hunks.isNotEmpty(), "$path: still has its real change")
            assertFalse(ignored.onlyWhitespaceChanged, "$path: more than whitespace changed")
        }
    }

    @Test
    fun `JGit's other whitespace comparators find the edits of git's -b and --ignore-space-at-eol`(): Unit = runBlocking {
        // What a later mode of DiffWhitespaceMode would use: the edits, without the diff's hunks around them
        commitAll("Old versions") { it.first }
        writeAll { it.second }

        val comparators = listOf(
            "-b" to RawTextComparator.WS_IGNORE_CHANGE,
            "--ignore-space-at-eol" to RawTextComparator.WS_IGNORE_TRAILING,
            "-w" to RawTextComparator.WS_IGNORE_ALL,
        )

        for ((flag, comparator) in comparators) {
            val expected = parseGitDiff(
                git.run(repository, "diff", "--no-color", "--no-ext-diff", "-U0", "--diff-algorithm=histogram", flag)
            )

            for (path in allPairs.keys.sorted()) {
                val (old, new) = allPairs.getValue(path)
                val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                    .diff(comparator, RawText(old.toByteArray(LATIN_1)), RawText(new.toByteArray(LATIN_1)))
                val actual = edits.map { "@@ -${range(it.beginA, it.lengthA)} +${range(it.beginB, it.lengthB)} @@" }

                assertEquals(expected[path].orEmpty().map { it.header }, actual, "$flag, $path:\n${(old to new).show()}")
            }
        }
    }

    /** A range as git prints it in a header: a line's number alone, `begin,length`, and for none the line before it. */
    private fun range(begin: Int, length: Int) = when (length) {
        0 -> "$begin,0"
        1 -> "${begin + 1}"
        else -> "${begin + 1},$length"
    }

    @Test
    fun `a change of mode or a plain rename has no hunks, but isn't a whitespace change`(): Unit = runBlocking {
        File(repository, "script.sh").writeText("#!/bin/sh\necho hi\n")
        File(repository, "moved.txt").writeText("one\ntwo\n")
        git.run(repository, "add", "-A")
        git.run(repository, "commit", "-m", "Add a script and a file")

        // Only its mode changes
        assertTrue(File(repository, "script.sh").setExecutable(true))
        val mode = unstaged("script.sh", DiffWhitespaceMode.IGNORE_ALL)

        assertTrue(mode.hunks.isEmpty())
        assertFalse(mode.onlyWhitespaceChanged, "Nothing differs in the content, whitespace or not")

        // Only its name changes
        git.run(repository, "mv", "moved.txt", "renamed.txt")
        git.run(repository, "commit", "-m", "Rename it")
        val commit = commit(git.run(repository, "rev-parse", "HEAD").trim())
        val entry = (GetCommitDiffEntriesGitAction(jgit, testPartialCloneFetch(jgit))(gitDir(), commit) as Either.Ok).value.single()
        val renamed = (formatDiff(gitDir(), DiffType.CommitDiff(entry), isDisplayFullFile = false, whitespace = DiffWhitespaceMode.IGNORE_ALL) as Either.Ok)
            .value as DiffResult.Text

        assertTrue(renamed.hunks.isEmpty())
        assertFalse(renamed.onlyWhitespaceChanged)
    }

    @Test
    fun `a context line shows the new version's text, as git does`(): Unit = runBlocking {
        commitAll("Old versions") { it.first }
        writeAll { it.second }

        val hunk = unstaged("context.txt", DiffWhitespaceMode.IGNORE_ALL).hunks.single()
        val contexts = hunk.lines.filter { it.lineType == LineType.CONTEXT }.map { it.text }

        assertEquals(listOf("a\n", "    b\n", "d\n"), contexts, "The second line has the new indentation")

        val shown = unstaged("context.txt", DiffWhitespaceMode.SHOW_ALL).hunks.single()

        assertEquals(
            listOf(LineType.CONTEXT to "a\n", LineType.REMOVED to "  b\n", LineType.REMOVED to "c\n", LineType.ADDED to "    b\n", LineType.ADDED to "C\n", LineType.CONTEXT to "d\n"),
            shown.lines.map { it.lineType to it.text },
            "Without ignoring, the re-indented line is a change, as before",
        )
    }

    @Test
    fun `only the hunks of a diff that ignored whitespace say so`(): Unit = runBlocking {
        commitAll("Old versions") { it.first }
        writeAll { it.second }

        assertTrue(unstaged("far-apart.txt", DiffWhitespaceMode.IGNORE_ALL).hunks.all { it.ignoresWhitespace })
        assertTrue(unstaged("far-apart.txt", DiffWhitespaceMode.SHOW_ALL).hunks.none { it.ignoresWhitespace })
    }

    @Test
    fun `hunk and line actions refuse a hunk of a diff that ignored whitespace, changing nothing`(): Unit = runBlocking {
        val gitCli = testGitCli(mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
        val applyHunk = ApplyHunkGitAction(jgit, gitCli)
        val path = "real-and-ws.txt"

        commitAll("Old versions") { it.first }
        writeAll { it.second }

        // Unstaged: stage and discard
        val unstaged = unstaged(path, DiffWhitespaceMode.IGNORE_ALL)
        val hunk = unstaged.hunks.first()
        val line = hunk.lines.first { it.lineType == LineType.ADDED }
        val before = snapshot(path)

        assertRefused(HunkAction.Stage, path, StageHunkGitAction(applyHunk)(gitDir(), unstaged.diffEntry, hunk))
        assertRefused(HunkAction.Stage, path, StageHunkLineGitAction(applyHunk)(gitDir(), unstaged.diffEntry, hunk, line))
        assertRefused(HunkAction.Discard, path, ResetHunkGitAction(applyHunk)(gitDir(), unstaged.diffEntry, hunk))
        assertRefused(
            HunkAction.Discard,
            path,
            DiscardUnstagedHunkLineGitAction(applyHunk)(gitDir(), unstaged.diffEntry, hunk, line),
        )
        assertEquals(before, snapshot(path), "Nothing changed")

        // Staged: unstage
        git.run(repository, "add", path)
        val stagedDiff = (formatDiff(
            gitDir(),
            DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.STAGED), EntryType.STAGED),
            isDisplayFullFile = false,
            whitespace = DiffWhitespaceMode.IGNORE_ALL,
        ) as Either.Ok).value as DiffResult.Text
        val stagedHunk = stagedDiff.hunks.first()
        val stagedLine = stagedHunk.lines.first { it.lineType == LineType.ADDED }
        val stagedBefore = snapshot(path)

        assertRefused(HunkAction.Unstage, path, UnstageHunkGitAction(applyHunk)(gitDir(), stagedDiff.diffEntry, stagedHunk))
        assertRefused(HunkAction.Unstage, path, UnstageHunkLineGitAction(applyHunk)(gitDir(), stagedDiff.diffEntry, stagedHunk, stagedLine))
        assertEquals(stagedBefore, snapshot(path), "Nothing changed")

        // The same hunk of a diff that shows whitespace is applied as before
        val shown = (formatDiff(
            gitDir(),
            DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.STAGED), EntryType.STAGED),
            isDisplayFullFile = false,
            whitespace = DiffWhitespaceMode.SHOW_ALL,
        ) as Either.Ok).value as DiffResult.Text

        assertEquals(Either.Ok(Unit), UnstageHunkGitAction(applyHunk)(gitDir(), shown.diffEntry, shown.hunks.first()))
    }

    private fun assertRefused(action: HunkAction, path: String, result: Either<Unit, GitError>) {
        val error = assertInstanceOf(WhitespaceIgnoredHunkError::class.java, (result as Either.Err).error)

        assertEquals(action, error.action)
        assertEquals(path, error.path)
    }

    /** The file in the working tree, and in the index, as bytes. */
    private fun snapshot(path: String) =
        File(repository, path).readBytes().toList() to git.run(repository, "ls-files", "--stage", path)

    // The comparison

    private suspend fun compareAll(
        entryType: EntryType?,
        reference: (DiffWhitespaceMode) -> Map<String, List<RefHunk>>,
        diffType: (String) -> DiffType,
    ) {
        for (mode in DiffWhitespaceMode.entries) {
            val expected = reference(mode)
            var withHunks = 0

            for (path in allPairs.keys.sorted()) {
                val hunks = (formatDiff(gitDir(), diffType(path), isDisplayFullFile = false, whitespace = mode) as Either.Ok)
                    .value
                    .let { it as DiffResult.Text }
                    .hunks
                    .map { hunk ->
                        RefHunk(
                            hunk.header,
                            hunk.lines.map { line -> line.lineType.sign() to line.text.removeSuffix("\n").removeSuffix("\r") },
                        )
                    }

                assertEquals(
                    expected[path].orEmpty(),
                    hunks,
                    "$mode, ${entryType ?: "commit"}, $path:\n${allPairs.getValue(path).show()}",
                )

                if (hunks.isNotEmpty()) withHunks++
            }

            assertTrue(withHunks > 0, "$mode: some of the files have hunks")
        }
    }

    private fun LineType.sign() = when (this) {
        LineType.CONTEXT -> ' '
        LineType.ADDED -> '+'
        LineType.REMOVED -> '-'
    }

    private fun Pair<String, String>.show() = "old: ${first.visible()}\nnew: ${second.visible()}"

    private fun String.visible() = replace("\t", "<TAB>").replace("\r", "<CR>").replace("\n", "<LF>")

    // git's side

    /** `git diff` of the repository's files, [-w][DiffWhitespaceMode.IGNORE_ALL] or not, split into each file's hunks. */
    private fun gitDiff(mode: DiffWhitespaceMode, vararg more: String): Map<String, List<RefHunk>> {
        val args = listOf("diff", "--no-color", "--no-ext-diff", "-U3", "--diff-algorithm=histogram") +
            (if (mode.ignoresWhitespace) listOf("-w") else emptyList()) + more
        val output = git.run(repository, *args.toTypedArray())

        return parseGitDiff(output)
    }

    private fun parseGitDiff(output: String): Map<String, List<RefHunk>> {
        val files = linkedMapOf<String, MutableList<RefHunk>>()
        var hunks: MutableList<RefHunk>? = null
        var header: String? = null
        var lines = mutableListOf<Pair<Char, String>>()

        fun finishHunk() {
            if (header != null) hunks?.add(RefHunk(header!!, lines))
            header = null
            lines = mutableListOf()
        }

        for (line in output.split("\n")) {
            when {
                line.startsWith("diff --git ") -> {
                    finishHunk()
                    val path = line.substringAfter(" b/")
                    hunks = files.getOrPut(path) { mutableListOf() }
                }

                line.startsWith("@@ ") -> {
                    finishHunk()
                    // git adds the line that a function starts at after the second @@
                    header = Regex("^@@ -\\S+ \\+\\S+ @@").find(line)!!.value
                }

                header != null && line.isNotEmpty() && line[0] in " +-" ->
                    lines.add(line[0] to line.substring(1).removeSuffix("\r"))
            }
        }

        finishHunk()

        return files
    }

    // The repository

    private fun gitDir() = File(repository, ".git").absolutePath

    private suspend fun unstaged(path: String, mode: DiffWhitespaceMode): DiffResult.Text {
        val diffType = DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED)

        return (formatDiff(gitDir(), diffType, isDisplayFullFile = false, whitespace = mode) as Either.Ok).value as DiffResult.Text
    }

    private fun commitAll(message: String, content: (Pair<String, String>) -> String) {
        writeAll(content)
        git.run(repository, "add", "-A")
        git.run(repository, "commit", "-m", message)
    }

    private fun writeAll(content: (Pair<String, String>) -> String) {
        for ((path, pair) in allPairs) {
            File(repository, path).writeBytes(content(pair).toByteArray(LATIN_1))
        }
    }

    private fun commit(hash: String) = Commit(
        hash = hash,
        message = "",
        committer = Identity("Leaf Test", "test@example.invalid"),
        author = Identity("Leaf Test", "test@example.invalid"),
        date = 0,
        parentsHashes = git.run(repository, "rev-list", "--parents", "-n1", hash).trim().split(" ").drop(1),
    )
}

// Random pairs

private val VOCABULARY = listOf(
    "fun parse(input: String): Result {",
    "val name = \"value\"",
    "return x + 1",
    "if (a && b) {",
    "}",
    "# a comment about the next line",
    "- item one",
    "SELECT id, name FROM users WHERE id = 1;",
    "foo(bar, baz)",
    "x = y",
)

/**
 * An old text of 12 to 28 lines, and a new one with indentation, trailing space, real and other changes. Each line ends
 * with its own number: lines that repeat give JGit's and git's diff algorithms equally short edit scripts to choose
 * from, and they choose differently, whitespace or not. With every line unique, the edits only depend on which lines
 * the comparison calls equal, which is what ignoring whitespace changes.
 */
private fun randomPair(random: Random): Pair<String, String> {
    fun indent(): String = when (random.nextInt(5)) {
        0 -> ""
        1 -> "  "
        2 -> "    "
        3 -> "\t"
        else -> "\t\t"
    }

    var number = 0
    val old = List(12 + random.nextInt(17)) { indent() + VOCABULARY.random(random) + " #${number++}" }
    val new = mutableListOf<String>()

    for (line in old) {
        val body = line.trimStart()

        when (random.nextInt(100)) {
            in 0..47 -> new.add(line)
            in 48..61 -> new.add(indent() + body)
            in 62..69 -> new.add(line + listOf(" ", "  ", "\t").random(random))
            in 70..77 -> new.add(line.replaceFirst(" ", listOf("  ", "\t", "   ").random(random)))
            in 78..83 -> new.add(line.replaceFirst(" ", ""))
            in 84..89 -> new.add(line.replace(" #", " // changed #"))
            in 90..93 -> Unit
            in 94..96 -> {
                new.add(indent() + VOCABULARY.random(random) + " #${number++}")
                new.add(line)
            }

            // One more space in front
            else -> new.add(" $line")
        }
    }

    val crlf = random.nextInt(100) < 15
    val noFinalLineEnding = random.nextInt(100) < 10
    val lineEnding = if (crlf) "\r\n" else "\n"
    var newText = new.joinToString("") { it + lineEnding }

    if (noFinalLineEnding) newText = newText.removeSuffix(lineEnding)

    val oldText = old.joinToString("") { it + "\n" }

    // A pair that is the same would have no diff to compare
    return oldText to if (newText == oldText) newText + "extra\n" else newText
}
