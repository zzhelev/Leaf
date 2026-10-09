// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.CloneSubmodulesError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Clones, and adds and updates submodules, with the git CLI, from repositories on disk (`file://`). git's
 * `protocol.file.allow` refuses local submodules by default, so the tests' global config allows them. Skipped when the
 * askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliCloneTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var source: File
    private lateinit var clones: File

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "test.gitconfig")
        globalConfig.writeText("[protocol \"file\"]\n\tallow = always\n")
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig)

        source = git.initRepository(File(tempDir, "source"))
        clones = File(tempDir, "clones").apply { mkdirs() }
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a clone checks the default branch out with JGit, and shows git's progress`(): Unit = runBlocking {
        val destination = File(clones, "copy")

        val states = clone(destination, "file://${source.absolutePath}")

        assertEquals(CloneState.Cloning("Starting...", 0, 0), states.first())
        assertEquals(CloneState.Completed(destination), states.last())
        assertTrue(CloneState.Cloning("Receiving objects", 100, 100) in states) { "$states" }
        assertTrue(CloneState.Cloning("Checking out files", 0, 0) in states) { "$states" }

        assertEquals("Test repository\n", File(destination, "README.md").readText())
        // The index matches HEAD, so nothing shows as changed
        assertEquals("", git.run(destination, "status", "--porcelain"))
        assertEquals("refs/heads/main", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals("origin", git.run(destination, "config", "branch.main.remote").trim())
        // No progress goes to the processing screen of the tab that clones
        assertEquals(emptyList<Any?>(), remote.progress)
    }

    @Test
    fun `an empty repository is cloned without a checkout`(): Unit = runBlocking {
        val empty = File(tempDir, "empty.git")
        git.run(tempDir, "init", "--bare", empty.path)
        val destination = File(clones, "empty")

        val states = clone(destination, "file://${empty.absolutePath}")

        assertEquals(CloneState.Completed(destination), states.last())
        assertTrue(File(destination, ".git/HEAD").isFile)
        assertEquals(listOf(".git"), destination.list()?.toList())
    }

    @Test
    fun `a repository that doesn't exist fails, and leaves no folder`(): Unit = runBlocking {
        val destination = File(clones, "missing")

        val error = failure(clone(destination, "file://${File(tempDir, "nothing").absolutePath}"))

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertFalse(destination.exists())
    }

    @Test
    fun `a failed clone into an empty folder leaves the folder, empty`(): Unit = runBlocking {
        val destination = File(clones, "chosen").apply { mkdirs() }

        val error = failure(clone(destination, "file://${File(tempDir, "nothing").absolutePath}"))

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertEquals(emptyList<String>(), destination.list()?.toList())
    }

    @Test
    fun `a folder with files is refused and left as it is`(): Unit = runBlocking {
        val destination = File(clones, "taken").apply { mkdirs() }
        File(destination, "notes.txt").writeText("mine")

        val error = failure(clone(destination, "file://${source.absolutePath}"))

        assertInstanceOf(RemoteOperationError.Failed::class.java, error)
        assertTrue((error as RemoteOperationError).output.contains("already exists and is not an empty directory")) {
            error.output
        }
        assertEquals(listOf("notes.txt"), destination.list()?.toList())
        assertEquals("mine", File(destination, "notes.txt").readText())
    }

    @Test
    fun `submodules are cloned, with the submodules inside them`(): Unit = runBlocking {
        val superproject = createSuperproject()
        val destination = File(clones, "super")

        val states = clone(destination, "file://${superproject.absolutePath}", cloneSubmodules = true)

        assertEquals(CloneState.Completed(destination), states.last())
        assertTrue(CloneState.Cloning("Cloning submodules", 0, 0) in states) { "$states" }
        assertEquals("Test repository\n", File(destination, "lib/README.md").readText())
        assertEquals("Test repository\n", File(destination, "lib/nested/README.md").readText())
        assertEquals("", git.run(destination, "status", "--porcelain"))
    }

    @Test
    fun `without cloneSubmodules, submodules aren't initialized`(): Unit = runBlocking {
        val superproject = createSuperproject()
        val destination = File(clones, "super")

        val states = clone(destination, "file://${superproject.absolutePath}", cloneSubmodules = false)

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals(emptyList<String>(), File(destination, "lib").list()?.toList())
        assertFalse(File(destination, ".git/config").readText().contains("[submodule"))
    }

    @Test
    fun `submodules that can't be cloned fail the clone, but the repository is kept`(): Unit = runBlocking {
        val superproject = createSuperproject()
        File(tempDir, "lib").deleteRecursively()
        val destination = File(clones, "super")

        val error = failure(clone(destination, "file://${superproject.absolutePath}", cloneSubmodules = true))

        assertInstanceOf(CloneSubmodulesError::class.java, error)
        assertEquals(destination.path, (error as CloneSubmodulesError).directory)
        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error.error)
        assertEquals("Test repository\n", File(destination, "README.md").readText())
    }

    @Test
    fun `updating a submodule clones it, with the submodules inside it`(): Unit = runBlocking {
        val superproject = createSuperproject()
        val work = File(tempDir, "work")
        git.run(tempDir, "clone", "file://${superproject.absolutePath}", work.path)

        val result = GitCliUpdateSubmoduleGitAction(jgit, remote.command)(File(work, ".git").path, "lib")

        assertEquals(Either.Ok(Unit), result)
        assertEquals("Test repository\n", File(work, "lib/README.md").readText())
        assertEquals("Test repository\n", File(work, "lib/nested/README.md").readText())
        // The processing screen showed git's progress, and nothing once git was done
        assertEquals(null, remote.progress.last())
    }

    @Test
    fun `a submodule that can't be updated reports git's message`(): Unit = runBlocking {
        val superproject = createSuperproject()
        val work = File(tempDir, "work")
        git.run(tempDir, "clone", "file://${superproject.absolutePath}", work.path)
        File(tempDir, "lib").deleteRecursively()

        val result = GitCliUpdateSubmoduleGitAction(jgit, remote.command)(File(work, ".git").path, "lib")

        val error = (result as Either.Err).error
        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertTrue((error as RemoteOperationError).output.contains("does not appear to be a git repository")) {
            error.output
        }
    }

    @Test
    fun `adding a submodule clones it and stages it under its name`(): Unit = runBlocking {
        val lib = git.initRepository(File(tempDir, "lib"))
        val work = git.initRepository(File(tempDir, "work"))

        val result = GitCliAddSubmoduleGitAction(jgit, remote.command)(
            repositoryPath = File(work, ".git").path,
            name = "library",
            path = "vendor/lib",
            uri = "file://${lib.absolutePath}",
        )

        assertEquals(Either.Ok(Unit), result)
        assertEquals("Test repository\n", File(work, "vendor/lib/README.md").readText())
        assertEquals(
            "vendor/lib\n",
            git.run(work, "config", "--file", ".gitmodules", "submodule.library.path"),
        )
        assertEquals(".gitmodules\nvendor/lib\n", git.run(work, "diff", "--cached", "--name-only"))
    }

    @Test
    fun `a submodule that can't be added reports git's message, and stages nothing`(): Unit = runBlocking {
        val work = git.initRepository(File(tempDir, "work"))

        val result = GitCliAddSubmoduleGitAction(jgit, remote.command)(
            repositoryPath = File(work, ".git").path,
            name = "lib",
            path = "lib",
            uri = "file://${File(tempDir, "nothing").absolutePath}",
        )

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, (result as Either.Err).error)
        assertEquals("", git.run(work, "diff", "--cached", "--name-only"))
        assertFalse(File(work, ".gitmodules").exists())
    }

    private suspend fun clone(destination: File, url: String, cloneSubmodules: Boolean = false) =
        GitCliCloneRepositoryGitAction(jgit, remote.command)(destination, url, cloneSubmodules).toList()

    private fun failure(states: List<CloneState>) = (states.last() as CloneState.Fail).reason

    /** `super`, with the submodule `lib`, which has the submodule `nested`. */
    private fun createSuperproject(): File {
        val nested = git.initRepository(File(tempDir, "nested"))
        val lib = git.initRepository(File(tempDir, "lib"))
        git.run(lib, "submodule", "add", "file://${nested.absolutePath}", "nested")
        git.run(lib, "commit", "-m", "Add nested")

        val superproject = git.initRepository(File(tempDir, "super"))
        git.run(superproject, "submodule", "add", "file://${lib.absolutePath}", "lib")
        git.run(superproject, "commit", "-m", "Add lib")

        return superproject
    }
}
