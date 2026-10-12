// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSides
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which branch or commit is ours and which is theirs, for the banner, the dialogs and the diff pane. */
class GetConflictSidesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val getConflictSides = GetConflictSidesGitAction(jgit)

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `in a merge, ours is the branch checked out and theirs the branch merged`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")

        assertEquals(
            ConflictSides(ConflictOperation.MERGE, commit("main", "HEAD"), commit("feature", "feature")),
            sides(),
        )
    }

    @Test
    fun `a merged commit that no branch points at is named by git's merge message, or not at all`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "notes.txt")
            git.run(repository, "merge", "--abort")
            git.run(repository, "update-ref", "refs/remotes/origin/feature", "feature")
            git.run(repository, "branch", "-D", "feature")

            git.runFailing(repository, "merge", "origin/feature")
            assertEquals(commit("origin/feature", "origin/feature"), sides().theirs)

            git.run(repository, "merge", "--abort")
            val featureCommit = git.run(repository, "rev-parse", "origin/feature").trim()
            git.run(repository, "update-ref", "-d", "refs/remotes/origin/feature")
            git.runFailing(repository, "merge", featureCommit)

            assertEquals(ConflictCommit(null, featureCommit.take(7), "Feature"), sides().theirs)
        }

    @Test
    fun `a merged branch is named by a local branch before a remote one, and by the merge message once deleted`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "notes.txt")
            git.run(repository, "update-ref", "refs/remotes/origin/feature", "feature")

            assertEquals("feature", sides().theirs?.branch)

            git.run(repository, "update-ref", "-d", "refs/remotes/origin/feature")
            git.run(repository, "branch", "-D", "feature")

            assertEquals("feature", sides().theirs?.branch)
        }

    @Test
    fun `in a rebase, ours is the branch rebased onto and theirs the commit replayed, from the branch rebased`(): Unit =
        runBlocking {
            git.mergeWithConflictsIn(repository, "notes.txt")
            git.run(repository, "merge", "--abort")
            git.run(repository, "switch", "feature")
            git.runFailing(repository, "rebase", "main")

            assertEquals(
                ConflictSides(ConflictOperation.REBASE, commit("main", "main"), commit("feature", "feature")),
                sides(),
            )
        }

    @Test
    fun `a rebase that Leaf ran with JGit has the same sides`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        git.run(repository, "merge", "--abort")
        git.run(repository, "switch", "feature")

        Git.open(repository).use { assertEquals(RebaseResult.Status.STOPPED, it.rebase().setUpstream("main").call().status) }

        assertEquals(
            ConflictSides(ConflictOperation.REBASE, commit("main", "main"), commit("feature", "feature")),
            sides(),
        )
    }

    @Test
    fun `in a cherry-pick, theirs is the commit picked`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        git.run(repository, "merge", "--abort")
        git.runFailing(repository, "cherry-pick", "feature")

        assertEquals(
            ConflictSides(ConflictOperation.CHERRY_PICK, commit("main", "HEAD"), commit(null, "feature")),
            sides(),
        )
    }

    @Test
    fun `in a revert, theirs is the commit reverted`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "notes.txt")
        git.run(repository, "merge", "--abort")
        File(repository, "notes.txt").writeText("main again\n")
        git.run(repository, "commit", "-am", "Main again")
        git.runFailing(repository, "revert", "--no-edit", "HEAD~1")

        assertEquals(
            ConflictSides(ConflictOperation.REVERT, commit("main", "HEAD"), commit(null, "HEAD~1")),
            sides(),
        )
    }

    @Test
    fun `a stash that didn't apply cleanly has no theirs`(): Unit = runBlocking {
        File(repository, "notes.txt").writeText("base\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        File(repository, "notes.txt").writeText("stashed\n")
        git.run(repository, "stash")
        File(repository, "notes.txt").writeText("committed\n")
        git.run(repository, "commit", "-am", "Committed")
        git.runFailing(repository, "stash", "apply")

        assertEquals(ConflictSides(ConflictOperation.OTHER, commit("main", "HEAD"), null), sides())
    }

    @Test
    fun `in a linked worktree, the sides are the worktree's`(): Unit = runBlocking {
        git.run(repository, "branch", "work")
        val worktree = File(tempDir, "work-tree")
        git.run(repository, "worktree", "add", worktree.path, "work")
        git.mergeWithConflictsIn(worktree, "notes.txt")

        val sides = getConflictSides(File(repository, ".git/worktrees/work-tree").path)

        assertEquals(
            Either.Ok(ConflictSides(ConflictOperation.MERGE, commit("work", "work", worktree), commit("feature", "feature"))),
            sides,
        )
    }

    private suspend fun sides(): ConflictSides = (getConflictSides(File(repository, ".git").path) as Either.Ok).value

    /** [revision] as a side, with [branch]. */
    private fun commit(branch: String?, revision: String, workTree: File = repository): ConflictCommit {
        val (hash, subject) = git.run(workTree, "log", "-1", "--format=%H%n%s", revision).trim().lines()

        return ConflictCommit(branch, hash.take(7), subject)
    }
}
