// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.diff.GetCommitDiffEntriesGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The files that the Staged pane's group lists while Amend is on (fork-only, upstream issue 260): what the commit being
 * amended changes against its first parent, which is what the pane reads with [GetCommitDiffEntriesUseCase]. They are
 * compared with git's own list for a normal commit, a root commit, a merge and a rename; for an amend, that the group's
 * files and the staged ones are together the files of the commit that Leaf's amend makes; and at a rebase's edit stop,
 * that the commit to amend is HEAD and its files are the stopped commit's.
 */
@DisabledOnOs(OS.WINDOWS)
class AmendingShowsLastCommitFilesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val tab by lazy {
        TestTab(
            jgit,
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }
    private val repository by lazy { File(tempDir, "repository") }
    private val gitDir by lazy { File(repository, ".git").path }

    private val commitFiles by lazy {
        val mapper = JGitCommitMapper(JGitIdentityMapper())

        GetCommitDiffEntriesUseCase(
            GetCommitDiffEntriesGitAction(jgit, testPartialCloneFetch(jgit)),
            dev.app.leaf.data.git.rebase.GetCommitFromHashGitAction(mapper, jgit),
            tab.useCaseExecutor,
        )
    }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        repository.mkdirs()
        git.run(repository, "init", "-b", "main")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    /** The files of [commit] against its first parent, as the pane reads them. */
    private suspend fun filesOf(commit: String): List<DiffEntry> {
        ensureOpen()

        val hash = git.run(repository, "rev-parse", commit).trim()
        val result = commitFiles(commitOf(hash))

        assertInstanceOf(Either.Ok::class.java, result, result.toString())

        return (result as Either.Ok).value
    }

    private suspend fun commitOf(hash: String): Commit {
        val mapper = JGitCommitMapper(JGitIdentityMapper())
        val result = dev.app.leaf.data.git.rebase.GetCommitFromHashGitAction(mapper, jgit)(gitDir, hash)

        return (result as Either.Ok).value!!
    }

    private fun List<DiffEntry>.byPath() = associate { it.filePath to it.changeType.name }

