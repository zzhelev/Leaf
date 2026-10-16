// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AutostashNotice
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBack
import dev.app.leaf.domain.models.AutostashPutBackAfter
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Leaf puts back what `merge.autoStash` set aside after its own commit ([PutBackMergeAutostashGitAction]), as
 * `git commit` does. Each case runs twice from the same start: once with `git commit`, once with JGit's commit, as
 * Leaf commits, then the put-back. Both must end the same.
 */
@DisabledOnOs(OS.WINDOWS)
class AutostashesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val gitCli by lazy {
        testGitCli(mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }
    private val repositoryState = InMemoryRepositoryStateRepository()
    private val putBack by lazy { PutBackMergeAutostashGitAction(Autostashes(gitCli, repositoryState), testJGit()) }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        // git refuses to make an identity up on Linux
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `does nothing when git set nothing aside`(): Unit = runBlocking {
        val repository = mergeStoppedAtConflicts("leaf", localChanges = emptyMap())
        commitResolvedWithJGit(repository)

        assertEquals(Either.Ok(AutostashPutBack.None), putBack(gitDir(repository), AutostashPutBackAfter.COMMIT))
        assertEquals(emptyList<String>(), status(repository))
        assertEquals(emptyList<AutostashNotice>(), repositoryState.autostashNotices.value)
    }

    @Test
    fun `puts the changes back after Leaf's commit, as git's commit does`(): Unit = runBlocking {
        val changes = mapOf("other.txt" to "local\n")
        val withGit = mergeStoppedAtConflicts("git", changes)
        val withLeaf = mergeStoppedAtConflicts("leaf", changes)
        assertEquals("o1\n", File(withLeaf, "other.txt").readText(), "git set the change aside")

        commitResolvedWithGit(withGit)
        commitResolvedWithJGit(withLeaf)
        val result = putBack(gitDir(withLeaf), AutostashPutBackAfter.COMMIT)

        assertEquals(Either.Ok(AutostashPutBack.Applied), result)
        assertEndsLike(withGit, withLeaf, "other.txt")
        assertEquals(listOf(" M other.txt"), status(withLeaf))
        assertEquals(emptyList<AutostashNotice>(), repositoryState.autostashNotices.value)
    }

