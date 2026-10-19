// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration

/**
 * Moved files are renames in a commit's file list, as git shows them. `git.diff()` found them only with `diff.renames`
 * set, which JGit reads as off when it isn't. The tests compare with what git says (`git diff -M --name-status`).
 */
class RenameDetectionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir get() = File(repo, ".git").path

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        repo.mkdirs()
        git.run(repo, "init")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a move that changes nothing is one rename of the same blob`(): Unit = runBlocking {
        write("old/Parser.kt", lines("Parser", 20))
        commit("Add the parser")
        git.run(repo, "mv", "old", "src")
        val moved = commit("Move the parser")

        val entry = entries(moved).single()

        assertEquals(ChangeType.RENAME, entry.changeType)
        assertEquals("old/Parser.kt", entry.oldPath)
        assertEquals("src/Parser.kt", entry.newPath)
        assertEquals("src/Parser.kt", entry.filePath)
        assertEquals(100, entry.score)
        assertEquals(entry.oldId, entry.newId)
        assertEquals(gitSays(moved), described(entries(moved)))
    }

    @Test
    fun `a move that edits the file is a rename with its similarity`(): Unit = runBlocking {
        write("old/Parser.kt", lines("Parser", 20))
        commit("Add the parser")
        git.run(repo, "mv", "old/Parser.kt", "Parser.kt")
        write("Parser.kt", lines("Parser", 20).replace("Parser 0 ", "Edited 0 "))
        val moved = commit("Move and edit the parser")

        val entry = entries(moved).single()

        assertEquals(ChangeType.RENAME, entry.changeType)
        assertEquals("old/Parser.kt", entry.oldPath)
        assertEquals("Parser.kt", entry.newPath)
        assertTrue(entry.score in 50..99, "The score is ${entry.score}")
        assertNotEquals(entry.oldId, entry.newId)
        assertEquals(gitSays(moved), described(entries(moved)))
    }

    @Test
    fun `pairs files that are half alike, as git does, where JGit's own threshold is 60 percent`(): Unit = runBlocking {
        write("src/Lexer.kt", lines("Lexer", 20))
        commit("Add the lexer")
        git.run(repo, "mv", "src/Lexer.kt", "src/Tokens.kt")
        write("src/Tokens.kt", lines("Lexer", 20).lines().mapIndexed { i, line -> if (i < 9) "changed ".padEnd(line.length, 'x') else line }.joinToString("\n"))
        val renamed = commit("Rename and rewrite part of the lexer")

        val entry = entries(renamed).single()

        assertEquals(ChangeType.RENAME, entry.changeType)
        assertTrue(entry.score in 50..59, "The score is ${entry.score}")
        assertEquals(gitSays(renamed), described(entries(renamed)))
    }

    @Test
    fun `a rename in its folder, with a few changes, pairs by content`(): Unit = runBlocking {
        write("src/Lexer.kt", lines("Lexer", 20))
        write("src/Other.kt", lines("Other", 20))
        commit("Add the lexer")
        git.run(repo, "mv", "src/Lexer.kt", "src/Tokens.kt")
        write("src/Tokens.kt", lines("Lexer", 20).replace("Lexer 3 ", "Tokens 3 "))
        val renamed = commit("Rename the lexer")

        val entry = entries(renamed).single()

        assertEquals(ChangeType.RENAME, entry.changeType)
        assertEquals("src/Lexer.kt" to "src/Tokens.kt", entry.oldPath to entry.newPath)
        assertEquals(gitSays(renamed), described(entries(renamed)))
    }

    @Test
    fun `files that were only changed, added or deleted stay what they were`(): Unit = runBlocking {
        write("a.txt", lines("a", 10))
        write("b.txt", lines("b", 10))
        commit("Start")
        write("a.txt", lines("a", 10) + "more\n")
        write("c.txt", lines("c", 10))
        git.run(repo, "rm", "-q", "b.txt")
        val second = commit("Change")

        assertEquals(
            listOf(
                "a.txt" to ChangeType.MODIFY,
                "b.txt" to ChangeType.DELETE,
                "c.txt" to ChangeType.ADD,
            ),
            entries(second).map { it.filePath to it.changeType },
        )
    }

    @Test
    fun `a file that another file was copied from is an addition, as git shows it`(): Unit = runBlocking {
        write("old.txt", lines("old", 20))
        commit("Start")
        git.run(repo, "rm", "-q", "old.txt")
        write("a.txt", lines("old", 20))
        write("b.txt", lines("old", 20))
        val copied = commit("Move one, copy one")

        val entries = entries(copied)

        assertEquals(
            listOf("a.txt" to ChangeType.RENAME, "b.txt" to ChangeType.ADD),
            entries.map { it.filePath to it.changeType },
        )
        assertEquals(gitSays(copied), described(entries))
    }

    @Test
    fun `diff renames false shows a move as a deletion and an addition`(): Unit = runBlocking {
        git.run(repo, "config", "diff.renames", "false")
        write("old/Parser.kt", lines("Parser", 20))
        commit("Add the parser")
        git.run(repo, "mv", "old", "src")
        val moved = commit("Move the parser")

        assertEquals(
            listOf("old/Parser.kt" to ChangeType.DELETE, "src/Parser.kt" to ChangeType.ADD),
            entries(moved).map { it.filePath to it.changeType },
        )
    }

    @Test
    fun `diff renames set to true or copies still finds renames`(): Unit = runBlocking {
        write("old/Parser.kt", lines("Parser", 20))
        commit("Add the parser")
        git.run(repo, "mv", "old", "src")
        val moved = commit("Move the parser")

        for (value in listOf("true", "copies")) {
            git.run(repo, "config", "diff.renames", value)

            assertEquals(listOf(ChangeType.RENAME), entries(moved).map { it.changeType }, "diff.renames=$value")
        }
    }

    @Test
    fun `a refactor that moves many files and edits each is found by their names, over the limit`(): Unit = runBlocking {
        val count = DEFAULT_RENAME_LIMIT + 50
        repeat(count) { write("old/File$it.kt", "package old\n" + lines("File$it", 20)) }
        commit("Add the files")
        git.run(repo, "mv", "old", "ui")
        repeat(count) { write("ui/File$it.kt", "package ui\n" + lines("File$it", 20)) }
        val moved = commit("Move the files")

        val entries = entries(moved)

        assertEquals(count, entries.size)
        assertEquals(setOf(ChangeType.RENAME), entries.map { it.changeType }.toSet())
        assertEquals(gitSays(moved), described(entries))
    }

    @Test
    fun `files renamed and edited are paired by content up to the rename limit, which diff renameLimit sets`(): Unit =
        runBlocking {
            val count = DEFAULT_RENAME_LIMIT + 1
            repeat(count) { write("src/Old$it.kt", lines("File$it", 20)) }
            commit("Add the files")
            repeat(count) {
                git.run(repo, "mv", "src/Old$it.kt", "src/New$it.kt")
                write("src/New$it.kt", lines("File$it", 20).replace("File$it 0 ", "Edited 0 "))
            }
            val renamed = commit("Rename the files")

            // Over the default limit, which is what keeps this from taking minutes, nothing pairs. Not with diff.renames
            // set either, which JGit reads with a limit of 400 of its own
            assertEquals(setOf(ChangeType.ADD, ChangeType.DELETE), entries(renamed).map { it.changeType }.toSet())

            git.run(repo, "config", "diff.renames", "true")

            assertEquals(setOf(ChangeType.ADD, ChangeType.DELETE), entries(renamed).map { it.changeType }.toSet())

            git.run(repo, "config", "diff.renameLimit", "${count + 5}")

            val entries = entries(renamed)

            assertEquals(count, entries.size)
            assertEquals(setOf(ChangeType.RENAME), entries.map { it.changeType }.toSet())
        }

    @Test
    fun `exact renames are found over the limit, and so are thousands of them`(): Unit = runBlocking {
        val count = 1500
        repeat(count) { write("old/File$it.txt", lines("File$it", 5)) }
        commit("Add the files")
        git.run(repo, "mv", "old", "new")
        val moved = commit("Move the files")

        val entries = entries(moved)

        assertEquals(count, entries.size)
        assertEquals(setOf(ChangeType.RENAME), entries.map { it.changeType }.toSet())
    }

    @Test
    fun `without time only the exact renames are found`(): Unit = runBlocking {
        write("same/Exact.kt", lines("Exact", 20))
        write("same/Edited.kt", lines("Edited", 20))
        commit("Add the files")
        git.run(repo, "mv", "same", "moved")
        write("moved/Edited.kt", lines("Edited", 20).replace("Edited 2 ", "Changed 2 "))
        val moved = commit("Move the files")

        val entries = entries(moved, timeLimit = Duration.ZERO)

        assertEquals(
            listOf(
                "moved/Edited.kt" to ChangeType.ADD,
                "moved/Exact.kt" to ChangeType.RENAME,
                "same/Edited.kt" to ChangeType.DELETE,
            ),
            entries.map { it.filePath to it.changeType }.sortedBy { it.first },
        )
        assertEquals(
            listOf(ChangeType.RENAME, ChangeType.RENAME),
            entries(moved).map { it.changeType },
        )
    }

    @Test
    fun `a stage that runs out of time half way leaves its files as they were, added and deleted`(): Unit = runBlocking {
        val count = DEFAULT_RENAME_LIMIT
        repeat(count) { write("src/Old$it.kt", lines("File$it", 20)) }
        commit("Add the files")
        repeat(count) {
            git.run(repo, "mv", "src/Old$it.kt", "src/New$it.kt")
            write("src/New$it.kt", lines("File$it", 20).replace("File$it 0 ", "Edited 0 "))
        }
        val renamed = commit("Rename the files")

        // Comparing 100 files with 100 takes seconds, so a millisecond ends the stage in the middle of it
        val entries = entries(renamed, timeLimit = Duration.parse("PT0.001S"))

        assertEquals(count * 2, entries.size)
        assertEquals(count, entries.count { it.changeType == ChangeType.ADD && it.newPath.startsWith("src/New") })
        assertEquals(count, entries.count { it.changeType == ChangeType.DELETE && it.oldPath.startsWith("src/Old") })
    }

    @Test
    fun `a cancelled call throws rather than returning what it found`(): Unit = runBlocking {
        write("old/Parser.kt", lines("Parser", 20))
        commit("Add the parser")
        git.run(repo, "mv", "old", "src")
        val moved = commit("Move the parser")
        var outcome = "not run"

        val job = launch {
            currentCoroutineContext()[Job]!!.cancel()

            // provide turns what its block throws into an error, so the block looks
            outcome = (jgit.provide(gitDir) { git ->
                try {
                    git.repository.diffWithRenames(treeOf(git.repository, moved, parent = true), treeOf(git.repository, moved))
                    "returned"
                } catch (e: CancellationException) {
                    "cancelled"
                }
            } as Either.Ok).value
        }
        job.join()

        assertEquals("cancelled", outcome)
    }

    @Test
    fun `the first commit has only additions`(): Unit = runBlocking {
        write("a.txt", lines("a", 5))
        val first = commit("Start")

        assertEquals(listOf(ChangeType.ADD), entries(first).map { it.changeType })
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a partial clone pairs exact renames and downloads nothing for the list`(): Unit = runBlocking {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        git.run(repo, "config", "uploadpack.allowFilter", "true")
        write("a/Exact.kt", lines("Exact", 20))
        write("a/Edited.kt", lines("Edited", 20))
        commit("Add the files")
        git.run(repo, "mv", "a", "b")
        write("b/Edited.kt", lines("Edited", 20).replace("Edited 4 ", "Changed 4 "))
        val moved = commit("Move the files")
        write("b/Exact.kt", lines("Exact", 20) + "later\n")
        write("b/Edited.kt", lines("Edited", 20) + "later\n")
        commit("Change them again")

        git.run(tempDir, "clone", "--filter=blob:none", "file://${repo.absolutePath}", "clone")
        val clone = File(tempDir, "clone")
        val missingBefore = missingObjects(clone)

        val entries = entries(moved, path = File(clone, ".git").path)

        // The blobs of the move aren't in the clone: the exact rename needs none, the edited one is left alone
        assertTrue(missingBefore.isNotEmpty())
        assertEquals(
            listOf(
                "a/Edited.kt" to ChangeType.DELETE,
                "b/Edited.kt" to ChangeType.ADD,
                "b/Exact.kt" to ChangeType.RENAME,
            ),
            entries.map { it.filePath to it.changeType }.sortedBy { it.first },
        )
        assertEquals(missingBefore, missingObjects(clone))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a partial clone pairs the files whose blobs it has, and leaves the others, in the same stage`(): Unit = runBlocking {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        git.run(repo, "config", "uploadpack.allowFilter", "true")
        val present = lines("Present", 20)
        write("src/Present.kt", present)
        // The old content stays in the clone as another file, so its blob is there
        write("Keeper.kt", present)
        write("src/Gone.kt", lines("Gone", 20))
        commit("Add the files")
        git.run(repo, "mv", "src/Present.kt", "src/Renamed.kt")
        write("src/Renamed.kt", present.replace("Present 0 ", "Edited 0 "))
        git.run(repo, "mv", "src/Gone.kt", "src/Away.kt")
        write("src/Away.kt", lines("Gone", 20).replace("Gone 0 ", "Edited 0 "))
        val moved = commit("Rename both")
        // The clone checks out this: Renamed.kt as it was renamed, but not Away.kt, and nothing has Gone.kt's content
        write("src/Away.kt", lines("Gone", 20) + "later\n")
        commit("Change Away.kt")

        git.run(tempDir, "clone", "--filter=blob:none", "file://${repo.absolutePath}", "clone")
        val clone = File(tempDir, "clone")
        val missingBefore = missingObjects(clone)

        val entries = entries(moved, path = File(clone, ".git").path)

        // Both pairs are for the content stage, and one of them reads blobs that aren't in the clone. That mustn't
        // stop the other, which would happen if the stage tried them all and failed.
        assertEquals(
            listOf(
                "src/Away.kt" to ChangeType.ADD,
                "src/Gone.kt" to ChangeType.DELETE,
                "src/Renamed.kt" to ChangeType.RENAME,
            ),
            entries.map { it.filePath to it.changeType }.sortedBy { it.first },
        )
        assertEquals("src/Present.kt", entries.first { it.changeType == ChangeType.RENAME }.oldPath)
        assertEquals(missingBefore, missingObjects(clone))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a partial clone pairs the files of a commit that it made itself, as it has their blobs`(): Unit = runBlocking {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        git.run(repo, "config", "uploadpack.allowFilter", "true")
        write("a/Edited.kt", lines("Edited", 20))
        commit("Add the file")

        git.run(tempDir, "clone", "--filter=blob:none", "file://${repo.absolutePath}", "clone")
        val clone = File(tempDir, "clone")
        git.run(clone, "mv", "a", "b")
        File(clone, "b/Edited.kt").writeText(lines("Edited", 20).replace("Edited 4 ", "Changed 4 "))
        git.run(clone, "add", "-A")
        git.run(clone, "commit", "-m", "Move and edit it")
        val moved = git.run(clone, "rev-parse", "HEAD").trim()

        val entry = entries(moved, path = File(clone, ".git").path).single()

        assertEquals(ChangeType.RENAME, entry.changeType)
        assertEquals("a/Edited.kt" to "b/Edited.kt", entry.oldPath to entry.newPath)
    }

    private suspend fun entries(
        hash: String,
        path: String = gitDir,
        timeLimit: Duration? = null,
    ): List<DiffEntry> {
        if (timeLimit == null) {
            val result = GetCommitDiffEntriesGitAction(jgit, testPartialCloneFetch(jgit))(path, commit(hash, path))

            return (result as Either.Ok).value
        }

        val result = jgit.provide(path) { git ->
            val repository = git.repository

            repository.diffWithRenames(
                oldTree = treeOf(repository, hash, parent = true),
                newTree = treeOf(repository, hash),
                timeLimit = timeLimit,
            ).sortedBy { it.filePath }
        }

        return (result as Either.Ok).value
    }

    private fun treeOf(repository: Repository, hash: String, parent: Boolean = false): CanonicalTreeParser {
        val commit = repository.parseCommit(repository.resolve(hash))
        val tree = if (parent) commit.getParent(0).let { repository.parseCommit(it).tree } else commit.tree

        return CanonicalTreeParser().apply { repository.newObjectReader().use { reset(it, tree) } }
    }

    private fun commit(hash: String, path: String) = Commit(
        hash = hash,
        message = "",
        committer = Identity("Leaf Test", "test@example.invalid"),
        author = Identity("Leaf Test", "test@example.invalid"),
        date = 0,
        parentsHashes = git.run(File(path).parentFile, "rev-list", "--parents", "-n1", hash).trim().split(" ").drop(1),
    )

    /** What git lists for the commit with `-M`, as `status old new`, which Leaf's entries are compared with. */
    private fun gitSays(hash: String): List<String> = git.run(repo, "diff", "-M", "--name-status", "$hash~1", hash)
        .lines()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').let { listOf(it[0].take(1)) + it.drop(1) }.joinToString(" ") }
        .sorted()

    private fun described(entries: List<DiffEntry>): List<String> = entries.map {
        when (it.changeType) {
            ChangeType.RENAME -> "R ${it.oldPath} ${it.newPath}"
            ChangeType.ADD -> "A ${it.newPath}"
            ChangeType.DELETE -> "D ${it.oldPath}"
            else -> "M ${it.newPath}"
        }
    }.sorted()

    private fun missingObjects(clone: File): Set<String> = git.run(clone, "rev-list", "--objects", "--all", "--missing=print")
        .lines()
        .filter { it.startsWith("?") }
        .toSet()

    private fun write(path: String, content: String) {
        File(repo, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun commit(message: String): String {
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-m", message)

        return git.run(repo, "rev-parse", "HEAD").trim()
    }

    /** [count] lines that [name] makes unlike another file's, so only a file's own copy is similar to it. */
    private fun lines(name: String, count: Int): String =
        (0 until count).joinToString("") { "$name $it line of the file with some text in it\n" }
}
