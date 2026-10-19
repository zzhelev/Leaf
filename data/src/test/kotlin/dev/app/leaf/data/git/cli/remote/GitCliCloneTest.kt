// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.remote_operations.CloneRepositoryGitAction
import dev.app.leaf.data.git.remote_operations.HandleTransportGitAction
import dev.app.leaf.data.git.remote_operations.NoSshSessionFactory
import dev.app.leaf.data.git.submodules.InitializeAllSubmodulesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.CloneSubmodulesError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
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

    private lateinit var helper: File
    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var source: File
    private lateinit var clones: File

    @BeforeEach
    fun setUp() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "test.gitconfig")
        globalConfig.writeText("[protocol \"file\"]\n\tallow = always\n")
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper, globalConfig)

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
    fun `a chosen branch is checked out, HEAD follows it, and every branch is fetched`(): Unit = runBlocking {
        createBranchesAndTags()
        val destination = File(clones, "copy")

        val states = clone(destination, "file://${source.absolutePath}", branch = "feature")

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals("refs/heads/feature", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals("feature\n", File(destination, "feature.txt").readText())
        assertEquals("", git.run(destination, "status", "--porcelain"))
        assertEquals("origin", git.run(destination, "config", "branch.feature.remote").trim())
        assertEquals(
            listOf("refs/remotes/origin/HEAD", "refs/remotes/origin/feature", "refs/remotes/origin/main", "refs/remotes/origin/other"),
            remoteRefs(destination),
        )
        assertEquals("+refs/heads/*:refs/remotes/origin/*\n", git.run(destination, "config", "--get-all", "remote.origin.fetch"))
    }

    @Test
    fun `the branch name is trimmed, and a blank one is the default branch`(): Unit = runBlocking {
        createBranchesAndTags()

        val trimmed = File(clones, "trimmed")
        assertEquals(CloneState.Completed(trimmed), clone(trimmed, "file://${source.absolutePath}", branch = " feature ").last())
        assertEquals("refs/heads/feature", git.run(trimmed, "symbolic-ref", "HEAD").trim())

        val blank = File(clones, "blank")
        assertEquals(CloneState.Completed(blank), clone(blank, "file://${source.absolutePath}", branch = "  ").last())
        assertEquals("refs/heads/main", git.run(blank, "symbolic-ref", "HEAD").trim())
    }

    @Test
    fun `a tag is checked out with a detached HEAD, annotated or not`(): Unit = runBlocking {
        createBranchesAndTags()
        val tagged = git.run(source, "rev-parse", "main").trim()

        for (tag in listOf("v0", "v1")) {
            val destination = File(clones, tag)

            val states = clone(destination, "file://${source.absolutePath}", branch = tag)

            assertEquals(CloneState.Completed(destination), states.last()) { "$tag: $states" }
            assertEquals("HEAD", git.run(destination, "rev-parse", "--abbrev-ref", "HEAD").trim())
            assertEquals(tagged, git.run(destination, "rev-parse", "HEAD").trim())
            assertEquals("Test repository\n", File(destination, "README.md").readText())
            assertFalse(File(destination, "feature.txt").exists())
            assertEquals("", git.run(destination, "status", "--porcelain"))
        }
    }

    @Test
    fun `only this branch fetches that branch alone, and so do later fetches`(): Unit = runBlocking {
        createBranchesAndTags()
        val destination = File(clones, "copy")

        val states = clone(destination, "file://${source.absolutePath}", branch = "feature", singleBranch = true)

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals("refs/heads/feature", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals("feature\n", File(destination, "feature.txt").readText())
        assertEquals(listOf("refs/remotes/origin/feature"), remoteRefs(destination))
        assertEquals(
            "+refs/heads/feature:refs/remotes/origin/feature\n",
            git.run(destination, "config", "--get-all", "remote.origin.fetch"),
        )

        // Another branch comes up on the remote, and a fetch leaves it there
        git.run(source, "switch", "-c", "later", "main")
        git.run(destination, "fetch", "origin")
        assertEquals(listOf("refs/remotes/origin/feature"), remoteRefs(destination))
    }

    @Test
    fun `only a tag fetches that tag, and no branch`(): Unit = runBlocking {
        createBranchesAndTags()
        val destination = File(clones, "copy")

        val states = clone(destination, "file://${source.absolutePath}", branch = "v1", singleBranch = true)

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals("HEAD", git.run(destination, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals(emptyList<String>(), remoteRefs(destination))
        assertEquals("+refs/tags/v1:refs/tags/v1\n", git.run(destination, "config", "--get-all", "remote.origin.fetch"))
        assertEquals("Test repository\n", File(destination, "README.md").readText())
    }

    @Test
    fun `only this branch without a branch clones the default branch alone`(): Unit = runBlocking {
        createBranchesAndTags()
        val destination = File(clones, "copy")

        val states = clone(destination, "file://${source.absolutePath}", singleBranch = true)

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals("refs/heads/main", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals(listOf("refs/remotes/origin/HEAD", "refs/remotes/origin/main"), remoteRefs(destination))
    }

    @Test
    fun `a branch that the remote lacks says so, and leaves no folder`(): Unit = runBlocking {
        createBranchesAndTags()

        for (singleBranch in listOf(false, true)) {
            val destination = File(clones, "missing-$singleBranch")

            val error = failure(clone(destination, "file://${source.absolutePath}", branch = "nope", singleBranch = singleBranch))

            assertInstanceOf(RemoteOperationError.BranchNotFound::class.java, error)
            assertEquals("nope", (error as RemoteOperationError.BranchNotFound).branch)
            assertTrue(error.output.contains("Remote branch nope not found in upstream origin")) { error.output }
            assertFalse(destination.exists())
        }
    }

    @Test
    fun `a branch that the remote lacks leaves a chosen empty folder empty`(): Unit = runBlocking {
        val destination = File(clones, "chosen").apply { mkdirs() }

        val error = failure(clone(destination, "file://${source.absolutePath}", branch = "nope"))

        assertInstanceOf(RemoteOperationError.BranchNotFound::class.java, error)
        assertEquals(emptyList<String>(), destination.list()?.toList())
    }

    @Test
    fun `a branch name that starts with a dash is a name, not an option`(): Unit = runBlocking {
        val destination = File(clones, "dash")

        val error = failure(clone(destination, "file://${source.absolutePath}", branch = "--bare"))

        assertEquals("--bare", (error as RemoteOperationError.BranchNotFound).branch)
        assertFalse(destination.exists())
    }

    @Test
    fun `a repository that doesn't exist is still access denied when a branch is chosen`(): Unit = runBlocking {
        val destination = File(clones, "missing")

        val error = failure(clone(destination, "file://${File(tempDir, "nothing").absolutePath}", branch = "feature"))

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertFalse(destination.exists())
    }

    @Test
    fun `submodules are those of the chosen branch`(): Unit = runBlocking {
        val lib = git.initRepository(File(tempDir, "lib"))
        val superproject = git.initRepository(File(tempDir, "super"))
        git.run(superproject, "switch", "-c", "feature")
        git.run(superproject, "submodule", "add", "file://${lib.absolutePath}", "lib")
        git.run(superproject, "commit", "-m", "Add lib")
        git.run(superproject, "switch", "main")

        val onFeature = File(clones, "feature")
        val featureStates = clone(onFeature, "file://${superproject.absolutePath}", cloneSubmodules = true, branch = "feature")

        assertEquals(CloneState.Completed(onFeature), featureStates.last())
        assertTrue(CloneState.Cloning("Cloning submodules", 0, 0) in featureStates) { "$featureStates" }
        assertEquals("Test repository\n", File(onFeature, "lib/README.md").readText())

        // Only the superproject is limited to its branch
        val single = File(clones, "single")
        val singleStates = clone(single, "file://${superproject.absolutePath}", cloneSubmodules = true, branch = "feature", singleBranch = true)

        assertEquals(CloneState.Completed(single), singleStates.last())
        assertEquals("Test repository\n", File(single, "lib/README.md").readText())
        assertEquals(listOf("refs/remotes/origin/feature"), remoteRefs(single))

        val onMain = File(clones, "main")
        val mainStates = clone(onMain, "file://${superproject.absolutePath}", cloneSubmodules = true)

        assertEquals(CloneState.Completed(onMain), mainStates.last())
        assertFalse(File(onMain, ".gitmodules").exists())
        assertFalse(CloneState.Cloning("Cloning submodules", 0, 0) in mainStates)
    }

    @Test
    fun `git is given the branch and the single-branch switch`() {
        val destination = File("/clones/copy")

        assertEquals(
            listOf("clone", "--progress", "--no-checkout", "--", "u", "/clones/copy"),
            cloneArguments("u", destination, branch = null, singleBranch = false),
        )
        assertEquals(
            listOf("clone", "--progress", "--no-checkout", "--branch=feature/x", "--single-branch", "--", "u", "/clones/copy"),
            cloneArguments("u", destination, branch = " feature/x ", singleBranch = true),
        )
        assertEquals(
            listOf("clone", "--progress", "--no-checkout", "--single-branch", "--", "u", "/clones/copy"),
            cloneArguments("u", destination, branch = "", singleBranch = true),
        )
    }

    @Test
    fun `the clone that the app runs gives the branch to git, or to JGit when git isn't used`(): Unit = runBlocking {
        createBranchesAndTags()

        for (withGit in listOf(true, false)) {
            val destination = File(clones, "selected-$withGit")

            val states = selectingClone(withGit)(
                destination,
                "file://${source.absolutePath}",
                cloneSubmodules = false,
                branch = "feature",
                singleBranch = true,
            ).toList()

            assertEquals(CloneState.Completed(destination), states.last()) { "$withGit: $states" }
            assertEquals("refs/heads/feature", git.run(destination, "symbolic-ref", "HEAD").trim())
            assertEquals(listOf("refs/remotes/origin/feature"), remoteRefs(destination)) { "$withGit" }
            // Only git reports its progress as stages like this one
            assertEquals(withGit, CloneState.Cloning("Receiving objects", 100, 100) in states) { "$withGit: $states" }
        }
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

    private suspend fun clone(
        destination: File,
        url: String,
        cloneSubmodules: Boolean = false,
        branch: String? = null,
        singleBranch: Boolean = false,
    ) = GitCliCloneRepositoryGitAction(jgit, remote.command, remote.gitLfsFetch)(
        destination,
        url,
        cloneSubmodules,
        branch,
        singleBranch,
    ).toList()

    /** The clone that the app runs, which picks git or JGit as [RemoteOperationsBackend] decides. */
    private fun selectingClone(withGit: Boolean) = SelectingCloneRepositoryGitAction(
        backend = RemoteOperationsBackend(
            appSettingsService = AppSettingsService(
                mockk<AppSettingsRepository> {
                    every { remoteOperationsWithGit } returns flowOf(withGit)
                    every { gitExecutablePath } returns flowOf(null)
                }
            ),
            gitExecutableLocator = GitExecutableLocator(ProcessRunner()),
            askpassHelper = AskpassHelper { helper },
            gitCli = remote.gitCli,
            jgit = jgit,
        ),
        gitCliCloneRepositoryGitAction = GitCliCloneRepositoryGitAction(jgit, remote.command, remote.gitLfsFetch),
        jGitCloneRepositoryGitAction = CloneRepositoryGitAction(
            HandleTransportGitAction(
                noSshSessionFactory = NoSshSessionFactory { null },
                httpCredentialsProvider = object : HttpCredentialsFactory {
                    override fun create(git: Git?) = error("No HTTPS in this test")
                },
                jgit = jgit,
            ),
            InitializeAllSubmodulesGitAction(),
        ),
    )

    private fun failure(states: List<CloneState>) = (states.last() as CloneState.Fail).reason

    /** The remote-tracking refs of a clone, sorted. */
    private fun remoteRefs(repository: File) =
        git.run(repository, "for-each-ref", "--format=%(refname)", "refs/remotes").lines().filter { it.isNotEmpty() }.sorted()

    /**
     * `source` gets the branches `feature` and `other`, each with a file of its own, and the tags `v0` (lightweight)
     * and `v1` (annotated) at main's commit. It's left on main.
     */
    private fun createBranchesAndTags() {
        git.run(source, "tag", "v0")
        git.run(source, "tag", "-a", "v1", "-m", "Version 1")

        for (name in listOf("feature", "other")) {
            git.run(source, "switch", "-c", name, "main")
            File(source, "$name.txt").writeText("$name\n")
            git.run(source, "add", ".")
            git.run(source, "commit", "-m", "Add $name")
        }

        git.run(source, "switch", "main")
    }

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