    @Test
    fun `keeps them in the stash list as autostash when they conflict, and says so`(): Unit = runBlocking {
        // The merge changes merged.txt too
        val changes = mapOf("merged.txt" to "local\n")
        val withGit = mergeStoppedAtConflicts("git", changes)
        val withLeaf = mergeStoppedAtConflicts("leaf", changes)

        commitResolvedWithGit(withGit)
        commitResolvedWithJGit(withLeaf)
        val result = putBack(gitDir(withLeaf), AutostashPutBackAfter.COMMIT)

        assertEquals(Either.Ok(AutostashPutBack.Stored(listOf("merged.txt"))), result)
        assertEndsLike(withGit, withLeaf, "merged.txt")
        assertEquals(listOf("UU merged.txt"), status(withLeaf))
        assertEquals(listOf("autostash"), stashes(withLeaf))
        assertEquals(
            listOf(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.COMMIT, listOf("merged.txt"), 0)),
            notices(),
        )
    }

    @Test
    fun `keeps them in the stash list when changes made since are in the way`(): Unit = runBlocking {
        val changes = mapOf("other.txt" to "local\n")
        val withGit = mergeStoppedAtConflicts("git", changes)
        val withLeaf = mergeStoppedAtConflicts("leaf", changes)
        File(withGit, "other.txt").writeText("during the merge\n")
        File(withLeaf, "other.txt").writeText("during the merge\n")

        commitResolvedWithGit(withGit)
        commitResolvedWithJGit(withLeaf)
        val result = putBack(gitDir(withLeaf), AutostashPutBackAfter.COMMIT)

        assertEquals(Either.Ok(AutostashPutBack.Stored(emptyList())), result)
        assertEndsLike(withGit, withLeaf, "other.txt")
        assertEquals("during the merge\n", File(withLeaf, "other.txt").readText())
        assertEquals(listOf("autostash"), stashes(withLeaf))
        assertEquals(
            listOf(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.COMMIT, emptyList(), 0)),
            notices(),
        )
    }

    @Test
    fun `puts back a linked worktree's own, which the other worktrees don't see`(): Unit = runBlocking {
        val main = mergeStoppedAtConflicts("main", localChanges = emptyMap())
        commitResolvedWithJGit(main)
        val linked = File(tempDir, "linked")
        git.run(main, "worktree", "add", linked.path, "-b", "linked", "main~1")
        git.run(linked, "commit", "--allow-empty", "-m", "Diverge")
        File(linked, "other.txt").writeText("local\n")
        git.runFailing(linked, "merge", "--no-edit", "feature")

        // The main worktree has none
        assertEquals(Either.Ok(AutostashPutBack.None), putBack(gitDir(main), AutostashPutBackAfter.COMMIT))

        commitResolvedWithJGit(linked)
        val linkedGitDir = git.run(linked, "rev-parse", "--absolute-git-dir").trim()

        assertEquals(Either.Ok(AutostashPutBack.Applied), putBack(linkedGitDir, AutostashPutBackAfter.COMMIT))
        assertEquals("local\n", File(linked, "other.txt").readText())
        assertEquals("o1\n", File(main, "other.txt").readText())
        assertNoMergeAutostash(linked)
    }

    @Test
    fun `asks git for it, as a reftable repository has no file`(): Unit = runBlocking {
        assumeTrue(
            runCatching { git.run(tempDir, "init", "--ref-format=reftable", File(tempDir, "probe").path) }.isSuccess,
            "This git has no reftable backend (2.45 or newer)",
        )
        val repository = mergeStoppedAtConflicts("leaf", mapOf("other.txt" to "local\n"), "--ref-format=reftable")
        assertEquals(false, File(gitDir(repository), "MERGE_AUTOSTASH").exists())

        commitResolvedWithJGit(repository)

        assertEquals(Either.Ok(AutostashPutBack.Applied), putBack(gitDir(repository), AutostashPutBackAfter.COMMIT))
        assertEquals("local\n", File(repository, "other.txt").readText())
        assertNoMergeAutostash(repository)
    }

    @Test
    fun `takes only a new stash named autostash for one that git kept`(): Unit = runBlocking {
        val autostashes = Autostashes(gitCli, repositoryState)
        val repository = mergeStoppedAtConflicts("leaf", localChanges = emptyMap())
        commitResolvedWithJGit(repository)
        val before = autostashes.newestStash(repository)

        // Another tool's stash, made meanwhile
        File(repository, "other.txt").writeText("an agent's change\n")
        git.run(repository, "stash", "push", "-m", "made by an agent")
        autostashes.noticeIfKept(repository, before, AutostashOperation.MERGE, AutostashPutBackAfter.OPERATION)

        assertEquals(emptyList<AutostashNotice>(), notices())

        File(repository, "other.txt").writeText("set aside\n")
        git.run(repository, "stash", "store", "-m", "autostash", git.run(repository, "stash", "create").trim())
        git.run(repository, "checkout", "other.txt")
        autostashes.noticeIfKept(repository, before, AutostashOperation.MERGE, AutostashPutBackAfter.OPERATION)

        assertEquals(
            listOf(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.OPERATION, emptyList(), 0)),
            notices(),
        )
    }

    /**
     * A repository where main and feature both changed conflict.txt, and feature merged.txt, merged with
     * `merge.autoStash` and [localChanges] in the working tree, which git sets aside. The merge stops at conflicts.
     */
    private fun mergeStoppedAtConflicts(name: String, localChanges: Map<String, String>, vararg initArgs: String): File {
        val repository = File(tempDir, name)
        repository.mkdirs()
        git.run(repository, "init", *initArgs)
        git.run(repository, "config", "merge.autoStash", "true")
        File(repository, "conflict.txt").writeText("base\n")
        File(repository, "other.txt").writeText("o1\n")
        File(repository, "merged.txt").writeText("m1\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "switch", "-c", "feature")
        File(repository, "conflict.txt").writeText("feature\n")
        File(repository, "merged.txt").writeText("m2\n")
        git.run(repository, "commit", "-am", "Feature")
        git.run(repository, "switch", "main")
        File(repository, "conflict.txt").writeText("main\n")
        git.run(repository, "commit", "-am", "Main")

        for ((file, content) in localChanges) {
            File(repository, file).writeText(content)
        }

        git.runFailing(repository, "merge", "--no-edit", "feature")

        return repository
    }

    private fun resolve(repository: File) {
        File(repository, "conflict.txt").writeText("resolved\n")
        git.run(repository, "add", "conflict.txt")
    }

    private fun commitResolvedWithGit(repository: File) {
        resolve(repository)
        git.run(repository, "commit", "--no-edit")
    }

    /** Commits as Leaf does, with JGit, which leaves `MERGE_AUTOSTASH` alone. The builder reads a `.git` file. */
    private fun commitResolvedWithJGit(repository: File) {
        resolve(repository)
        Git.wrap(FileRepositoryBuilder().setWorkTree(repository).build()).use {
            it.commit().setMessage("Merge branch 'feature'").call()
        }
    }

    private fun assertEndsLike(withGit: File, withLeaf: File, file: String) {
        assertEquals(File(withGit, file).readText(), File(withLeaf, file).readText())
        assertEquals(status(withGit), status(withLeaf))
        assertEquals(stashes(withGit), stashes(withLeaf))
        assertNoMergeAutostash(withGit)
        assertNoMergeAutostash(withLeaf)
    }

    private fun assertNoMergeAutostash(repository: File) {
        git.runFailing(repository, "rev-parse", "-q", "--verify", "MERGE_AUTOSTASH")
    }

    private fun gitDir(repository: File) = File(repository, ".git").path

    private fun status(repository: File) =
        git.run(repository, "status", "--porcelain").lines().filter { it.isNotBlank() }

    private fun stashes(repository: File) =
        git.run(repository, "stash", "list", "--format=%gs").lines().filter { it.isNotBlank() }

    private fun notices() = repositoryState.autostashNotices.value.map { it.copy(date = 0) }
}
