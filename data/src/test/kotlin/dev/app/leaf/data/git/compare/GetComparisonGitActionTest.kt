// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

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
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonMode.ALL
import dev.app.leaf.domain.models.ComparisonMode.SINCE_SPLIT
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.MAX_COMPARED_COMMITS
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Compares commits and branches: `agent1` forked from `main`, which got a commit of its own afterwards, as the base
 * moves on while an agent works. Each comparison is checked against what git lists for it.
 */
class GetComparisonGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val compare by lazy { GetComparisonGitAction(jgit, JGitCommitMapper(JGitIdentityMapper()), testPartialCloneFetch(jgit)) }

    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir by lazy { File(repo, ".git").path }
    private lateinit var forkPoint: String

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        repo.mkdirs()
        git.run(repo, "init")
        write("a.txt", "line1\nline2\nline3\n")
        write("b.txt", "b\n")
        commit("Initial commit")
        forkPoint = git.run(repo, "rev-parse", "HEAD").trim()

        git.run(repo, "checkout", "-q", "-b", "agent1")
        write("a.txt", "login\nline2\nline3\n")
        write("retry.txt", "retry\n")
        commit("Add the login retry")
        write("test/retry_test.txt", "test\n")
        commit("Test the retry limit")

        git.run(repo, "checkout", "-q", "main")
        write("a.txt", "line1\nline2\nmain\n")
        write("b.txt", "b on main\n")
        commit("Update main")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lists the branch's commits oldest first, and its changes since the merge base without the base's own`(): Unit =
        runBlocking {
            val comparison = comparison("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT)

            assertEquals(forkPoint, comparison.mergeBase)
            assertEquals(ComparisonRelation.DIVERGED, comparison.relation)
            assertEquals(
                listOf("Add the login retry", "Test the retry limit"),
                comparison.commitsOnlyInB.map { it.shortMessage },
            )
            assertEquals(2, comparison.countOnlyInB)
            assertEquals(listOf("Update main"), comparison.commitsOnlyInA.map { it.shortMessage })
            assertEquals(1, comparison.countOnlyInA)
            assertEquals(
                listOf("a.txt" to ChangeType.MODIFY, "retry.txt" to ChangeType.ADD, "test/retry_test.txt" to ChangeType.ADD),
                comparison.changes.map { it.filePath to it.changeType },
            )
        }

    @Test
    fun `all differences include the base's own changes, as if the branch had undone them`(): Unit = runBlocking {
        val comparison = comparison("refs/heads/main", "refs/heads/agent1", ALL)

        assertEquals(ALL, comparison.mode)
        assertEquals(
            listOf(
                "a.txt" to ChangeType.MODIFY,
                "b.txt" to ChangeType.MODIFY,
                "retry.txt" to ChangeType.ADD,
                "test/retry_test.txt" to ChangeType.ADD,
            ),
            comparison.changes.map { it.filePath to it.changeType },
        )

        val diff = formatDiff()(gitDir, DiffType.CommitDiff(comparison.changes.first { it.filePath == "b.txt" }), false)
        val lines = assertInstanceOf(DiffResult.Text::class.java, (diff as Either.Ok).value).hunks.flatMap { it.lines }

        assertEquals(
            listOf(LineType.REMOVED to "b on main", LineType.ADDED to "b"),
            lines.map { it.lineType to it.text.trimEnd('\n') },
        )
    }

    @Test
    fun `every pair of sides, in both modes, lists the changes, commits and relation that git gives`(): Unit =
        runBlocking {
            // A tag, a commit id, an orphan branch and a branch that moves files, besides main and agent1
            git.run(repo, "tag", "-a", "v1", "-m", "Release 1", forkPoint)
            git.run(repo, "checkout", "-q", "-b", "mover")
            val guide = (1..20).joinToString("") { "line $it of the guide\n" }
            write("guide.txt", guide)
            commit("Add the guide")
            git.run(repo, "mv", "guide.txt", "guide-moved.txt")
            git.run(repo, "mv", "b.txt", "c.txt")
            write("guide-moved.txt", guide.replace("line 3 of", "edited line 3 of"))
            commit("Move the guide and b")
            git.run(repo, "checkout", "-q", "--orphan", "pages")
            git.run(repo, "rm", "-rqf", ".")
            File(repo, "index.html").writeText("<html>\n")
            git.run(repo, "add", "index.html")
            commit("Start the pages")
            git.run(repo, "checkout", "-q", "-f", "main")

            val sides = listOf("refs/heads/main", "refs/heads/agent1", "refs/heads/mover", "refs/heads/pages", "refs/tags/v1", forkPoint)
            val relations = mutableSetOf<ComparisonRelation>()

            for (a in sides) for (b in sides) for (mode in ComparisonMode.entries) {
                val comparison = comparison(a, b, mode)
                val what = "$a and $b, $mode"

                assertEquals(a, comparison.a, what)
                assertEquals(b, comparison.b, what)
                assertEquals(mode, comparison.mode, what)
                assertEquals(gitRelation(a, b), comparison.relation, what)
                assertEquals(gitChanges(a, b, mode), comparison.changes.map { it.nameStatus() }, what)
                assertEquals(gitCommits(only = b, notIn = a), comparison.commitsOnlyInB.map { it.hash }, what)
                assertEquals(gitCommits(only = a, notIn = b), comparison.commitsOnlyInA.map { it.hash }, what)
                assertEquals(comparison.commitsOnlyInB.size, comparison.countOnlyInB, what)
                assertEquals(comparison.commitsOnlyInA.size, comparison.countOnlyInA, what)
                relations += comparison.relation
            }

            // Every relation was there to compare
            assertEquals(ComparisonRelation.entries.toSet(), relations)
        }

    @Test
    fun `since they split lists nothing for histories without a commit in common, which git refuses`(): Unit =
        runBlocking {
            createOrphan("pages")

            val since = comparison("refs/heads/main", "refs/heads/pages", SINCE_SPLIT)
            val all = comparison("refs/heads/main", "refs/heads/pages", ALL)

            assertNull(since.mergeBase)
            assertEquals(ComparisonRelation.UNRELATED, since.relation)
            assertEquals(emptyList<Any>(), since.changes)
            assertTrue("no merge base" in git.runFailing(repo, "diff", "--name-status", "refs/heads/main...refs/heads/pages"))
            assertEquals(listOf("Start the pages"), since.commitsOnlyInB.map { it.shortMessage })
            assertEquals(2, since.countOnlyInA)
            assertEquals(
                listOf("D\ta.txt", "D\tb.txt", "A\tindex.html"),
                all.changes.map { it.nameStatus() },
            )
        }

    @Test
    fun `with several merge bases, since they split compares from the one it names, which is one of git's`(): Unit =
        runBlocking {
            // A criss-cross: each branch merges the other's first commit
            git.run(repo, "checkout", "-q", "-b", "left", forkPoint)
            write("left.txt", "left\n")
            commit("Left")
            git.run(repo, "checkout", "-q", "-b", "right", forkPoint)
            write("right.txt", "right\n")
            commit("Right")
            git.run(repo, "merge", "-q", "--no-edit", "left")
            git.run(repo, "checkout", "-q", "left")
            git.run(repo, "merge", "-q", "--no-edit", "right~1")
            write("left.txt", "left again\n")
            commit("Left again")
            git.run(repo, "checkout", "-q", "right")
            write("right.txt", "right again\n")
            commit("Right again")

            val comparison = comparison("refs/heads/left", "refs/heads/right", SINCE_SPLIT)
            val gitMergeBases = git.run(repo, "merge-base", "--all", "left", "right").lines().filter { it.isNotBlank() }

            assertEquals(2, gitMergeBases.size)
            assertTrue(comparison.mergeBase in gitMergeBases, "${comparison.mergeBase} in $gitMergeBases")
            assertEquals(ComparisonRelation.DIVERGED, comparison.relation)
            assertEquals(
                nameStatus(git.run(repo, "diff", "--name-status", comparison.mergeBase!!, "refs/heads/right")),
                comparison.changes.map { it.nameStatus() },
            )
        }

    @Test
    fun `a file's diff is from the merge base, so the base's later change to it isn't shown`(): Unit = runBlocking {
        val entry = comparison("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT).changes.first { it.filePath == "a.txt" }

        val diff = formatDiff()(gitDir, DiffType.CommitDiff(entry), isDisplayFullFile = false)

        val lines = assertInstanceOf(DiffResult.Text::class.java, (diff as Either.Ok).value).hunks.flatMap { it.lines }
        assertEquals(
            listOf(
                LineType.REMOVED to "line1",
                LineType.ADDED to "login",
                LineType.CONTEXT to "line2",
                LineType.CONTEXT to "line3",
            ),
            lines.map { it.lineType to it.text.trimEnd('\n') },
        )
    }

    @Test
    fun `a tag and a commit id compare as their commits, a remote branch as a local one`(): Unit = runBlocking {
        git.run(repo, "tag", "-a", "v1", "-m", "Release 1", forkPoint)
        git.run(repo, "update-ref", "refs/remotes/origin/main", "refs/heads/main")
        val agent1 = git.run(repo, "rev-parse", "agent1").trim()

        val byBranch = comparison("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT)
        val byRemote = comparison("refs/remotes/origin/main", agent1, SINCE_SPLIT)
        val byTag = comparison("refs/tags/v1", "refs/heads/agent1", ALL)
        val byCommit = comparison(forkPoint, agent1, ALL)

        // JGit's DiffEntry has no equals of its own
        assertEquals(
            byBranch.copy(a = "refs/remotes/origin/main", b = agent1, changes = emptyList()),
            byRemote.copy(changes = emptyList()),
        )
        assertEquals(byBranch.changes.map { it.described() }, byRemote.changes.map { it.described() })
        assertEquals(byCommit.copy(a = "refs/tags/v1", b = "refs/heads/agent1", changes = emptyList()), byTag.copy(changes = emptyList()))
        assertEquals(byCommit.changes.map { it.described() }, byTag.changes.map { it.described() })
        assertEquals(ComparisonRelation.A_IS_ANCESTOR, byTag.relation)
    }

    @Test
    fun `a branch that the base already has brings nothing since they split`(): Unit = runBlocking {
        git.run(repo, "branch", "done", forkPoint)

        val comparison = comparison("refs/heads/main", "refs/heads/done", SINCE_SPLIT)

        assertEquals(forkPoint, comparison.mergeBase)
        assertEquals(ComparisonRelation.B_IS_ANCESTOR, comparison.relation)
        assertEquals(emptyList<Any>(), comparison.commitsOnlyInB)
        assertEquals(0, comparison.countOnlyInB)
        assertEquals(1, comparison.countOnlyInA)
        assertEquals(emptyList<Any>(), comparison.changes)
    }

    @Test
    fun `a side that doesn't exist is an error that names it`(): Unit = runBlocking {
        assertEquals(
            Either.Err(GenericError("gone doesn't exist.")),
            compare(gitDir, "refs/heads/main", "refs/heads/gone", SINCE_SPLIT),
        )
        assertEquals(
            Either.Err(GenericError("origin/main doesn't exist.")),
            compare(gitDir, "refs/remotes/origin/main", "refs/heads/agent1", ALL),
        )
    }

    @Test
    fun `lists each side's newest commits when there are too many, and counts them all`(): Unit = runBlocking {
        val total = MAX_COMPARED_COMMITS + 3
        createCommits("refs/heads/long", from = "refs/heads/main", count = total)

        val onB = comparison("refs/heads/main", "refs/heads/long", SINCE_SPLIT)
        val onA = comparison("refs/heads/long", "refs/heads/main", SINCE_SPLIT)

        assertEquals(total, onB.countOnlyInB)
        assertEquals(MAX_COMPARED_COMMITS, onB.commitsOnlyInB.size)
        assertEquals("Commit 4", onB.commitsOnlyInB.first().shortMessage)
        assertEquals("Commit $total", onB.commitsOnlyInB.last().shortMessage)
        assertEquals(total, onA.countOnlyInA)
        assertEquals(onB.commitsOnlyInB.map { it.hash }, onA.commitsOnlyInA.map { it.hash })
    }

    @Test
    fun `files that the branch moved are renames, and the diff of a moved file is from the old one`(): Unit = runBlocking {
        val text = (1..20).joinToString("") { "line $it of the guide\n" }
        write("guide.txt", text)
        write("notes.txt", "notes ".repeat(10) + "\n" + text)
        commit("Add the guide and the notes")
        git.run(repo, "checkout", "-q", "-b", "mover")
        File(repo, "docs").mkdirs()
        git.run(repo, "mv", "guide.txt", "docs/guide.txt")
        git.run(repo, "mv", "notes.txt", "docs/notes.txt")
        write("docs/notes.txt", ("notes ".repeat(10) + "\n" + text).replace("line 3 of", "edited line 3 of"))
        commit("Move the guide and the notes into docs")

        val comparison = comparison("refs/heads/main", "refs/heads/mover", SINCE_SPLIT)

        assertEquals(
            listOf(
                Triple("docs/guide.txt", ChangeType.RENAME, "guide.txt"),
                Triple("docs/notes.txt", ChangeType.RENAME, "notes.txt"),
            ),
            comparison.changes.map { Triple(it.filePath, it.changeType, it.oldPath) },
        )
        assertEquals(100, comparison.changes[0].score)

        // The moved file with edits shows what changed in it, as a diff of the old file
        val edited = formatDiff()(gitDir, DiffType.CommitDiff(comparison.changes[1]), isDisplayFullFile = false)
        val lines = assertInstanceOf(DiffResult.Text::class.java, (edited as Either.Ok).value).hunks.flatMap { it.lines }

        assertEquals(
            listOf(LineType.REMOVED to "line 3 of the guide", LineType.ADDED to "edited line 3 of the guide"),
            lines.filter { it.lineType != LineType.CONTEXT }.map { it.lineType to it.text.trimEnd('\n') },
        )

        // The moved file without edits has nothing to show: the diff pane says that it was renamed
        for (isDisplayFullFile in listOf(false, true)) {
            val unchanged = formatDiff()(gitDir, DiffType.CommitDiff(comparison.changes[0]), isDisplayFullFile)

            assertEquals(
                emptyList<Any>(),
                assertInstanceOf(DiffResult.Text::class.java, (unchanged as Either.Ok).value).hunks,
                "Full file: $isDisplayFullFile",
            )
        }
    }

    /** How git relates [a] and [b], from its own answers rather than from commit counts. */
    private fun gitRelation(a: String, b: String): ComparisonRelation {
        fun succeeds(vararg args: String) = runCatching { git.run(repo, *args) }.isSuccess

        return when {
            git.run(repo, "rev-parse", "$a^{commit}") == git.run(repo, "rev-parse", "$b^{commit}") -> ComparisonRelation.SAME
            succeeds("merge-base", "--is-ancestor", a, b) -> ComparisonRelation.A_IS_ANCESTOR
            succeeds("merge-base", "--is-ancestor", b, a) -> ComparisonRelation.B_IS_ANCESTOR
            succeeds("merge-base", a, b) -> ComparisonRelation.DIVERGED
            else -> ComparisonRelation.UNRELATED
        }
    }

    /** What `git diff --name-status` lists for [mode]: nothing for `A...B` when git refuses it for lack of a merge base. */
    private fun gitChanges(a: String, b: String, mode: ComparisonMode): List<String> = when (mode) {
        ComparisonMode.ALL -> nameStatus(git.run(repo, "diff", "--name-status", a, b))
        ComparisonMode.SINCE_SPLIT -> runCatching { git.run(repo, "diff", "--name-status", "$a...$b") }
            .map { nameStatus(it) }
            .getOrElse { error ->
                assertTrue("no merge base" in error.message.orEmpty(), error.message)
                emptyList()
            }
    }

    /** The commits that [only] has and [notIn] lacks, oldest first, as `git rev-list` lists them. */
    private fun gitCommits(only: String, notIn: String): List<String> =
        git.run(repo, "rev-list", "--topo-order", "--reverse", only, "^$notIn").lines().filter { it.isNotBlank() }

    /** `git diff --name-status` lines by path, with a rename's score left out: `R\told\tnew`. */
    private fun nameStatus(output: String): List<String> = output.lines()
        .filter { '\t' in it }
        .map { line ->
            val parts = line.split('\t')
            (listOf(parts[0].take(1)) + parts.drop(1)).joinToString("\t")
        }
        .sortedBy { it.split('\t').last() }

    private fun DiffEntry.nameStatus(): String = when (changeType) {
        ChangeType.ADD -> "A\t$newPath"
        ChangeType.DELETE -> "D\t$oldPath"
        ChangeType.MODIFY -> "M\t$newPath"
        ChangeType.RENAME -> "R\t$oldPath\t$newPath"
        ChangeType.COPY -> "C\t$oldPath\t$newPath"
        null -> "?"
    }

    private fun DiffEntry.described() = "$changeType $oldPath ${oldId.name()} $newPath ${newId.name()}"

    private suspend fun comparison(a: String, b: String, mode: ComparisonMode): Comparison =
        (compare(gitDir, a, b, mode) as Either.Ok).value

    private fun createOrphan(branch: String) {
        git.run(repo, "checkout", "-q", "--orphan", branch)
        git.run(repo, "rm", "-rqf", ".")
        File(repo, "index.html").writeText("<html>\n")
        git.run(repo, "add", "index.html")
        commit("Start the pages")
        git.run(repo, "checkout", "-q", "-f", "main")
    }

    private fun write(path: String, text: String) {
        val file = File(repo, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun commit(message: String) {
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", message)
    }

    /** Commits [count] empty commits on [ref], from [from], with JGit: a thousand `git commit` runs take too long. */
    private fun createCommits(ref: String, from: String, count: Int) {
        Git.open(repo).use { git ->
            val repository = git.repository
            var parent = repository.resolve(from)
            val tree = repository.parseCommit(parent).tree

            repository.newObjectInserter().use { inserter ->
                for (number in 1..count) {
                    val ident = PersonIdent("Leaf Test", "test@example.invalid", Instant.ofEpochSecond(1_700_000_000L + number), ZoneOffset.UTC)

                    parent = inserter.insert(
                        CommitBuilder().apply {
                            setTreeId(tree)
                            setParentId(parent)
                            author = ident
                            committer = ident
                            message = "Commit $number"
                        }
                    )
                }

                inserter.flush()
            }

            repository.updateRef(ref).apply { setNewObjectId(parent) }.forceUpdate()
        }
    }

    private fun formatDiff(): FormatDiffGitAction {
        val getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper())
        val getCurrentBranch = GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit)

        return FormatDiffGitAction(
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
}
