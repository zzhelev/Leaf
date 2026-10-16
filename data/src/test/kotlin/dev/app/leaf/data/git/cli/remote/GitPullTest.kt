// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.operations.testGitCliOperations
import dev.app.leaf.data.git.repository.IsUnchangedSinceGitAction
import dev.app.leaf.data.git.stash.CreateSnapshotStashGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.interfaces.PullHasConflicts
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pulling with `git pull` ([GitPullGitAction]) from bare repositories on disk, which another clone changes. Each case
 * is what `git pull --no-rebase` or `git pull --rebase` does in a terminal (git 2.54), with Leaf's backup of local
 * changes on top. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitPullTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()
    private val repositoryState = InMemoryRepositoryStateRepository()

    private lateinit var globalConfig: File
    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var origin: File
    private lateinit var work: File
    private lateinit var other: File

    private val gitDir get() = File(work, ".git").absolutePath

    /** How git names origin in its merge messages: its URL, without `.git`. */
    private val originName get() = origin.absolutePath.removeSuffix(".git")

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        globalConfig = File(tempDir, "global.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig, jgit = jgit)

        origin = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", origin.absolutePath)

        val seed = git.initRepository(File(tempDir, "seed"))
        commitIn(seed, "base")
        git.run(seed, "push", origin.absolutePath, "main")

        work = clone("work")
        other = clone("other")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a pull fast-forwards to the upstream's new commits, and updates the other remote branches`(): Unit =
        runBlocking {
            commitIn(other, "new on origin")
            git.run(other, "push", "origin", "main", "main:feature")

            assertEquals(Either.Ok(false), pull(PullType.MERGE))

            assertEquals(head(other), head(work))
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
    fun `pull_ff set to only refuses a merge, also from a file that includeIf names`(): Unit = runBlocking {
        val included = File(tempDir, "ff-only.gitconfig").apply { writeText("[pull]\n\tff = only\n") }
        globalConfig.writeText("[includeIf \"onbranch:main\"]\n\tpath = ${included.absolutePath}\n")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertInstanceOf(RemoteOperationError.Failed::class.java, error)
        assertTrue((error as RemoteOperationError).output.contains("Not possible to fast-forward")) { error.output }
        assertEquals(before, head(work))
    }

    @Test
    fun `pull_ff set to only still fast-forwards`(): Unit = runBlocking {
        git.run(work, "config", "pull.ff", "only")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals(head(other), head(work))
    }

    @Test
    fun `pull_ff set to only doesn't stop a rebase`(): Unit = runBlocking {
        git.run(work, "config", "pull.ff", "only")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^").trim())
    }

    @Test
    fun `a merge is named as git names it`(): Unit = runBlocking {
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^2").trim())
        assertEquals("Merge branch 'main' of $originName", subject(work))
    }

    @Test
    fun `a merge into another branch than main says which`(): Unit = runBlocking {
        git.run(work, "switch", "-c", "feat", "--track", "origin/main")
        commitIn(work, "local", file = "local.txt")
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals("Merge branch 'main' of $originName into feat", subject(work))
    }

    @Test
    fun `a pull with rebase puts the local commits on top`(): Unit = runBlocking {
        commitIn(other, "new on origin")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^").trim())
        assertEquals("local", subject(work))
    }

    @Test
    fun `a rebase onto a force-pushed upstream leaves out the commits that it replaced`(): Unit = runBlocking {
        commitIn(other, "upstream")
        git.run(other, "push", "origin", "main")
        git.run(work, "pull", "--ff-only")
        commitIn(work, "local", file = "local.txt")
        File(other, "file.txt").appendText("amended\n")
        git.run(other, "commit", "-a", "--amend", "-m", "upstream, amended")
        git.run(other, "push", "--force", "origin", "main")

        // A rebase onto the fetched commit would replay "upstream" too, which conflicts with its amended version.
        // git pull rebases from the fork point that the remote-tracking branch's reflog gives.
        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^").trim())
        assertEquals("local", subject(work))
    }

    @Test
    fun `a merge conflict is reported, and the backup stash of local changes is kept`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours")
        File(work, "unrelated.txt").writeText("uncommitted\n")

        assertEquals(Either.Ok(true), pull(PullType.MERGE))

        assertTrue(File(work, ".git/MERGE_HEAD").isFile)
        assertEquals("automatic stash", stashes(work))
    }

    @Test
    fun `a rebase conflict is reported, and the rebase is git's`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours")

        assertEquals(Either.Ok(true), pull(PullType.REBASE))

        assertTrue(File(work, ".git/rebase-merge").isDirectory)
        // Continue, Skip and Abort then run git (isRebaseStartedByGit)
        assertFalse(File(work, ".git/rebase-merge/onto_name").exists())
    }

    @Test
    fun `local changes that a fast-forward would overwrite stop the pull with git's message`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        File(work, "file.txt").appendText("uncommitted\n")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertOverwriteRefused(error, "Your local changes to the following files would be overwritten by merge")
        assertEquals(before, head(work))
        assertTrue(File(work, "file.txt").readText().endsWith("uncommitted\n"))
        // Nothing was merged, so no backup of the local changes is left behind
        assertEquals("", stashes(work))
    }

    @Test
    fun `local changes that a merge would overwrite stop the pull with git's message`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours", file = "local.txt")
        File(work, "file.txt").appendText("uncommitted\n")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertOverwriteRefused(error, "Your local changes to the following files would be overwritten by merge")
        assertEquals(before, head(work))
        assertEquals("", stashes(work))
    }

    @Test
    fun `an untracked file in the way stops the pull, and stays as it was`(): Unit = runBlocking {
        commitIn(other, "theirs", file = "new.txt")
        git.run(other, "push", "origin", "main")
        File(work, "new.txt").writeText("mine\n")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertOverwriteRefused(error, "untracked working tree files would be overwritten by merge")
        assertEquals(before, head(work))
        assertEquals("mine\n", File(work, "new.txt").readText())
        assertEquals("", stashes(work))
    }

    @Test
    fun `a rebase with unstaged changes is refused before fetching`(): Unit = runBlocking {
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        File(work, "file.txt").appendText("uncommitted\n")
        val tracking = ref(work, "refs/remotes/origin/main")

        val error = (pull(PullType.REBASE) as Either.Err).error

        assertTrue((error as RemoteOperationError).output.contains("You have unstaged changes")) { error.output }
        assertEquals(tracking, ref(work, "refs/remotes/origin/main"))
    }

    @Test
    fun `rebase_autoStash sets local changes aside and puts them back`(): Unit = runBlocking {
        git.run(work, "config", "rebase.autoStash", "true")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")
        File(work, "README.md").appendText("uncommitted\n")

        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^").trim())
        assertTrue(File(work, "README.md").readText().endsWith("uncommitted\n"))
        assertEquals("", stashes(work))
        assertEquals(emptyList<Any>(), repositoryState.autostashNotices.value)
    }

    @Test
    fun `rebase_autoStash changes that don't go back stay in the stash, with a notice`(): Unit = runBlocking {
        git.run(work, "config", "rebase.autoStash", "true")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        File(work, "file.txt").appendText("mine\n")

        // git exits with 0, as the rebase itself worked
        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals(head(other), head(work))
        assertEquals("autostash", stashes(work))
        val notice = repositoryState.autostashNotices.value.single()
        assertEquals(AutostashOperation.REBASE, notice.setAsideFor)
        assertEquals(AutostashPutBackAfter.OPERATION, notice.putBackAfter)
        assertEquals(listOf("file.txt"), notice.conflicts)
    }

    @Test
    fun `merge_autoStash sets local changes aside and puts them back`(): Unit = runBlocking {
        git.run(work, "config", "merge.autoStash", "true")
        writeLinesAndPull()
        replaceLine(other, "6", "6 theirs")
        git.run(other, "commit", "-am", "theirs")
        git.run(other, "push", "origin", "main")
        replaceLine(work, "1", "1 mine")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals("1 mine\n2\n3\n4\n5\n6 theirs\n", File(work, "lines.txt").readText())
        // git put them back, and Leaf deleted its backup
        assertEquals("", stashes(work))
        assertEquals(emptyList<Any>(), repositoryState.autostashNotices.value)
    }

    @Test
    fun `merge_autoStash changes that don't go back stay in the stash, with a notice`(): Unit = runBlocking {
        git.run(work, "config", "merge.autoStash", "true")
        writeLinesAndPull()
        replaceLine(other, "1", "1 theirs")
        git.run(other, "commit", "-am", "theirs")
        git.run(other, "push", "origin", "main")
        replaceLine(work, "1", "1 mine")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals(head(other), head(work))
        assertEquals("autostash", stashes(work))
        val notice = repositoryState.autostashNotices.value.single()
        assertEquals(AutostashOperation.MERGE, notice.setAsideFor)
        assertEquals(listOf("lines.txt"), notice.conflicts)
    }

    @Test
    fun `merge_autoStash keeps the changes in MERGE_AUTOSTASH while the merge has conflicts`(): Unit = runBlocking {
        git.run(work, "config", "merge.autoStash", "true")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "ours")
        File(work, "README.md").appendText("uncommitted\n")

        assertEquals(Either.Ok(true), pull(PullType.MERGE))

        // Leaf's commit and Abort put them back (Autostashes)
        assertNotNull(ref(work, "MERGE_AUTOSTASH"))
        assertEquals("Test repository\n", File(work, "README.md").readText())
        assertEquals("automatic stash", stashes(work))
    }

    @Test
    fun `a merge pull runs git's hooks`(): Unit = runBlocking {
        File(work, ".git/hooks/post-merge").writeExecutable("#!/bin/sh\nprintf '%s\\n' \"\$1\" > .git/post-merge.log\n")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals("0\n", File(work, ".git/post-merge.log").readText())
    }

    @Test
    fun `a pre-merge-commit hook that refuses stops the merge, and the backup is kept`(): Unit = runBlocking {
        File(work, ".git/hooks/pre-merge-commit").writeExecutable("#!/bin/sh\nprintf '%s\\n' 'Not today' >&2\nexit 1\n")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")
        File(work, "README.md").appendText("uncommitted\n")

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertTrue((error as RemoteOperationError).output.contains("Not today")) { error.output }
        // git leaves the merge to commit, as in a terminal
        assertTrue(File(work, ".git/MERGE_HEAD").isFile)
        assertEquals("automatic stash", stashes(work))
    }

    @Test
    fun `a rebase pull runs the post-rewrite hook`(): Unit = runBlocking {
        File(work, ".git/hooks/post-rewrite")
            .writeExecutable("#!/bin/sh\nprintf '%s\\n' \"\$1\" > .git/post-rewrite.log\n")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")
        commitIn(work, "local", file = "local.txt")

        assertEquals(Either.Ok(false), pull(PullType.REBASE))

        assertEquals("rebase\n", File(work, ".git/post-rewrite.log").readText())
    }

    @Test
    fun `a merge follows a file that the branch renamed`(): Unit = runBlocking {
        git.run(work, "mv", "file.txt", "renamed.txt")
        git.run(work, "commit", "-m", "Rename")
        commitIn(other, "theirs")
        git.run(other, "push", "origin", "main")

        assertEquals(Either.Ok(false), pull(PullType.MERGE))

        assertEquals("base\ntheirs\n", File(work, "renamed.txt").readText())
        assertFalse(File(work, "file.txt").exists())
    }

    @Test
    fun `pulling a chosen remote branch merges that branch`(): Unit = runBlocking {
        commitIn(other, "on feature")
        git.run(other, "push", "origin", "main:feature")
        git.run(work, "fetch", "origin")
        commitIn(work, "local", file = "local.txt")

        val feature = Branch(hash = head(other), name = "refs/remotes/origin/feature", isLocal = false)
        assertEquals(Either.Ok(false), pull(PullType.MERGE, feature))

        assertEquals(head(other), git.run(work, "rev-parse", "HEAD^2").trim())
        assertEquals("Merge branch 'feature' of $originName", subject(work))
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
        assertEquals("Merge branch 'main' into topic", subject(work))
    }

    @Test
    fun `an upstream branch that the remote doesn't have is reported`(): Unit = runBlocking {
        git.run(work, "config", "branch.main.merge", "refs/heads/gone")

        val error = (pull(PullType.MERGE) as Either.Err).error

        val output = (error as RemoteOperationError).output
        assertTrue(output.contains("merge with the ref 'refs/heads/gone'"), output)
        assertTrue(output.contains("no such ref was fetched"), output)
    }

    @Test
    fun `a fetch that fails stops the pull before merging`(): Unit = runBlocking {
        git.run(work, "remote", "set-url", "origin", File(tempDir, "missing.git").absolutePath)
        File(work, "README.md").appendText("uncommitted\n")
        val before = head(work)

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertEquals(before, head(work))
        assertEquals("", stashes(work))
    }

    @Test
    fun `a detached HEAD is refused before git runs`(): Unit = runBlocking {
        git.run(work, "switch", "--detach")

        val error = (pull(PullType.MERGE) as Either.Err).error

        assertEquals("HEAD isn't on a branch, so there is no branch to pull into.", (error as GenericError).message)
    }

    @Test
    fun `a branch without commits takes the pulled commit`(): Unit = runBlocking {
        for (pullType in listOf(PullType.MERGE, PullType.REBASE)) {
            work = File(tempDir, "empty-$pullType").apply { mkdirs() }
            git.run(work, "init")
            git.run(work, "remote", "add", "origin", origin.absolutePath)

            assertEquals(Either.Ok(false), pull(pullType), "$pullType")

            assertEquals(ref(origin, "refs/heads/main"), head(work))
            assertEquals("Test repository\n", File(work, "README.md").readText())
        }
    }

    private suspend fun pull(pullType: PullType, remoteBranch: Branch? = null): Either<PullHasConflicts, GitError> =
        testGitPullGitAction(jgit, remote.gitCli, repositoryState)(
            gitDir,
            pullType,
            mergeAutoStash = true,
            remoteBranch,
            "automatic stash",
        )

    private fun assertOverwriteRefused(error: GitError, message: String) {
        assertInstanceOf(RemoteOperationError.Failed::class.java, error)
        val output = (error as RemoteOperationError).output
        assertTrue(output.contains(message)) { output }
    }

    /** A clone of origin, with an identity for git's merges and rebases. */
    private fun clone(name: String): File {
        val directory = File(tempDir, name)
        git.run(tempDir, "clone", origin.absolutePath, directory.absolutePath)
        git.run(directory, "config", "user.name", "Leaf Test")
        git.run(directory, "config", "user.email", "test@example.invalid")

        return directory
    }

    /** Pushes `lines.txt` with the lines 1 to 6 from other, and pulls it into work. */
    private fun writeLinesAndPull() {
        File(other, "lines.txt").writeText((1..6).joinToString("") { "$it\n" })
        git.run(other, "add", ".")
        git.run(other, "commit", "-m", "Lines")
        git.run(other, "push", "origin", "main")
        git.run(work, "pull", "--ff-only")
    }

    private fun replaceLine(repository: File, line: String, replacement: String) {
        val file = File(repository, "lines.txt")
        file.writeText(file.readLines().joinToString("") { if (it == line) "$replacement\n" else "$it\n" })
    }

    private fun commitIn(repository: File, message: String, file: String = "file.txt") {
        File(repository, file).appendText("$message\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", message)
    }

    private fun head(repository: File) = git.run(repository, "rev-parse", "HEAD").trim()

    private fun subject(repository: File) = git.run(repository, "log", "-1", "--format=%s").trim()

    private fun stashes(repository: File) = git.run(repository, "stash", "list", "--format=%gs").trim()

    private fun ref(repository: File, name: String) =
        runCatching { git.run(repository, "rev-parse", "--verify", "-q", name).trim() }.getOrNull()
}

/** [GitPullGitAction] as Dagger builds it, around [gitCli], with the built askpass helper. */
internal fun testGitPullGitAction(
    jgit: JGit,
    gitCli: GitCli,
    repositoryState: RepositoryStateRepository = InMemoryRepositoryStateRepository(),
) = GitPullGitAction(
    jgit = jgit,
    gitCli = gitCli,
    operations = testGitCliOperations(jgit, gitCli, repositoryState = repositoryState),
    checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
    createSnapshotStashGitAction = CreateSnapshotStashGitAction(JGitCommitMapper(JGitIdentityMapper()), jgit),
    deleteStashGitAction = DeleteStashGitAction(jgit),
    isUnchangedSinceGitAction = IsUnchangedSinceGitAction(jgit),
)
