// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.WorktreeBranchUse
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeleteBranchesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = DeleteBranchesGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `deletes merged and unmerged branches in one go and reports each with its tip`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "merged")
        createBranchWithCommits(repository, "feature", commits = 2)
        createBranchWithCommits(repository, "keep", commits = 1)

        val targets = listOf(target(repository, "merged"), target(repository, "feature"))
        val result = delete(repository, targets)

        assertEquals(
            BranchesDeletion(targets.map { BranchDeletionOutcome.Deleted(it) }),
            result,
        )
        assertEquals(listOf("keep", "main"), branches(repository))
        assertEquals("merged feature", result.deleted.joinToString(" ") { it.simpleName })
        assertEquals(
            targets.joinToString("\n") { "${it.simpleName} ${it.tip}" },
            result.deletedList(),
            "One line per branch with its whole tip, which git branch <name> <tip> brings back",
        )
    }

    @Test
    fun `deletes branches with a slash and unusual characters in their names`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val names = listOf(
            "claude/eager-ride-4f2a",
            "feature/deep/er/name",
            "fix#12",
            "user@host",
            "a+b",
            "100%",
            "it's",
            "ünï/çödé",
            "tilde-ish.lock-not",
            "semi;colon",
            "dollar\$name",
            "ver1.0",
        )

        for (name in names) git.run(repository, "branch", name)

        val kept = "keep/me"
        git.run(repository, "branch", kept)

        val result = delete(repository, names.map { target(repository, it) })

        assertEquals(names.size, result.deleted.size, "kept: ${result.kept}")
        assertEquals(listOf(kept, "main"), branches(repository))
    }

    @Test
    fun `deletes a branch whose name git's own commands would take for an option`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "update-ref", "refs/heads/-odd", "HEAD")

        val result = delete(repository, listOf(target(repository, "-odd")))

        assertEquals(1, result.deleted.size, "kept: ${result.kept}")
        assertEquals(listOf("main"), branches(repository))
    }

    @Test
    fun `keeps the branch that this worktree has checked out and deletes the others`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "other")
        val main = target(repository, "main")
        val other = target(repository, "other")

        val result = delete(repository, listOf(main, other))

        assertEquals(
            listOf(
                BranchDeletionOutcome.Kept(
                    main,
                    BranchKept.UsedByWorktree(repository.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = true),
                ),
                BranchDeletionOutcome.Deleted(other),
            ),
            result.outcomes.map { it.withCanonicalPath() },
        )
        assertEquals(listOf("main"), branches(repository))
    }

    @Test
    fun `keeps a branch that a linked worktree has checked out, even without its folder`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val present = File(tempDir, "wt-present")
        val missing = File(tempDir, "wt-missing")
        git.run(repository, "worktree", "add", "-b", "agent-present", present.path)
        git.run(repository, "worktree", "add", "-b", "agent-missing", missing.path)
        git.run(repository, "branch", "other")
        missing.deleteRecursively()
        val agentPresent = target(repository, "agent-present")
        val agentMissing = target(repository, "agent-missing")
        val other = target(repository, "other")

        val result = delete(repository, listOf(agentPresent, agentMissing, other))

        assertEquals(
            listOf(
                BranchDeletionOutcome.Kept(
                    agentPresent,
                    BranchKept.UsedByWorktree(present.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = false),
                ),
                BranchDeletionOutcome.Kept(
                    agentMissing,
                    BranchKept.UsedByWorktree(missing.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = false),
                ),
                BranchDeletionOutcome.Deleted(other),
            ),
            result.outcomes.map { it.withCanonicalPath() },
        )
        assertEquals(listOf("agent-missing", "agent-present", "main"), branches(repository))
    }

    @Test
    fun `from a linked worktree its own branch is here and the main worktree's is not`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)

        val outcomes = deleteIn(
            File(repository, ".git/worktrees/wt-agent").path,
            listOf(target(repository, "agent"), target(repository, "main")),
        ).outcomes

        assertEquals(
            listOf(
                BranchKept.UsedByWorktree(worktree.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = true),
                BranchKept.UsedByWorktree(repository.canonicalPath, WorktreeBranchUse.CheckedOut, isHere = false),
            ),
            outcomes.map { (it.withCanonicalPath() as BranchDeletionOutcome.Kept).reason },
        )
    }

    @Test
    fun `keeps a branch that its worktree is rebasing and one that another is bisecting from`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        File(repository, "README.md").writeText("Main")
        git.run(repository, "commit", "-am", "Main")
        git.run(repository, "switch", "-c", "feature", "HEAD~1")
        File(repository, "README.md").writeText("Feature")
        git.run(repository, "commit", "-am", "Feature")
        git.runFailing(repository, "rebase", "main")
        val bisect = File(tempDir, "wt-bisect")
        git.run(repository, "worktree", "add", "-b", "bisected", bisect.path, "main")
        repeat(3) { git.run(bisect, "commit", "--allow-empty", "-m", "Bisect $it") }
        git.run(bisect, "bisect", "start", "HEAD", "HEAD~3")

        val outcomes = delete(repository, listOf(target(repository, "feature"), target(repository, "bisected"))).outcomes

        assertEquals(
            listOf(
                BranchKept.UsedByWorktree(repository.canonicalPath, WorktreeBranchUse.Rebasing, isHere = true),
                BranchKept.UsedByWorktree(bisect.canonicalPath, WorktreeBranchUse.Bisecting, isHere = false),
            ),
            outcomes.map { (it.withCanonicalPath() as BranchDeletionOutcome.Kept).reason },
        )
        assertEquals(listOf("bisected", "feature", "main"), branches(repository))
    }

    @Test
    fun `deletes a branch that its worktree has left`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val worktree = File(tempDir, "wt-agent")
        git.run(repository, "worktree", "add", "-b", "agent", worktree.path)
        git.run(worktree, "switch", "--detach")

        val result = delete(repository, listOf(target(repository, "agent")))

        assertEquals(1, result.deleted.size)
        assertEquals(listOf("main"), branches(repository))
    }

    @Test
    fun `leaves a branch that was deleted since the dialog read it, and deletes the rest`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "gone")
        git.run(repository, "branch", "stays")
        val targets = listOf(target(repository, "gone"), target(repository, "stays"))
        git.run(repository, "branch", "-D", "gone")

        val result = delete(repository, targets)

        assertEquals(
            listOf(
                BranchDeletionOutcome.Kept(targets[0], BranchKept.NoLongerExists),
                BranchDeletionOutcome.Deleted(targets[1]),
            ),
            result.outcomes,
        )
        assertEquals(listOf("main"), branches(repository))
    }

    @Test
    fun `never deletes a branch that moved since the dialog read it`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 1)
        git.run(repository, "branch", "stays")
        val targets = listOf(target(repository, "feature"), target(repository, "stays"))

        // An agent commits to the branch after the dialog read its tip
        val worktree = File(tempDir, "wt-feature")
        git.run(repository, "worktree", "add", worktree.path, "feature")
        git.run(worktree, "commit", "--allow-empty", "-m", "More work")
        val movedTo = git.run(repository, "rev-parse", "refs/heads/feature").trim()
        git.run(repository, "worktree", "remove", worktree.path)

        val result = delete(repository, targets)

        assertEquals(
            listOf(
                BranchDeletionOutcome.Kept(targets[0], BranchKept.Moved(movedTo)),
                BranchDeletionOutcome.Deleted(targets[1]),
            ),
            result.outcomes,
        )
        assertEquals(movedTo, git.run(repository, "rev-parse", "refs/heads/feature").trim(), "The new commit is still there")
        assertEquals(listOf("feature", "main"), branches(repository))
    }

    @Test
    fun `a branch recreated at another commit is a different branch`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        createBranchWithCommits(repository, "feature", commits = 1)
        val targets = listOf(target(repository, "feature"))
        git.run(repository, "branch", "-D", "feature")
        createBranchWithCommits(repository, "feature", commits = 3)
        val now = git.run(repository, "rev-parse", "refs/heads/feature").trim()

        val result = delete(repository, targets)

        assertEquals(listOf(BranchDeletionOutcome.Kept(targets[0], BranchKept.Moved(now))), result.outcomes)
        assertEquals(listOf("feature", "main"), branches(repository))
    }

    @Test
    fun `removes the reflog and the config section as git branch -D does`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val twin = git.initRepository(File(tempDir, "twin"))

        for (repo in listOf(repository, twin)) {
            git.run(repo, "switch", "-c", "feature")
            git.run(repo, "commit", "--allow-empty", "-m", "Feature")
            git.run(repo, "config", "branch.feature.remote", "origin")
            git.run(repo, "config", "branch.feature.merge", "refs/heads/feature")
            git.run(repo, "config", "branch.feature.description", "Some work")
            git.run(repo, "config", "branch.other.remote", "origin")
            git.run(repo, "switch", "main")
        }

        assertTrue(File(repository, ".git/logs/refs/heads/feature").exists())

        delete(repository, listOf(target(repository, "feature")))
        git.run(twin, "branch", "-D", "feature")

        assertFalse(File(repository, ".git/logs/refs/heads/feature").exists())
        assertEquals(
            git.run(twin, "config", "--local", "--list").lines().filter { it.startsWith("branch.") },
            git.run(repository, "config", "--local", "--list").lines().filter { it.startsWith("branch.") },
            "The same branch settings are left as git leaves",
        )
        assertEquals(listOf("branch.other.remote=origin"), git.run(repository, "config", "--local", "--list").lines().filter { it.startsWith("branch.") })
        assertEquals(git.run(twin, "for-each-ref"), git.run(repository, "for-each-ref").replace(repository.name, twin.name))
    }

    @Test
    fun `deletes branches that are only in packed-refs`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "one")
        git.run(repository, "branch", "two")
        git.run(repository, "pack-refs", "--all")
        assertFalse(File(repository, ".git/refs/heads/one").exists())

        val result = delete(repository, listOf(target(repository, "one"), target(repository, "two")))

        assertEquals(2, result.deleted.size, "kept: ${result.kept}")
        assertEquals(listOf("main"), branches(repository))
    }

    @Test
    fun `deletes a hundred and fifty branches`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        val names = (1..150).map { "claude/agent-$it" }

        git.runWithInput(repository, names.joinToString("\n") { "create refs/heads/$it HEAD" } + "\n", "update-ref", "--stdin")
        git.run(repository, "pack-refs", "--all")

        val startedAt = System.nanoTime()
        val result = delete(repository, names.map { target(repository, it) })
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0

        assertEquals(150, result.deleted.size, "kept: ${result.kept}")
        assertEquals(listOf("main"), branches(repository))
        assertTrue(seconds < 20, "Deleting 150 packed branches took $seconds s")
    }

    @Test
    fun `lists a branch given twice once`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "twice")
        val target = target(repository, "twice")

        val result = delete(repository, listOf(target, target))

        assertEquals(listOf(BranchDeletionOutcome.Deleted(target)), result.outcomes)
    }

    @Test
    fun `an empty list is nothing to do`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))

        assertEquals(BranchesDeletion(emptyList()), delete(repository, emptyList()))
        assertEquals(listOf("main"), branches(repository))
    }

    @Test
    fun `JGit refuses a delete whose expected old value is wrong, so a branch that moves in between stays`() {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "branch", "feature")
        val tip = git.run(repository, "rev-parse", "refs/heads/feature").trim()
        git.run(repository, "commit", "--allow-empty", "-m", "Moved on")
        git.run(repository, "branch", "-f", "feature", "HEAD")

        RepositoryCache.FileKey.lenient(File(repository, ".git"), FS.DETECTED).let { key ->
            key.open(true).use { jgitRepository ->
                val update = jgitRepository.updateRef("refs/heads/feature")

                update.setExpectedOldObjectId(org.eclipse.jgit.lib.ObjectId.fromString(tip))
                update.isForceUpdate = true

                assertEquals(RefUpdate.Result.LOCK_FAILURE, update.delete())
            }
        }

        assertEquals(listOf("feature", "main"), branches(repository))
    }

    private suspend fun delete(repository: File, targets: List<BranchToDelete>) =
        deleteIn(File(repository, ".git").absolutePath, targets)

    private suspend fun deleteIn(gitDir: String, targets: List<BranchToDelete>): BranchesDeletion =
        when (val result = action(gitDir, targets)) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The action failed: ${result.error}")
        }

    /** The outcome with a worktree's folder as a real path: git writes it as the user gave it, and macOS has /private. */
    private fun BranchDeletionOutcome.withCanonicalPath(): BranchDeletionOutcome {
        val kept = this as? BranchDeletionOutcome.Kept ?: return this
        val reason = kept.reason as? BranchKept.UsedByWorktree ?: return this

        return kept.copy(reason = reason.copy(path = File(reason.path).canonicalPath))
    }

    /** The branch as the dialog reads it: its name and the commit it points to now. */
    private fun target(repository: File, name: String) =
        BranchToDelete("refs/heads/$name", git.run(repository, "rev-parse", "refs/heads/$name").trim())

    /** The names of the local branches that are left, sorted as for-each-ref does. */
    private fun branches(repository: File): List<String> =
        git.run(repository, "for-each-ref", "--format=%(refname:short)", "refs/heads/").lines().filter { it.isNotBlank() }

    /** Creates [name] from main with [commits] commits of its own, then checks out main again. */
    private fun createBranchWithCommits(repository: File, name: String, commits: Int) {
        git.run(repository, "switch", "-c", name)

        repeat(commits) { index ->
            git.run(repository, "commit", "--allow-empty", "-m", "$name ${index + 1}")
        }

        git.run(repository, "switch", "main")
    }
}