    /**
     * git's own list: status letter and path for each file of the commit against its first parent, or against nothing for
     * a root commit.
     */
    private fun gitsList(commit: String): Map<String, String> {
        val hasParent = git.run(repository, "rev-list", "--parents", "-n1", commit).trim().split(' ').size > 1
        val output = if (hasParent) {
            git.run(repository, "diff", "--name-status", "-M", "$commit^1", commit)
        } else {
            git.run(repository, "diff-tree", "-r", "--root", "--no-commit-id", "--name-status", "-M", commit)
        }

        return output.lines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split('\t')
                val type = when (parts[0].first()) {
                    'A' -> "ADD"
                    'M' -> "MODIFY"
                    'D' -> "DELETE"
                    'R' -> "RENAME"
                    else -> parts[0]
                }

                parts.last() to type
            }
    }

    private fun write(vararg files: Pair<String, String>) {
        for ((file, content) in files) {
            File(repository, file).apply { parentFile.mkdirs() }.writeText(content)
        }
    }

    @Test
    fun `the last commit's files are what it changes against its parent`(): Unit = runBlocking {
        write("a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n", "keep.txt" to "k\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        write("a.txt" to "a2\n", "added/new.txt" to "n\n")
        File(repository, "c.txt").delete()
        git.run(repository, "add", "-A")
        git.run(repository, "commit", "-m", "The change")

        val files = filesOf("HEAD")

        assertEquals(mapOf("a.txt" to "MODIFY", "added/new.txt" to "ADD", "c.txt" to "DELETE"), files.byPath())
        assertEquals(gitsList("HEAD"), files.byPath())
    }

    @Test
    fun `a root commit lists every file as added`(): Unit = runBlocking {
        write("a.txt" to "a\n", "src/b.txt" to "b\n", "src/deep/c.txt" to "c\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Initial commit")

        val files = filesOf("HEAD")

        assertEquals(setOf("a.txt", "src/b.txt", "src/deep/c.txt"), files.map { it.filePath }.toSet())
        assertEquals(setOf("ADD"), files.map { it.changeType.name }.toSet())
        assertEquals(gitsList("HEAD"), files.byPath())
    }

    @Test
    fun `an empty commit has no files`(): Unit = runBlocking {
        write("a.txt" to "a\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "commit", "--allow-empty", "-m", "Nothing")

        assertEquals(emptyList<DiffEntry>(), filesOf("HEAD"))
    }

    @Test
    fun `a merge commit lists what it brought in over its first parent`(): Unit = runBlocking {
        write("a.txt" to "a\n", "shared.txt" to "s\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "switch", "-c", "feature")
        write("feature.txt" to "f\n", "shared.txt" to "feature\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Feature")
        git.run(repository, "switch", "main")
        write("main.txt" to "m\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Main")
        git.run(repository, "merge", "--no-ff", "-m", "Merge feature", "feature")

        val files = filesOf("HEAD")

        // Against the first parent (main), so main's own file isn't in it
        assertEquals(mapOf("feature.txt" to "ADD", "shared.txt" to "MODIFY"), files.byPath())
        assertEquals(gitsList("HEAD"), files.byPath())
    }

    @Test
    fun `a moved file is one row, a rename`(): Unit = runBlocking {
        write("src/Lexer.kt" to "class Lexer {\n    fun lex() = 1\n    fun more() = 2\n}\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "mv", "src/Lexer.kt", "src/Parser.kt")
        git.run(repository, "commit", "-m", "Rename it")

        val files = filesOf("HEAD")

        assertEquals(mapOf("src/Parser.kt" to "RENAME"), files.byPath())
        assertEquals("src/Lexer.kt", files.single().oldPath)
    }

    @Test
    fun `the group and the staged files are together the files of the commit that the amend makes`(): Unit =
        runBlocking {
            write("a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "Base")
            write("a.txt" to "a2\n", "new.txt" to "n\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "The commit to amend")
            val parent = git.run(repository, "rev-parse", "HEAD~1").trim()

            // What the group shows before the amend, and what is staged: b.txt is new to the commit, a.txt is in both
            val group = filesOf("HEAD").map { it.filePath }.toSet()
            write("a.txt" to "a3\n", "b.txt" to "b2\n", "extra.txt" to "x\n")
            git.run(repository, "add", ".")
            val staged = git.run(repository, "diff", "--cached", "--name-only").lines().filter { it.isNotBlank() }.toSet()

            val amended = tab.doCommit("The commit to amend", amend = true, author = null, isMergeMessage = false).await()
            assertInstanceOf(Either.Ok::class.java, amended, amended.toString())

            assertEquals(parent, git.run(repository, "rev-parse", "HEAD~1").trim(), "the amend keeps the parent")
            assertEquals(1, git.run(repository, "rev-list", "--count", "$parent..HEAD").trim().toInt())
            assertEquals(setOf("a.txt", "new.txt"), group)
            assertEquals(setOf("a.txt", "b.txt", "extra.txt"), staged)
            assertEquals(
                group + staged,
                filesOf("HEAD").map { it.filePath }.toSet(),
                "the amended commit changes the group's files and the staged ones",
            )
        }

    @Test
    fun `an amend with nothing staged keeps exactly the files that the group lists`(): Unit = runBlocking {
        write("a.txt" to "a\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        write("a.txt" to "a2\n", "b.txt" to "b\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Wrong message")

        val group = filesOf("HEAD").map { it.filePath }.toSet()

        val amended = tab.doCommit("Right message", amend = true, author = null, isMergeMessage = false).await()
        assertInstanceOf(Either.Ok::class.java, amended, amended.toString())

        assertEquals("Right message", git.run(repository, "log", "-1", "--format=%s").trim())
        assertEquals(group, filesOf("HEAD").map { it.filePath }.toSet())
    }

    @Test
    fun `a root commit can be amended, and its files are what the group listed`(): Unit = runBlocking {
        write("a.txt" to "a\n", "b.txt" to "b\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Initial comit")

        val group = filesOf("HEAD").map { it.filePath }.toSet()

        write("c.txt" to "c\n")
        git.run(repository, "add", ".")
        val amended = tab.doCommit("Initial commit", amend = true, author = null, isMergeMessage = false).await()
        assertInstanceOf(Either.Ok::class.java, amended, amended.toString())

        assertEquals(0, git.run(repository, "rev-list", "--parents", "-n1", "HEAD").trim().split(' ').size - 1)
        assertEquals(group + "c.txt", filesOf("HEAD").map { it.filePath }.toSet())
    }

    @Test
    fun `at a rebase's edit stop the commit to amend is HEAD, and its files are the stopped commit's`(): Unit =
        runBlocking {
            write("a.txt" to "a\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "Base")
            git.run(repository, "switch", "-c", "topic")
            write("one.txt" to "1\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "topic: one")
            write("two.txt" to "2\n", "a.txt" to "a2\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "topic: two")
            write("three.txt" to "3\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "topic: three")

            git.run(
                repository,
                mapOf("GIT_SEQUENCE_EDITOR" to "sed -i.bak 's/^pick \\(.* topic: two\\)/edit \\1/'"),
                "rebase", "-i", "main",
            )

            ensureOpen()

            val state = tab.awaitLoaded(tab.repositoryData.rebaseInteractiveState) {
                it is RebaseInteractiveState.ProcessingCommits
            } as RebaseInteractiveState.ProcessingCommits

            assertEquals(git.run(repository, "rev-parse", "HEAD").trim(), state.commitToAmendId)
            assertEquals(true, state.isCurrentStepAmenable)
            assertEquals(
                mapOf("two.txt" to "ADD", "a.txt" to "MODIFY"),
                filesOf(state.commitToAmendId!!).byPath(),
            )
        }

    @Test
    fun `at a rebase's stop that isn't an edit there is no commit to amend`(): Unit = runBlocking {
        write("conflict.txt" to "base\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "switch", "-c", "topic")
        write("conflict.txt" to "topic\n")
        git.run(repository, "commit", "-am", "topic: one")
        git.run(repository, "switch", "main")
        write("conflict.txt" to "main\n")
        git.run(repository, "commit", "-am", "main: one")
        git.run(repository, "switch", "topic")
        git.runFailing(repository, "rebase", "-i", "main")

        ensureOpen()

        val state = tab.awaitLoaded(tab.repositoryData.rebaseInteractiveState) {
            it is RebaseInteractiveState.ProcessingCommits
        } as RebaseInteractiveState.ProcessingCommits

        assertEquals(false, state.isCurrentStepAmenable)
    }

    private var isOpen = false

    /** The tab opens the repository once; the commands that follow see what git did to it since. */
    private suspend fun ensureOpen() {
        if (isOpen) return

        isOpen = true
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)
    }
}
