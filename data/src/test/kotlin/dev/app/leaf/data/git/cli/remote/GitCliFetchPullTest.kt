// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.FetchRemotesError
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.interfaces.PullHasConflicts
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.PullType
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

private const val OVERWRITE_FILE_TXT =
    "The pull didn't merge, as it would overwrite your changes to file.txt. Commit or stash them, then pull again."

/**
 * Fetching and pulling with the git CLI ([GitCliFetchAllRemotesGitAction], [GitCliPullBranchGitAction]) from bare
 * repositories on disk, which another clone changes. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliFetchPullTest {
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

    @Test
    fun `a pull fast-forwards to the upstream's new commits, and updates the other remote branches`(): Unit =
        runBlocking {
            commitIn(other, "new on origin")
            git.run(other, "push", "origin", "main", "main:feature")

            assertEquals(Either.Ok(false), pull(PullType.MERGE))

            assertEquals(head(other), head(work))
            assertEquals(head(other), ref(work, "refs/remotes/origin/main"))
            assertEquals(head(other), ref(work, "refs/remotes/origin/feature"))
        }

    @Test
    fun `pull_ff set to false merges even when the pull could fast-forward`(): Unit = runBlocking {
        git.run(work, "config", "pull.ff", "false")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^2").trim())
    }

    @Test
    fun `pull_ff set to only refuses diverged branches`(): Unit = runBlocking {
        git.run(work, "config", "pull.ff", "only")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertTrue((error as GenericError).message.contains("only allows fast-forwards")) { error.message }
        assertEquals(before, head(work))
    }

    @Test
    fun `a pull merges diverged branches, naming the upstream as git does`(): Unit = runBlocking {
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals("${head(other)}", git.run(work, "rev-parse", "HEAD^2").trim())
        // git's own description from FETCH_HEAD, and " into main" as JGit's pull writes it
        assertEquals(
            "Merge branch 'main' of ${origin.absolutePath.removeSuffix(".git")} into main",
            git.run(work, "log", "-1", "--format=%s").trim(),
        )
    }

    @Test
    fun `a pull with rebase puts the local commits on top`(): Unit = runBlocking {
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^").trim())
        assertEquals("local", git.run(work, "log", "-1", "--format=%s").trim())
    }

    @Test
    fun `a merge conflict is reported, and the backup stash of local changes is kept`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours")
        File(work, "unrelated.txt").writeText("uncommitted\n")

        assertEquals(Either.Ok(true), pull(PullType.MERGE))

        assertTrue(File(work, ".git/MERGE_HEAD").isFile)
        assertEquals("automatic stash", git.run(work, "stash", "list", "--format=%gs").trim())
    }

    @Test
    fun `a rebase conflict is reported`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours")

        assertEquals(Either.Ok(true), pull(PullType.REBASE))

        assertTrue(File(work, ".git/rebase-merge").isDirectory)
    }

    @Test
    fun `local changes that a fast-forward would overwrite stop the pull, and are kept`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        File(work, "file.txt").writeText("uncommitted\n")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertEquals(GenericError(OVERWRITE_FILE_TXT), error.withoutException())
        assertEquals(before, head(work))
        assertEquals("uncommitted\n", File(work, "file.txt").readText())
        // Nothing was merged, so no backup of the local changes is left behind
        assertEquals("", git.run(work, "stash", "list"))
    }

    @Test
    fun `local changes that a merge would overwrite stop the pull, and are kept`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours", file = "local.txt")
        File(work, "file.txt").writeText("uncommitted\n")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertEquals(GenericError(OVERWRITE_FILE_TXT), error.withoutException())
        assertEquals(before, head(work))
        assertEquals("uncommitted\n", File(work, "file.txt").readText())
        // Nothing was merged, so no backup of the local changes is left behind
        assertEquals("", git.run(work, "stash", "list"))
    }

    @Test
    fun `pulling a chosen remote branch merges that branch`(): Unit = runBlocking {
        commitIn(other, "on feature")
        git.run(other, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")

        val feature = Branch(hash = head(other), name = "refs/remotes/origin/feature", isLocal = false)
        assertEquals(Either.Ok(false), pull(PullType.MERGE, feature))

        assertEquals(head(other), head(work))
    }

    @Test
    fun `a branch without upstream pulls the branch of the same name from origin`(): Unit = runBlocking {
        git.run(work, "branch", "--unset-upstream")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals(head(other), head(work))
    }

    @Test
    fun `a branch whose upstream is a local branch pulls it without fetching`(): Unit = runBlocking {
        git.run(work, "switch", "-c", "topic")
        git.run(work, "config", "branch.topic.remote", ".")
        git.run(work, "config", "branch.topic.merge", "refs/heads/main")
        git.run(work, "switch", "main")
        commitIn(work, "on main")
        val main = head(work)
        git.run(work, "switch", "topic")
        commitIn(work, "on topic", file = "topic.txt")
        git.run(work, "remote", "set-url", "origin", File(tempDir, "missing.git").absolutePath)

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals(main, git.run(work, "rev-parse", "HEAD^2").trim())
        assertEquals("Merge branch 'main' into topic", git.run(work, "log", "-1", "--format=%s").trim())
    }

    @Test
    fun `an upstream branch that the remote doesn't have is reported`(): Unit = runBlocking {
        git.run(work, "config", "branch.main.merge", "refs/heads/gone")

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertEquals(GenericError("origin has no branch gone to pull."), error)
    }

    @Test
    fun `a fetch that fails stops the pull before merging`(): Unit = runBlocking {
        git.run(work, "remote", "set-url", "origin", File(tempDir, "missing.git").absolutePath)
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertEquals(before, head(work))
    }

    @Test
    fun `a branch without commits takes the pulled commit`(): Unit = runBlocking {
        work = File(tempDir, "empty").apply { mkdirs() }
        git.run(work, "init")
        git.run(work, "remote", "add", "origin", origin.absolutePath)

        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(ref(origin, "refs/heads/main"), head(work))
        assertEquals("Test repository\n", File(work, "README.md").readText())
    }

    private suspend fun fetch(specificRemote: Remote? = null): Either<Unit, GitError> =
        GitCliFetchAllRemotesGitAction(jgit, remote.command)(gitDir, specificRemote)

    private suspend fun pull(pullType: PullType, remoteBranch: Branch? = null): Either<PullHasConflicts, GitError> {
        val action = GitCliPullBranchGitAction(
            jgit = jgit,
            remoteCommand = remote.command,
            checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
            deleteStashGitAction = DeleteStashGitAction(jgit),
            commitMapper = JGitCommitMapper(JGitIdentityMapper()),
        )

        return action(gitDir, pullType, mergeAutoStash = true, remoteBranch, "automatic stash")
    }

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

    private fun GitError.withoutException() = if (this is GenericError) copy(exception = null) else this
}
