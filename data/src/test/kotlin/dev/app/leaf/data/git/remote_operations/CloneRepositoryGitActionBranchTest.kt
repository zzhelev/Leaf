// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.submodules.InitializeAllSubmodulesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
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

/**
 * Cloning a branch or a tag with JGit, which clones when git can't run or "Use git for remote operations" is off
 * (see `GitCliCloneTest` for git's). The same clones as there, from a repository on disk, and the same result.
 */
class CloneRepositoryGitActionBranchTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var source: File
    private lateinit var clones: File

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        git = TestGitCli(File(tempDir, "empty.gitconfig"))
        source = git.initRepository(File(tempDir, "source"))
        clones = File(tempDir, "clones").apply { mkdirs() }

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

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a chosen branch is checked out, and every branch is fetched`(): Unit = runBlocking {
        val destination = File(clones, "copy")

        val states = clone(destination, branch = "feature")

        assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
        assertEquals("refs/heads/feature", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals("feature\n", File(destination, "feature.txt").readText())
        assertEquals("", git.run(destination, "status", "--porcelain"))
        assertEquals(
            listOf("refs/remotes/origin/feature", "refs/remotes/origin/main", "refs/remotes/origin/other"),
            remoteRefs(destination),
        )
        assertEquals("+refs/heads/*:refs/remotes/origin/*\n", git.run(destination, "config", "--get-all", "remote.origin.fetch"))
    }

    @Test
    fun `a tag is checked out with a detached HEAD, annotated or not`(): Unit = runBlocking {
        val tagged = git.run(source, "rev-parse", "main").trim()

        for (tag in listOf("v0", "v1")) {
            val destination = File(clones, tag)

            val states = clone(destination, branch = tag)

            assertEquals(CloneState.Completed(destination), states.last()) { "$tag: $states" }
            assertEquals("HEAD", git.run(destination, "rev-parse", "--abbrev-ref", "HEAD").trim())
            assertEquals(tagged, git.run(destination, "rev-parse", "HEAD").trim())
            assertFalse(File(destination, "feature.txt").exists())
            assertEquals("", git.run(destination, "status", "--porcelain"))
        }
    }

    @Test
    fun `only this branch fetches that branch alone`(): Unit = runBlocking {
        val destination = File(clones, "copy")

        val states = clone(destination, branch = "feature", singleBranch = true)

        assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
        assertEquals("refs/heads/feature", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals("feature\n", File(destination, "feature.txt").readText())
        assertEquals(listOf("refs/remotes/origin/feature"), remoteRefs(destination))
        assertEquals(
            "+refs/heads/feature:refs/remotes/origin/feature\n",
            git.run(destination, "config", "--get-all", "remote.origin.fetch"),
        )
    }

    @Test
    fun `only a tag fetches that tag, and no branch`(): Unit = runBlocking {
        val destination = File(clones, "copy")

        val states = clone(destination, branch = "v1", singleBranch = true)

        assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
        assertEquals("HEAD", git.run(destination, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals(emptyList<String>(), remoteRefs(destination))
        assertEquals("+refs/tags/v1:refs/tags/v1\n", git.run(destination, "config", "--get-all", "remote.origin.fetch"))
    }

    @Test
    fun `only this branch without a branch clones the default branch alone`(): Unit = runBlocking {
        val destination = File(clones, "copy")

        val states = clone(destination, singleBranch = true)

        assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
        assertEquals("refs/heads/main", git.run(destination, "symbolic-ref", "HEAD").trim())
        assertEquals(listOf("refs/remotes/origin/main"), remoteRefs(destination))
    }

    @Test
    fun `a branch name is trimmed, and a blank one is the default branch`(): Unit = runBlocking {
        val trimmed = File(clones, "trimmed")
        assertEquals(CloneState.Completed(trimmed), clone(trimmed, branch = " feature ", singleBranch = true).last())
        assertEquals("refs/heads/feature", git.run(trimmed, "symbolic-ref", "HEAD").trim())

        val blank = File(clones, "blank")
        assertEquals(CloneState.Completed(blank), clone(blank, branch = "  ").last())
        assertEquals("refs/heads/main", git.run(blank, "symbolic-ref", "HEAD").trim())
    }

    @Test
    fun `a branch that the remote lacks says so, and leaves no folder`(): Unit = runBlocking {
        for (singleBranch in listOf(false, true)) {
            val destination = File(clones, "missing-$singleBranch")

            val states = clone(destination, branch = "nope", singleBranch = singleBranch)

            val error = (states.last() as CloneState.Fail).reason
            assertInstanceOf(RemoteOperationError.BranchNotFound::class.java, error) { "$singleBranch: $error" }
            assertEquals("nope", (error as RemoteOperationError.BranchNotFound).branch)
            assertTrue(error.output.contains("not found in upstream origin")) { error.output }
            assertFalse(destination.exists())
        }
    }

    private suspend fun clone(destination: File, branch: String? = null, singleBranch: Boolean = false) =
        CloneRepositoryGitAction(transport(), InitializeAllSubmodulesGitAction())(
            destination,
            "file://${source.absolutePath}",
            cloneSubmodules = false,
            branch = branch,
            singleBranch = singleBranch,
        ).toList()

    private fun transport() = HandleTransportGitAction(
        noSshSessionFactory = NoSshSessionFactory { null },
        httpCredentialsProvider = object : HttpCredentialsFactory {
            override fun create(git: Git?) = error("No HTTPS in this test")
        },
        jgit = jgit,
    )

    /** The remote-tracking refs of a clone, sorted. */
    private fun remoteRefs(repository: File) =
        git.run(repository, "for-each-ref", "--format=%(refname)", "refs/remotes").lines().filter { it.isNotEmpty() }.sorted()
}
