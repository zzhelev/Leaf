// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.MergeToolRefusal
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.usecases.OpenMergeToolUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

private const val RESOLVED = "resolved line\n"

/**
 * "Open in merge tool": `git mergetool` with a fake tool, a shell script set up as `mergetool.<name>.cmd`, the way users
 * set up Meld and others. stdin is closed, so git's "Was the merge successful?" reads the end of input: if it weren't,
 * the tests that reach it would wait for an answer until their timeout.
 */
@DisabledOnOs(OS.WINDOWS)
class OpenMergeToolGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val jgit = testJGit()
    private val stateRepository = InMemoryRepositoryStateRepository()
    private val openMergeTool by lazy { OpenMergeToolGitAction(jgit, testGitCli(isolatedGit), stateRepository) }

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val tools by lazy { File(tempDir, "tools").apply { mkdirs() } }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a tool that saves the file resolves it, and git stages it`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        setTool("fake", resolvingTool(), trustExitCode = true)

        val result = openMergeTool("notes.txt")

        assertEquals(Either.Ok(Unit), result)
        assertEquals(RESOLVED, File(repository, "notes.txt").readText())
        assertEquals(listOf(0), git.stages(repository, "notes.txt"))
        assertEquals(RESOLVED, git.staged(repository, "notes.txt"))
        // The other conflicts are left alone
        assertEquals(listOf(1, 2), git.stages(repository, "kept.txt"))
    }

    @Test
    fun `the tool gets git's copies of the common ancestor, ours and theirs, and the file to write`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            // git removes its copies once the tool is done
            val copying = "cp \"\$1\" '$tools/base'; cp \"\$2\" '$tools/local'; cp \"\$3\" '$tools/remote'"
            setTool("fake", resolvingTool(afterSaving = copying), trustExitCode = true)

            openMergeTool("notes.txt")

            assertEquals("base line\n", File(tools, "base").readText())
            assertEquals(MAIN_NOTES, File(tools, "local").readText())
            assertEquals(FEATURE_NOTES, File(tools, "remote").readText())
            assertEquals("notes.txt", File(tools, "args").readLines()[3])
        }

    @Test
    fun `with an untrusted exit code, a file saved by the tool counts as resolved`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        // git compares the file's time with its backup's, to the second
        setTool("fake", resolvingTool(afterSaving = "touch -t 203001010000 \"\$4\""), trustExitCode = null)

        val result = openMergeTool("notes.txt")

        assertEquals(Either.Ok(Unit), result)
        assertEquals(RESOLVED, git.staged(repository, "notes.txt"))
    }

    @Test
    fun `with an untrusted exit code, a tool closed without saving leaves the conflict, as git can't ask`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            val conflicted = File(repository, "notes.txt").readText()
            setTool("fake", "#!/bin/sh\nexit 0\n", trustExitCode = null)

            val result = openMergeTool("notes.txt")

            val error = assertInstanceOf(ConflictError.NotResolved::class.java, (result as Either.Err).error)
            assertEquals("fake", error.tool)
            assertEquals("notes.txt", error.path)
            assertTrue("Was the merge successful" in error.output, error.output)
            assertTrue("merge of notes.txt failed" in error.output, error.output)
            assertEquals(conflicted, File(repository, "notes.txt").readText())
            assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
            assertEquals(emptyList<String>(), git.untracked(repository))
        }

    @Test
    fun `a trusted tool that fails leaves the conflict, with what it printed`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        val conflicted = File(repository, "notes.txt").readText()
        setTool("fake", "#!/bin/sh\necho 'fake: cannot open display' >&2\nexit 1\n", trustExitCode = true)

        val result = openMergeTool("notes.txt")

        val error = assertInstanceOf(ConflictError.NotResolved::class.java, (result as Either.Err).error)
        assertTrue("fake: cannot open display" in error.output, error.output)
        assertEquals(conflicted, File(repository, "notes.txt").readText())
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
    }

    @Test
    fun `a trusted tool closed without saving gets its conflict back, rather than staged with its markers`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            val conflicted = File(repository, "notes.txt").readText()
            setTool("fake", "#!/bin/sh\nexit 0\n", trustExitCode = true)

            val result = openMergeTool("notes.txt")

            assertEquals(Either.Err(ConflictError.MarkersLeft("fake", "notes.txt")), result)
            assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
            assertEquals(conflicted, File(repository, "notes.txt").readText())
        }

    @Test
    fun `what a trusted tool saved with markers left is kept, with the conflict back`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        val partial = "<<<<<<< HEAD\nmain line, half resolved\n=======\nfeature line\n>>>>>>> feature\n"
        val saving = listOf("#!/bin/sh", "printf '%s' '$partial' > \"\$4\"").joinToString("\n", postfix = "\n")
        setTool("fake", saving, trustExitCode = true)

        val result = openMergeTool("notes.txt")

        assertEquals(Either.Err(ConflictError.MarkersLeft("fake", "notes.txt")), result)
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
        assertEquals(partial, File(repository, "notes.txt").readText())
    }

    @Test
    fun `no merge tool set is reported without running git mergetool`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        // A command that git would run, if it ran a tool of that name
        git.run(repository, "config", "mergetool.fake.cmd", "touch '${File(tools, "ran").path}'")

        val result = openMergeTool("notes.txt")

        assertEquals(Either.Err(ConflictError.NoMergeTool), result)
        assertFalse(File(tools, "ran").exists())
        // git, left to guess a tool, would ask before starting it, and leave its copies of the file behind
        assertEquals(emptyList<String>(), git.untracked(repository))
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
    }

    @Test
    fun `merge guitool comes before merge tool, as for git mergetool --gui`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        setTool("failing", "#!/bin/sh\nexit 1\n", trustExitCode = true)
        setTool("fake", resolvingTool(), trustExitCode = true)
        git.run(repository, "config", "merge.tool", "failing")
        git.run(repository, "config", "merge.guitool", "fake")

        assertEquals(Either.Ok(Unit), openMergeTool("notes.txt"))
    }

    @Test
    fun `the user's mergetool prompt doesn't stop it, as nobody could answer`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        setTool("fake", resolvingTool(), trustExitCode = true)
        git.run(repository, "config", "mergetool.prompt", "true")

        assertEquals(Either.Ok(Unit), openMergeTool("notes.txt"))
    }

    @Test
    fun `a tool that git doesn't know leaves the conflict, and nothing in the working tree`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        git.run(repository, "config", "merge.tool", "nosuchtool")

        val error = (openMergeTool("notes.txt") as Either.Err).error

        val notResolved = assertInstanceOf(ConflictError.NotResolved::class.java, error)
        assertTrue("mergetool.nosuchtool.cmd not set" in notResolved.output, notResolved.output)
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
        assertEquals(emptyList<String>(), git.untracked(repository))
    }

    @Test
    fun `a tool that isn't installed leaves the conflict, and git's copies outside the working tree`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            // One of git's own tools, which it looks for on the PATH, where it isn't
            git.run(repository, "config", "merge.tool", "tortoisemerge")
            val withoutTools = OpenMergeToolGitAction(
                jgit,
                testGitCli(isolatedGit + ("PATH" to "/usr/bin:/bin")),
                stateRepository,
            )

            val error = (withTimeout(30.seconds) { withoutTools(gitDir(), "notes.txt") } as Either.Err).error

            val notResolved = assertInstanceOf(ConflictError.NotResolved::class.java, error)
            assertTrue("is not available" in notResolved.output, notResolved.output)
            assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
            // git exits without removing them, from where mergetool.writeToTemp puts them
            assertEquals(emptyList<String>(), git.untracked(repository))
        }

    @Test
    fun `git's copies go to a temporary folder, and no backup is kept, unless the user set them`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            setTool("fake", resolvingTool(), trustExitCode = true)

            openMergeTool("notes.txt")

            assertFalse(File(tools, "args").readLines().first().startsWith("./"))
            assertEquals(emptyList<String>(), git.untracked(repository))
        }

    @Test
    fun `the user's own mergetool keepBackup and writeToTemp apply`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        setTool("fake", resolvingTool(), trustExitCode = true)
        git.run(repository, "config", "mergetool.keepBackup", "true")
        git.run(repository, "config", "mergetool.writeToTemp", "false")

        openMergeTool("notes.txt")

        assertTrue(File(tools, "args").readLines().first().startsWith("./notes_BASE_"))
        assertEquals(listOf("notes.txt.orig"), git.untracked(repository))
    }

    @Test
    fun `a conflict where one side deleted the file is refused, as git would ask which side to keep`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            setTool("fake", resolvingTool(), trustExitCode = true)

            assertEquals(
                Either.Err(ConflictError.MergeToolRefused("kept.txt", MergeToolRefusal.NOT_TWO_FILES)),
                openMergeTool("kept.txt"),
            )
            assertEquals(
                Either.Err(ConflictError.MergeToolRefused("dropped.txt", MergeToolRefusal.NOT_TWO_FILES)),
                openMergeTool("dropped.txt"),
            )
            assertFalse(File(tools, "args").exists())
            assertEquals(listOf(1, 2), git.stages(repository, "kept.txt"))
            assertEquals(emptyList<String>(), git.untracked(repository))
        }

    @Test
    fun `a file that both sides added is merged without a common ancestor`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        setTool("fake", resolvingTool(), trustExitCode = true)

        assertEquals(Either.Ok(Unit), openMergeTool("added.txt"))
        assertEquals(RESOLVED, git.staged(repository, "added.txt"))
    }

    @Test
    fun `a name that the shell would expand is refused, as git-mergetool could merge another file`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "[ab].txt", "a.txt")
            setTool("fake", resolvingTool(), trustExitCode = true)

            val result = openMergeTool("[ab].txt")

            assertEquals(Either.Err(ConflictError.MergeToolRefused("[ab].txt", MergeToolRefusal.FILE_NAME)), result)
            assertEquals(listOf(1, 2, 3), git.stages(repository, "a.txt"))
        }

    @Test
    fun `a name with spaces is merged`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "my notes.txt")
        setTool("fake", resolvingTool(), trustExitCode = true)

        assertEquals(Either.Ok(Unit), openMergeTool("my notes.txt"))
        assertEquals(RESOLVED, git.staged(repository, "my notes.txt"))
    }

    @Test
    fun `a file without conflicts is reported, without running the tool`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        setTool("fake", resolvingTool(), trustExitCode = true)
        git.run(repository, "add", "notes.txt")

        assertEquals(Either.Err(ConflictError.NotConflicted(listOf("notes.txt"))), openMergeTool("notes.txt"))
        assertFalse(File(tools, "args").exists())
    }

    @Test
    fun `in a linked worktree, the tool resolves the worktree's file`(): Unit = runBlocking {
        git.run(repository, "branch", "work")
        val worktree = File(tempDir, "work-tree")
        git.run(repository, "worktree", "add", worktree.path, "work")
        git.mergeWithConflictsIn(worktree, "notes.txt")
        setTool("fake", resolvingTool(), trustExitCode = true)

        val result = withTimeout(30.seconds) {
            openMergeTool(File(repository, ".git/worktrees/work-tree").path, "notes.txt")
        }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(RESOLVED, git.staged(worktree, "notes.txt"))
        assertFalse(File(repository, "notes.txt").exists())
        assertEquals("", git.run(repository, "status", "--porcelain"))
    }

    @Test
    fun `while the tool is open, the processing screen names it and can cancel, which kills it`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            val pidFile = File(tools, "pid")
            setTool("slow", "#!/bin/sh\necho \$\$ > '${pidFile.path}'\nsleep 60\n", trustExitCode = true)

            val dataRepository = InMemoryRepositoryDataRepository().apply {
                setRepositorySelectionState(RepositorySelectionState.Open(gitDir()))
            }
            val executor = UseCaseExecutor(
                dataRepository,
                stateRepository,
                Provider { error("A cancelled task refreshes nothing") },
                TabCoroutineScope(),
            )

            OpenMergeToolUseCase(openMergeTool, executor)("notes.txt")

            withTimeout(30.seconds) {
                while (!pidFile.exists() || pidFile.readText().isBlank()) {
                    delay(50)
                }
            }

            assertEquals(TaskType.OpenMergeTool, stateRepository.currentTask.value)
            assertEquals(TaskProgress(stage = "slow: notes.txt", percent = null), stateRepository.taskProgress.value)

            stateRepository.cancelCurrentTask()
            withTimeout(10.seconds) { stateRepository.currentTask.first { it == null } }

            val tool = ProcessHandle.of(pidFile.readText().trim().toLong())
            withTimeout(10.seconds) {
                while (tool.map { it.isAlive }.orElse(false)) {
                    delay(50)
                }
            }

            assertNull(stateRepository.taskProgress.value)
            assertEquals(emptyList<Any>(), stateRepository.completedTasks.value)
            assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
        }

    private fun gitDir() = File(repository, ".git").path

    private suspend fun openMergeTool(path: String): Either<Unit, GitError> =
        withTimeout(30.seconds) { openMergeTool(gitDir(), path) }

    /** A tool that writes [RESOLVED] to the file to merge, and notes its arguments in `args`. */
    private fun resolvingTool(afterSaving: String = "") = listOf(
        "#!/bin/sh",
        "printf '%s\\n' \"\$@\" > '${File(tools, "args").path}'",
        "printf 'resolved line\\n' > \"\$4\"",
        afterSaving,
    ).joinToString("\n", postfix = "\n")

    /**
     * Sets [script] up as the merge tool [name], which git passes the common ancestor, ours, theirs and the file to
     * write, as users set up Meld (`mergetool.meld.cmd`). [trustExitCode] null leaves git's default, false.
     */
    private fun setTool(name: String, script: String, trustExitCode: Boolean?) {
        val tool = File(tools, name).writeExecutable(script)

        git.run(repository, "config", "merge.tool", name)
        git.run(repository, "config", "mergetool.$name.cmd", "'${tool.path}' \"\$BASE\" \"\$LOCAL\" \"\$REMOTE\" \"\$MERGED\"")

        if (trustExitCode != null) {
            git.run(repository, "config", "mergetool.$name.trustExitCode", trustExitCode.toString())
        }
    }
}
