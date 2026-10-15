// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.git.MergeContents.Clean
import dev.app.leaf.data.git.MergeContents.Companion.FILE
import dev.app.leaf.data.git.MergeContents.Companion.ZDIFF3
import dev.app.leaf.data.git.MergeContents.Companion.conflictIn
import dev.app.leaf.data.git.MergeContents.Conflict
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.GitCliPullBranchGitAction
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.remote_operations.HandleTransportGitAction
import dev.app.leaf.data.git.remote_operations.HasPullResultConflictsGitAction
import dev.app.leaf.data.git.remote_operations.NoSshSessionFactory
import dev.app.leaf.data.git.remote_operations.PullBranchGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.PullHasConflicts
import dev.app.leaf.domain.models.PullType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Pulls, with JGit and with the git CLI, in a repository that sets `merge.conflictStyle` or `diff.algorithm` to a
 * value that git accepts and JGit's enums lack. JGit merges or rebases what both fetched, and failed with "Invalid
 * value" before [GitCompatibleConfigGetter]. The repository's config is never changed.
 */
class GitOnlyConfigValuesPullTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()
    private lateinit var git: TestGitCli
    private lateinit var globalConfig: File
    private lateinit var origin: File
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        readGitOnlyConfigValues()
        globalConfig = File(tempDir, "empty.gitconfig")
        git = TestGitCli(globalConfig)
        origin = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", origin.absolutePath)
    }

    @AfterEach
    fun restore() {
        Config.setTypedConfigGetter(null)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `JGit's merge pull stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        diverged(Conflict.base, local = Conflict.ours, remote = Conflict.theirs)

        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { jgitPull(PullType.MERGE) })

        assertEquals(Conflict.result, conflictIn(work))
    }

    @Test
    fun `JGit's rebase pull stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        // A rebase replays the local commit on the remote one, so the remote side is ours
        diverged(Conflict.base, local = Conflict.theirs, remote = Conflict.ours)

        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { jgitPull(PullType.REBASE) })

        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `JGit's merge pull merges`(algorithm: String): Unit = runBlocking {
        diverged(Clean.base, local = Clean.ours, remote = Clean.theirs)

        assertEquals(Either.Ok(false), keepingConfig("diff.algorithm=$algorithm") { jgitPull(PullType.MERGE) })

        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `JGit's rebase pull rebases`(algorithm: String): Unit = runBlocking {
        diverged(Clean.base, local = Clean.ours, remote = Clean.theirs)

        assertEquals(Either.Ok(false), keepingConfig("diff.algorithm=$algorithm") { jgitPull(PullType.REBASE) })

        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the git CLI's merge pull stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        diverged(Conflict.base, local = Conflict.ours, remote = Conflict.theirs)

        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { gitCliPull(PullType.MERGE) })

        assertEquals(Conflict.result, conflictIn(work))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the git CLI's rebase pull stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        diverged(Conflict.base, local = Conflict.theirs, remote = Conflict.ours)

        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { gitCliPull(PullType.REBASE) })

        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    @DisabledOnOs(OS.WINDOWS)
    fun `the git CLI's merge pull merges`(algorithm: String): Unit = runBlocking {
        diverged(Clean.base, local = Clean.ours, remote = Clean.theirs)

        assertEquals(Either.Ok(false), keepingConfig("diff.algorithm=$algorithm") { gitCliPull(PullType.MERGE) })

        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    @DisabledOnOs(OS.WINDOWS)
    fun `the git CLI's rebase pull rebases`(algorithm: String): Unit = runBlocking {
        diverged(Clean.base, local = Clean.ours, remote = Clean.theirs)

        assertEquals(Either.Ok(false), keepingConfig("diff.algorithm=$algorithm") { gitCliPull(PullType.REBASE) })

        assertEquals(Clean.result, File(work, FILE).readText())
    }

    /**
     * Runs [block] with [setting] (`section.key=value`) in the repository's config, and checks that the config file is
     * as it was.
     */
    private suspend fun <T> keepingConfig(setting: String, block: suspend () -> T): T {
        val (key, value) = setting.split("=", limit = 2)
        git.run(work, "config", key, value)

        val config = File(gitDir, "config")
        val before = config.readText()

        return block().also { assertEquals(before, config.readText(), "The repository's config changed") }
    }

    private suspend fun jgitPull(pullType: PullType): Either<PullHasConflicts, GitError> {
        val transport = HandleTransportGitAction(
            noSshSessionFactory = NoSshSessionFactory { null },
            httpCredentialsProvider = object : HttpCredentialsFactory {
                override fun create(git: Git?) = error("No HTTPS in this test")
            },
            jgit = jgit,
        )
        val action = PullBranchGitAction(
            checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
            handleTransportGitAction = transport,
            hasPullResultConflictsGitAction = HasPullResultConflictsGitAction(),
            deleteStashGitAction = DeleteStashGitAction(jgit),
            commitMapper = JGitCommitMapper(JGitIdentityMapper()),
            jgit = jgit,
        )

        return action(gitDir, pullType, mergeAutoStash = true, remoteBranch = null, "automatic stash")
    }

    private suspend fun gitCliPull(pullType: PullType): Either<PullHasConflicts, GitError> {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        val remote = TestRemoteCommand(helper!!, globalConfig)

        val action = GitCliPullBranchGitAction(
            jgit = jgit,
            remoteCommand = remote.command,
            gitLfsFetch = remote.gitLfsFetch,
            partialClone = PartialCloneFetch(jgit, remote.command),
            checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
            deleteStashGitAction = DeleteStashGitAction(jgit),
            commitMapper = JGitCommitMapper(JGitIdentityMapper()),
        )

        return action(gitDir, pullType, mergeAutoStash = true, remoteBranch = null, "automatic stash")
    }

    /**
     * Origin's main with [FILE] at [base], then [remote] pushed to it from another clone, and the clone [work], which
     * tracks it, with [local] committed on top of [base].
     */
    private fun diverged(base: String, local: String, remote: String) {
        val seed = git.initRepository(File(tempDir, "seed"))
        commitFile(seed, base)
        git.run(seed, "push", origin.absolutePath, "main")

        val other = clone("other")
        commitFile(other, remote)
        git.run(other, "push", "origin", "main")

        work = clone("work")
        git.run(work, "reset", "--hard", "HEAD~1")
        commitFile(work, local)
    }

    /** A clone of origin, with an identity for JGit's merges and rebases. */
    private fun clone(name: String): File {
        val directory = File(tempDir, name)
        git.run(tempDir, "clone", origin.absolutePath, directory.absolutePath)
        git.run(directory, "config", "user.name", "Leaf Test")
        git.run(directory, "config", "user.email", "test@example.invalid")

        return directory
    }

    private fun commitFile(repository: File, content: String) {
        File(repository, FILE).writeText(content)
        git.run(repository, "add", FILE)
        git.run(repository, "commit", "-m", "Change $FILE")
    }
}
