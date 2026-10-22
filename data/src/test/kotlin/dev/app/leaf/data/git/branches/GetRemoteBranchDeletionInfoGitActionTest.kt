// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.cli.remote.testRemoteOperationsBackend
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.TipCommit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.SshNeedsGitError
import dev.app.leaf.domain.interfaces.RemoteBranchDeletionRead
import dev.app.leaf.domain.models.MergedIntoBase
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the "Delete branches" dialog reads about a remote's branches ([GetRemoteBranchDeletionInfoGitAction]), from a
 * clone of a bare repository on disk. Skipped when the askpass helper isn't built, as the action needs git to run.
 */
@DisabledOnOs(OS.WINDOWS)
class GetRemoteBranchDeletionInfoGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var bare: File
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig)

        bare = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", bare.absolutePath)
        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", bare.absolutePath, "main")

        // A clone, so that origin/HEAD names the default branch
        work = File(tempDir, "work")
        git.run(tempDir, "clone", bare.absolutePath, work.absolutePath)
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `merged by its commits, by its changes, not merged, and on other branches`(): Unit = runBlocking {
        branchOnOrigin("merged", commits = 2)
        git.run(work, "merge", "--no-ff", "-m", "Merge merged", "origin/merged")
        branchOnOrigin("squashed", commits = 3)
        git.run(work, "merge", "--squash", "origin/squashed")
        git.run(work, "commit", "-m", "Squashed")
        git.run(work, "push", "origin", "main")
        branchOnOrigin("unmerged", commits = 2)
        branchOnOrigin("on-local", commits = 1, keepLocal = true)

        val read = read("merged", "squashed", "unmerged", "on-local")
        val plan = BranchDeletionPlan(read.base, read.infos, remote = read.remote)

        assertEquals("refs/remotes/origin/main", read.base)
        assertEquals(
            listOf(
                BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0),
                BranchDeletionKind.Merged(MergedIntoBase.CHANGES, 3),
                BranchDeletionKind.Unmerged(2),
                BranchDeletionKind.OnOtherRefs,
            ),
            read.infos.map { plan.kindOf(it) },
        )
        assertEquals(
            listOf("merged", "squashed", "unmerged", "on-local").map { git.run(work, "rev-parse", "origin/$it").trim() },
            read.infos.map { it.branch.tip },
        )
    }

    @Test
    fun `for delete merged branches, the commits of a branch that isn't merged aren't counted`(): Unit = runBlocking {
        branchOnOrigin("squashed", commits = 2)
        git.run(work, "merge", "--squash", "origin/squashed")
        git.run(work, "commit", "-m", "Squashed")
        git.run(work, "push", "origin", "main")
        branchOnOrigin("unmerged", commits = 2)

        val names = listOf("squashed", "unmerged").map { "refs/remotes/origin/$it" }
        val infos = remoteAction()(gitDir, "origin", "refs/heads/main", names, onlyMerged = true).okOrThrow().infos

        assertEquals(listOf(MergedIntoBase.CHANGES, null), infos.map { it.merged })
        assertEquals(listOf(2, 0), infos.map { it.commitsOnlyHere }, "The squash-merged one still says what goes")
    }

    @Test
    fun `two selected branches at one commit don't make each other look safe`(): Unit = runBlocking {
        branchOnOrigin("a", commits = 1)
        git.run(work, "push", "origin", "refs/remotes/origin/a:refs/heads/b")
        git.run(work, "fetch", "origin")

        assertEquals(listOf(1, 1), read("a", "b").infos.map { it.commitsOnlyHere })
        assertEquals(listOf(0), read("a").infos.map { it.commitsOnlyHere }, "b stays, and has a's commit")
    }

    @Test
    fun `the remote's HEAD and default branch are refused, and merged means merged into the default branch`(): Unit =
        runBlocking {
            // develop is the worktrees' base here, and has a branch that origin/main doesn't
            branchOnOrigin("in-develop", commits = 1)
            git.run(work, "switch", "-c", "develop")
            git.run(work, "merge", "--no-ff", "-m", "Merge in-develop", "origin/in-develop")
            git.run(work, "switch", "main")

            val read = read("HEAD", "main", "in-develop", base = "refs/heads/develop")

            assertEquals("refs/remotes/origin/main", read.base)
            assertEquals(
                listOf(BranchKept.RemoteHead, BranchKept.RemoteDefaultBranch(isNamedByRemote = true), null),
                read.infos.map { it.refusal },
            )
            assertNull(read.infos[2].merged, "It's merged into develop, not into origin/main")
        }

    @Test
    fun `without a HEAD on the remote, the base's branch there is refused, and merged means merged into the base`(): Unit =
        runBlocking {
            git.run(work, "remote", "set-head", "origin", "--delete")
            branchOnOrigin("done", commits = 1)
            git.run(work, "merge", "--no-ff", "-m", "Merge done", "origin/done")

            val read = read("main", "done", base = "refs/heads/main")

            assertEquals("refs/heads/main", read.base)
            assertEquals(listOf(BranchKept.RemoteDefaultBranch(isNamedByRemote = false), null), read.infos.map { it.refusal })
            assertEquals(MergedIntoBase.COMMITS, read.infos[1].merged)
        }

    @Test
    fun `without a HEAD on the remote nor a base, nothing is merged and nothing is refused`(): Unit = runBlocking {
        git.run(work, "remote", "set-head", "origin", "--delete")
        branchOnOrigin("done", commits = 1)

        val read = read("main", "done", base = null)

        assertNull(read.base)
        assertEquals(listOf(null, null), read.infos.map { it.refusal })
        assertEquals(listOf(null, null), read.infos.map { it.merged })
    }

    @Test
    fun `each branch says who made its tip and when`(): Unit = runBlocking {
        git.run(work, "switch", "-c", "by-alex")
        File(work, "alex.txt").writeText("Alex\n")
        git.run(work, "add", ".")
        git.run(
            work,
            mapOf("GIT_COMMITTER_DATE" to "2026-10-01T12:00:00Z", "GIT_AUTHOR_NAME" to "Alex Doe"),
            "-c", "user.name=Alex Doe", "commit", "-m", "Alex's change",
        )
        git.run(work, "push", "origin", "by-alex")
        git.run(work, "switch", "main")

        val tip = read("by-alex").infos.single().lastCommit

        assertEquals(TipCommit("Alex Doe", 1_790_856_000_000L), tip)
    }

    @Test
    fun `the remote's URL without its password, and no fetch time for another URL`(): Unit = runBlocking {
        git.run(work, "fetch", "origin")
        git.run(work, "remote", "set-url", "origin", "https://alex:secret@example.invalid/team/app.git")

        val read = read("main")

        assertEquals("https://alex:***@example.invalid/team/app.git", read.remote.url)
        assertNull(read.remote.lastFetchedMillis, "FETCH_HEAD names the folder origin was fetched from before")
    }

    @Test
    fun `the last fetch's time, when FETCH_HEAD names the remote`(): Unit = runBlocking {
        git.run(work, "fetch", "origin")
        val fetchHead = File(work, ".git/FETCH_HEAD")
        fetchHead.setLastModified(1_790_000_000_000L)

        assertEquals(1_790_000_000_000L, read("main").remote.lastFetchedMillis)

        // Fetching another remote writes its own FETCH_HEAD
        val other = File(tempDir, "other.git")
        git.run(tempDir, "clone", "--bare", bare.absolutePath, other.absolutePath)
        git.run(work, "remote", "add", "other", other.absolutePath)
        git.run(work, "fetch", "other")

        assertNull(read("main").remote.lastFetchedMillis)
    }

    @Test
    fun `URLs as git writes them in FETCH_HEAD`() {
        assertEquals("/work/origin", fetchHeadUrl("/work/origin.git"))
        assertEquals("/work/origin", fetchHeadUrl("/work/origin.git/"))
        assertEquals("/work/a@b/origin", fetchHeadUrl("/work/a@b/origin.git"))
        assertEquals("https://example.invalid/team/app", fetchHeadUrl("https://alex:secret@example.invalid/team/app.git"))
        assertEquals("https://example.invalid/a@b", fetchHeadUrl("https://example.invalid/a@b"))
        assertEquals("ssh://example.invalid:2222/team/app", fetchHeadUrl("ssh://git@example.invalid:2222/team/app.git"))
        assertEquals("github.com:team/app", fetchHeadUrl("git@github.com:team/app.git"))
        assertEquals("a.git", fetchHeadUrl("a.git"))
    }

    @Test
    fun `the last fetch's time from a URL with a user, which git leaves out of FETCH_HEAD`() {
        val repository = File(tempDir, "fetch-head")
        val gitDirectory = File(repository, ".git").apply { mkdirs() }
        File(gitDirectory, "FETCH_HEAD").writeText(
            "${"a".repeat(40)}\t\tbranch 'main' of https://example.invalid/team/app\n" +
                "${"b".repeat(40)}\tnot-for-merge\tbranch 'x' of https://example.invalid/team/app\n"
        )
        File(gitDirectory, "FETCH_HEAD").setLastModified(1_790_000_000_000L)
        git.run(repository, "init")

        FileRepositoryBuilder().setGitDir(gitDirectory).build().use { jgitRepository ->
            assertEquals(1_790_000_000_000L, lastFetchedMillis(jgitRepository, "https://alex@example.invalid/team/app.git"))
            assertEquals(1_790_000_000_000L, lastFetchedMillis(jgitRepository, "https://example.invalid/team/app"))
            assertNull(lastFetchedMillis(jgitRepository, "https://example.invalid/team/other"))
            assertNull(lastFetchedMillis(jgitRepository, null))
        }
    }

    @Test
    fun `another remote's branches, missing ones and local ones are left out`(): Unit = runBlocking {
        branchOnOrigin("feature", commits = 1, keepLocal = true)

        val infos = remoteAction()(
            gitDir,
            "origin",
            "refs/heads/main",
            listOf("refs/remotes/origin/feature", "refs/remotes/origin/missing", "refs/heads/feature", "refs/remotes/up/x"),
            onlyMerged = false,
        ).okOrThrow().infos

        assertEquals(listOf("refs/remotes/origin/feature"), infos.map { it.branch.name })
    }

    @Test
    fun `without git for remote operations, it says that git is needed`(): Unit = runBlocking {
        val action = GetRemoteBranchDeletionInfoGitAction(
            jgit,
            remote.gitCli,
            testRemoteOperationsBackend(jgit, remote.gitCli, builtAskpassHelper(), remoteOperationsWithGit = false),
        )

        assertEquals(
            Either.Err(RemoteBranchesNeedGitError(SshNeedsGitError.Reason.SettingOff)),
            action(gitDir, "origin", "refs/heads/main", listOf("refs/remotes/origin/main"), onlyMerged = false),
        )
    }

    private fun remoteAction() = GetRemoteBranchDeletionInfoGitAction(
        jgit,
        remote.gitCli,
        testRemoteOperationsBackend(jgit, remote.gitCli, builtAskpassHelper()),
    )

    private suspend fun read(vararg names: String, base: String? = "refs/heads/main"): RemoteBranchDeletionRead =
        remoteAction()(gitDir, "origin", base, names.map { "refs/remotes/origin/$it" }, onlyMerged = false).okOrThrow()

    private fun <T> Either<T, *>.okOrThrow(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> throw AssertionError("The action failed: $error")
    }

    /**
     * Pushes [name] to origin with [commits] commits of its own, each changing a file of its own, from main, then
     * deletes the local branch unless [keepLocal], so that only the remote-tracking branch has its commits.
     */
    private fun branchOnOrigin(name: String, commits: Int, keepLocal: Boolean = false) {
        git.run(work, "switch", "-c", name)

        repeat(commits) { index ->
            File(work, "$name-${index + 1}.txt").writeText("$name ${index + 1}\n")
            git.run(work, "add", ".")
            git.run(work, "commit", "-m", "$name ${index + 1}")
        }

        git.run(work, "push", "origin", name)
        git.run(work, "switch", "main")

        if (!keepLocal) {
            git.run(work, "branch", "-D", name)
        }
    }
}
