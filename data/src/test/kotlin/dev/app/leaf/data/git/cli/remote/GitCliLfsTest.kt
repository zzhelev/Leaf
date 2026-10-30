// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.CloneCheckoutError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.security.MessageDigest
import kotlin.random.Random

private const val USER = "leaf"
private const val PASSWORD = "s3cret"

/** What git passes `post-checkout` as the commit before a clone's checkout. */
private const val NO_COMMIT = "0000000000000000000000000000000000000000"

/** As `git lfs install` sets them up. */
internal const val GIT_LFS_FILTERS = "[filter \"lfs\"]\n" +
    "\tprocess = git-lfs filter-process\n" +
    "\tsmudge = git-lfs smudge -- %f\n" +
    "\tclean = git-lfs clean -- %f\n" +
    "\trequired = true\n"

/**
 * Clones, pulls and pushes LFS files with the git CLI and git-lfs, from a bare repository on disk whose LFS objects
 * are on [FakeLfsServer]. Skipped without git-lfs or the askpass helper. A `git-lfs` that notes how it was run in
 * [gitLfsLog], then runs the real one, is on the PATH that Leaf's git gets.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliLfsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var helper: File
    private lateinit var git: TestGitCli
    private lateinit var globalConfig: File
    private lateinit var server: FakeLfsServer
    private lateinit var gitLfsLog: File
    private lateinit var pathWithGitLfs: String
    private lateinit var source: File
    private lateinit var origin: File

    @BeforeEach
    fun setUp() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!

        val realGitLfs = ProcessBuilder("/bin/sh", "-c", "command -v git-lfs").start()
            .let { process -> process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() } }
        assumeTrue(realGitLfs.isNotEmpty()) { "git-lfs isn't installed" }

        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        globalConfig = File(tempDir, "test.gitconfig").apply { writeText(GIT_LFS_FILTERS) }
        git = TestGitCli(globalConfig)
        server = FakeLfsServer(File(tempDir, "lfs-store"))

        gitLfsLog = File(tempDir, "git-lfs.log")
        val bin = File(tempDir, "bin")
        File(bin, "git-lfs").writeExecutable("#!/bin/sh\necho \"$*\" >> '${gitLfsLog.path}'\nexec '$realGitLfs' \"$@\"\n")
        pathWithGitLfs = "${bin.absolutePath}:/usr/bin:/bin"

        publishLfsRepository()
        server.requests.clear()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.close()
        }
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `git-lfs checks a clone's LFS files out, asking through Leaf's dialog`(): Unit = runBlocking {
        server.credentials = USER to PASSWORD
        val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to pathWithGitLfs))
        val destination = File(tempDir, "clone")

        val (states, dialogs) = remote.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted(USER, PASSWORD) }) {
            clone(remote, destination).toList()
        }

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = null, askPassword = true)), dialogs)
        assertArrayEquals(File(source, "first.bin").readBytes(), File(destination, "first.bin").readBytes())

        // git's count of the files that the filter delays, as git-lfs's filter shows no progress of its own
        assertTrue(CloneState.Cloning("Filtering content", 100, 100) in states) { "$states" }
        // Whether git-lfs works, for Leaf's filter, then git's filter (Leaf adds none), then the hook it installed
        val head = git.run(destination, "rev-parse", "HEAD").trim()
        assertEquals(listOf("version", "filter-process", "post-checkout $NO_COMMIT $head 1"), gitLfsLog.readLines())
        assertTrue(server.requests.any { it.startsWith("POST /objects/batch git-lfs/") }) { "${server.requests}" }
        assertEquals("", git.run(destination, "status", "--porcelain"))
        // git-lfs installs its hooks as it checks the files out, so that pushes upload the LFS objects
        assertTrue(File(destination, ".git/hooks/pre-push").readText().contains("git lfs pre-push"))
    }

    @Test
    fun `git-lfs checks out the LFS files of the chosen branch`(): Unit = runBlocking {
        git.run(source, "switch", "-c", "feature")
        File(source, "branch.bin").writeBytes(Random(7).nextBytes(20_000))
        git.run(source, "add", ".")
        git.run(source, "commit", "-m", "Add branch.bin")
        git.run(source, "push", "origin", "feature")
        val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to pathWithGitLfs))
        val destination = File(tempDir, "clone")

        val states = clone(remote, destination, branch = "feature", singleBranch = true).toList()

        assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
        assertEquals("refs/heads/feature", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertArrayEquals(File(source, "branch.bin").readBytes(), File(destination, "branch.bin").readBytes())
        val head = git.run(destination, "rev-parse", "HEAD").trim()
        assertEquals(listOf("version", "filter-process", "post-checkout $NO_COMMIT $head 1"), gitLfsLog.readLines())
        assertEquals("", git.run(destination, "status", "--porcelain"))
    }

    @Test
    fun `without git-lfs, Leaf's filter checks a clone's LFS files out, over git-lfs's in the config`(): Unit =
        runBlocking {
            val path = pathWithoutGitLfs(File(tempDir, "no-lfs-bin"))
            val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to path))
            val destination = File(tempDir, "clone")

            val states = clone(remote, destination).toList()

            assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
            assertArrayEquals(File(source, "first.bin").readBytes(), File(destination, "first.bin").readBytes())
            assertEquals(1, server.requests.count { it.startsWith("POST /objects/batch") }) { "${server.requests}" }
            val id = lfsOid("first.bin")
            assertTrue(File(destination, ".git/lfs/objects/${id.take(2)}/${id.substring(2, 4)}/$id").isFile)
            assertEquals("", git.run(destination, "status", "--porcelain"))
        }

    @Test
    fun `a repository without LFS is cloned without running git-lfs`(): Unit = runBlocking {
        val plain = git.initRepository(File(tempDir, "plain"))
        // Attributes of its own, but none for LFS
        File(plain, ".gitattributes").writeText("*.txt text eol=lf\n")
        git.run(plain, "add", ".")
        git.run(plain, "commit", "-m", "Attributes")
        val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to pathWithGitLfs))
        val destination = File(tempDir, "clone")

        val states = clone(remote, destination, url = "file://${plain.absolutePath}").toList()

        assertEquals(CloneState.Completed(destination), states.last())
        // Whether git-lfs runs, which decides Leaf's filter, and nothing that reads the tree
        assertEquals(listOf("version"), gitLfsLog.readLines())
    }

    @Test
    fun `a clone whose LFS files git-lfs can't download fails, and leaves no folder`(): Unit = runBlocking {
        File(tempDir, "lfs-store/${lfsOid("first.bin")}").delete()
        val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to pathWithGitLfs))
        val destination = File(tempDir, "clone")

        val states = clone(remote, destination).toList()

        val error = (states.last() as CloneState.Fail).reason
        assertInstanceOf(CloneCheckoutError::class.java, error) { "$error" }
        val output = ((error as CloneCheckoutError).error as RemoteOperationError).output
        assertTrue("smudge filter lfs failed" in output) { output }
        // What git and git-lfs say about the clone, which Leaf removed
        assertFalse("git restore" in output || "Errors logged to" in output) { output }
        assertFalse(destination.exists())
    }

    @Test
    fun `git-lfs's own prompts, without a credential helper, take one dialog`(): Unit = runBlocking {
        server.credentials = USER to PASSWORD
        val remote = TestRemoteCommand(
            helper,
            globalConfig,
            cacheCredentials = false,
            shellVariables = mapOf("PATH" to pathWithGitLfs),
        )
        val destination = File(tempDir, "clone")

        val (states, dialogs) = remote.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted(USER, PASSWORD) }) {
            clone(remote, destination).toList()
        }

        assertEquals(CloneState.Completed(destination), states.last())
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = null, askPassword = true)), dialogs)
        assertArrayEquals(File(source, "first.bin").readBytes(), File(destination, "first.bin").readBytes())
    }

    @Test
    fun `git pull checks out a pull's new LFS files with git-lfs's filter`(): Unit = runBlocking {
        val work = cloneWithGit()
        val pushed = commitLfsFile("second.bin")
        val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to pathWithGitLfs))

        val result = testGitPullGitAction(jgit, remote.gitCli)(
            File(work, ".git").path,
            PullType.MERGE,
            mergeAutoStash = true,
            remoteBranch = null,
            "automatic stash",
        )

        assertEquals(Either.Ok(false), result)
        assertEquals(pushed, git.run(work, "rev-parse", "HEAD").trim())
        assertArrayEquals(File(source, "second.bin").readBytes(), File(work, "second.bin").readBytes())
        assertTrue("filter-process" in gitLfsLog.readLines()) { gitLfsLog.readText() }
    }

    @Test
    fun `a push shows git-lfs's upload progress`(): Unit = runBlocking {
        val work = cloneWithGit()
        File(work, "third.bin").writeBytes(Random(3).nextBytes(30_000))
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Third")
        val remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to pathWithGitLfs))

        val result = GitCliPushBranchGitAction(jgit, GetTrackingBranchGitAction(jgit), remote.command)(
            File(work, ".git").path,
            force = false,
            pushTags = false,
            pushWithLease = true,
            specificBranch = null,
        )

        assertEquals(Either.Ok(Unit), result)
        assertTrue(TaskProgress("Uploading LFS objects", 100) in remote.progress) { "${remote.progress}" }
        assertTrue(File(tempDir, "lfs-store/${lfsOid("third.bin", work)}").isFile)
    }

    private fun clone(
        remote: TestRemoteCommand,
        destination: File,
        url: String = "file://${origin.absolutePath}",
        branch: String? = null,
        singleBranch: Boolean = false,
    ) = GitCliCloneRepositoryGitAction(jgit, remote.command)(destination, url, false, branch, singleBranch)

    /** `source`, with an LFS file, pushed to `origin.git` and its LFS object to the server. */
    private fun publishLfsRepository() {
        origin = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", origin.path)

        source = File(tempDir, "source").apply { mkdirs() }
        git.run(source, "init")
        git.run(source, "lfs", "track", "*.bin")
        File(source, ".lfsconfig").writeText("[lfs]\n\turl = ${server.url}\n")
        File(source, "README.md").writeText("LFS test\n")
        File(source, "first.bin").writeBytes(Random(1).nextBytes(20_000))
        git.run(source, "add", ".")
        git.run(source, "commit", "-m", "LFS files")
        git.run(source, "remote", "add", "origin", "file://${origin.absolutePath}")
        git.run(source, "push", "origin", "main")
    }

    /** Pushes a new LFS file from `source`, and returns the new commit. */
    private fun commitLfsFile(name: String): String {
        File(source, name).writeBytes(Random(name.hashCode()).nextBytes(20_000))
        git.run(source, "add", ".")
        git.run(source, "commit", "-m", "Add $name")
        git.run(source, "push", "origin", "main")

        return git.run(source, "rev-parse", "HEAD").trim()
    }

    /** A clone made by git and git-lfs, before the server wants credentials. */
    private fun cloneWithGit(): File {
        val work = File(tempDir, "work")
        git.run(tempDir, "clone", "file://${origin.absolutePath}", work.path)

        return work
    }

    private fun lfsOid(name: String, repository: File = source) = sha256(File(repository, name))
}

/** An LFS object's ID: the SHA-256 of the file. */
internal fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
    "%02x".format(it)
}
