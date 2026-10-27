// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.FetchRemotesError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.Remote
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Fetching with the git CLI ([GitCliFetchAllRemotesGitAction]), from bare repositories on disk, which another clone
 * changes. `git pull` is [GitPullTest]'s. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliFetchTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var origin: File
    private lateinit var work: File
    private lateinit var other: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig)

        origin = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", origin.absolutePath)

        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", origin.absolutePath, "main")

        work = clone("work")
        other = clone("other")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `fetching brings each remote's new commits and prunes deleted branches`(): Unit = runBlocking {
        val second = File(tempDir, "second.git")
        git.run(tempDir, "clone", "--bare", origin.absolutePath, second.absolutePath)
        git.run(work, "remote", "add", "second", second.absolutePath)
        git.run(other, "push", "origin", "main:feature")
        git.run(work, "fetch", "--all")

        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main", ":feature")
        git.run(other, "push", second.absolutePath, "main:extra")

        assertEquals(Either.Ok(Unit), fetch())

        assertEquals(head(other), ref(work, "refs/remotes/origin/main"))
        assertNull(ref(work, "refs/remotes/origin/feature"))
        assertEquals(head(other), ref(work, "refs/remotes/second/extra"))
    }

    @Test
    fun `a remote that fails is reported, and the others are still fetched`(): Unit = runBlocking {
        git.run(work, "remote", "add", "broken", File(tempDir, "missing.git").absolutePath)
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")

        val error = (fetch() as Either.Err).error

        assertInstanceOf(FetchRemotesError::class.java, error)
        val failure = (error as FetchRemotesError).failures.single()
        assertEquals("broken", failure.remote)
        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, failure.error)
        assertTrue(failure.error.output.contains("does not appear to be a git repository")) { failure.error.output }
        assertEquals(head(other), ref(work, "refs/remotes/origin/main"))
    }

    @Test
    fun `a chosen remote is fetched alone`(): Unit = runBlocking {
        val second = File(tempDir, "second.git")
        git.run(tempDir, "clone", "--bare", origin.absolutePath, second.absolutePath)
        git.run(work, "remote", "add", "second", second.absolutePath)
        val originBefore = ref(work, "refs/remotes/origin/main")
        commitIn(other, "new everywhere")
        git.run(other, "push", "origin", "main")
        git.run(other, "push", second.absolutePath, "main")

        assertEquals(Either.Ok(Unit), fetch(Remote("second", second.absolutePath, second.absolutePath)))

        assertEquals(head(other), ref(work, "refs/remotes/second/main"))
        assertEquals(originBefore, ref(work, "refs/remotes/origin/main"))
    }

    private suspend fun fetch(specificRemote: Remote? = null): Either<Unit, GitError> =
        GitCliFetchAllRemotesGitAction(jgit, remote.command)(gitDir, specificRemote)

    /** A clone of origin, with an identity for JGit's merges and rebases. */
    private fun clone(name: String): File {
        val directory = File(tempDir, name)
        git.run(tempDir, "clone", origin.absolutePath, directory.absolutePath)
        git.run(directory, "config", "user.name", "Leaf Test")
        git.run(directory, "config", "user.email", "test@example.invalid")

        return directory
    }

    private fun commitIn(repository: File, message: String, file: String = "file.txt") {
        File(repository, file).appendText("$message\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", message)
    }

    private fun head(repository: File) = git.run(repository, "rev-parse", "HEAD").trim()

    private fun ref(repository: File, name: String) =
        runCatching { git.run(repository, "rev-parse", "--verify", "-q", name).trim() }.getOrNull()

}
