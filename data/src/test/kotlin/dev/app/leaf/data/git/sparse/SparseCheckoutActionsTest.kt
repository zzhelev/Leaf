// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.sparse

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.branches.CheckoutBranchGitAction
import dev.app.leaf.data.git.branches.CheckoutRemoteBranchGitAction
import dev.app.leaf.data.git.branches.CreateBranchGitAction
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.isolatedGitCli
import dev.app.leaf.data.git.log.CheckoutCommitGitAction
import dev.app.leaf.data.git.log.ResetToCommitGitAction
import dev.app.leaf.data.git.repository.ResetRepositoryStateGitAction
import dev.app.leaf.data.git.stash.ApplyStashGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.stash.PopStashGitAction
import dev.app.leaf.data.git.stash.StashChangesGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.StageByDirectoryGitAction
import dev.app.leaf.data.git.workspace.UnstageAllGitAction
import dev.app.leaf.data.git.workspace.UnstageByDirectoryGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.CreateBranchError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.StashChangesError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.usecases.ResetType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What git keeps of a sparse checkout (upstream issue 336): the files outside the patterns have no file on disk and git's
 * skip-worktree bit in the index. JGit writes them when it checks out, resets or stashes, and clears the bit, which
 * widens the user's sparse set without a word, and stages the deletion of those under a folder that it adds or resets.
 * Each action that did runs git there. Every test makes two repositories alike, runs Leaf's action in one and what git
 * does in the other, and compares them: the branch, the files on disk, the skip-worktree bits, the index and the status.
 *
 * The repository: `keep/` and the folder `mixed/a` are in the sparse set, `skip/` and `mixed/b` are not. Branch `feat`
 * has two commits on main: the first changes a file in `keep/`, changes one in `skip/`, adds one there and deletes one;
 * the second changes files in `mixed/`, `mixed/a` and `mixed/b`.
 */
@DisabledOnOs(OS.WINDOWS)
class SparseCheckoutActionsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit = testJGit()
    private val gitCli by lazy { isolatedGitCli(globalConfig) }
    private val sparse by lazy { testSparseCheckoutGit(jgit, gitCli) }
    private val partialClone by lazy { testPartialCloneFetch(jgit) }
    private var repositories = 0

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        // What git needs to commit and to stash, and where the repositories start
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n[init]\n\tdefaultBranch = main\n")
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    // The checkouts

    @Test
    fun `checks out a local branch and leaves the skipped files skipped`(): Unit = runBlocking {
        val (leaf, plain) = twins()

        val result = CheckoutBranchGitAction(jgit, partialClone, sparse)(gitDir(leaf), localBranch(leaf, "feat"))
        git.run(plain, "checkout", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "feat")
    }

    @Test
    fun `checks out a remote branch as a new local one that tracks it`(): Unit = runBlocking {
        val (leaf, plain) = twins { it.withOrigin() }

        val result = CheckoutBranchGitAction(jgit, partialClone, sparse)(gitDir(leaf), remoteBranch(leaf, "origin/feat"))
        // git makes the branch that tracks origin/feat from its name
        git.run(plain, "checkout", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "feat")
        assertEquals("origin", git.run(leaf, "config", "branch.feat.remote").trim())
    }

    @Test
    fun `refuses a checkout that would overwrite changes, as git does, and changes nothing`(): Unit = runBlocking {
        val (leaf, plain) = twins { File(it, "keep/f.txt").writeText("my change\n") }
        val before = stateOf(leaf)

        val result = CheckoutBranchGitAction(jgit, partialClone, sparse)(gitDir(leaf), localBranch(leaf, "feat"))
        git.runFailing(plain, "checkout", "feat")

        val error = (result as Either.Err).error
        assertInstanceOf(GitCliError.CommandFailed::class.java, error)
        assertTrue((error as GitCliError.CommandFailed).stderr.contains("would be overwritten"), error.stderr)
        assertEquals(before, stateOf(leaf))
        assertEquals(stateOf(plain), stateOf(leaf))
    }

    @Test
    fun `checks out a branch in a linked worktree of a sparse checkout, which is sparse as well`(): Unit = runBlocking {
        val (leaf, plain) = twins { it.withLinkedWorktree() }
        val leafWorktree = linkedWorktree(leaf)
        val plainWorktree = linkedWorktree(plain)
        // git copied the main worktree's sparse set into the linked one's own config
        assertEquals(skipped, skipWorktreeOf(leafWorktree))

        val result = CheckoutBranchGitAction(jgit, partialClone, sparse)(gitDir(leafWorktree), localBranch(leaf, "feat"))
        git.run(plainWorktree, "checkout", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leafWorktree, plainWorktree, branch = "feat")
    }

    @Test
    fun `checks out the branch of a remote branch that moved ahead, and fast-forwards it`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            it.withOrigin()
            // The local feat is a commit behind origin/feat
            git.run(it, "branch", "--force", "feat", "origin/feat~1")
        }

        val result = CheckoutRemoteBranchGitAction(jgit, partialClone, sparse)(
            gitDir(leaf),
            remoteBranch(leaf, "origin/feat"),
            fastForward = true,
        )
        git.run(plain, "checkout", "feat")
        git.run(plain, "merge", "--ff-only", "origin/feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "feat", file = "mixed/top.txt" to "top 2")
    }

    @Test
    fun `creates the local branch of a remote branch, and fast-forwards the current one`(): Unit = runBlocking {
        val (leaf, plain) = twins { it.withOrigin() }
        val action = CheckoutRemoteBranchGitAction(jgit, partialClone, sparse)

        val created = action(gitDir(leaf), remoteBranch(leaf, "origin/feat"), fastForward = false)
        git.run(plain, "checkout", "feat")
        assertEquals(Either.Ok(Unit), created)
        assertKeptSkipped(leaf, plain, branch = "feat")

        // The current branch is behind its remote branch
        listOf(leaf, plain).forEach {
            git.run(it, "switch", "main")
            git.run(it, "push", "origin", "feat:main", "--force")
            git.run(it, "fetch", "origin")
        }

        val fastForwarded = action(gitDir(leaf), remoteBranch(leaf, "origin/main"), fastForward = true)
        git.run(plain, "merge", "--ff-only", "origin/main")

        assertEquals(Either.Ok(Unit), fastForwarded)
        assertKeptSkipped(leaf, plain, branch = "main")
    }

    @Test
    fun `tracks the remote branch that it creates, whatever branch autoSetupMerge says`(): Unit = runBlocking {
        val (leaf, plain) = twins { it.withOrigin() }
        git.run(leaf, "config", "--global", "branch.autoSetupMerge", "false")

        val result = CheckoutRemoteBranchGitAction(jgit, partialClone, sparse)(
            gitDir(leaf),
            remoteBranch(leaf, "origin/feat"),
            fastForward = false,
        )
        git.run(plain, "checkout", "--track", "-b", "feat", "origin/feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "feat")
        assertEquals("origin", git.run(leaf, "config", "branch.feat.remote").trim())
    }

    @Test
    fun `has nothing to fast-forward when the current branch is ahead of its remote branch`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            it.withOrigin()
            File(it, "keep/other.txt").writeText("other\n")
            git.run(it, "add", ".")
            git.run(it, "commit", "-m", "Ahead of origin/main")
        }
        val before = stateOf(leaf)

        val result = CheckoutRemoteBranchGitAction(jgit, partialClone, sparse)(
            gitDir(leaf),
            remoteBranch(leaf, "origin/main"),
            fastForward = true,
        )

        assertEquals(Either.Ok(Unit), result)
        assertEquals(before, stateOf(leaf))
        assertEquals(stateOf(plain), stateOf(leaf))
    }

    @Test
    fun `says that the current branch can't fast-forward when it diverged, and changes nothing`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            it.withOrigin()
            File(it, "keep/other.txt").writeText("other\n")
            git.run(it, "add", ".")
            git.run(it, "commit", "-m", "Diverge")
            git.run(it, "push", "origin", "origin/feat:main", "--force")
            git.run(it, "fetch", "origin")
        }
        val before = stateOf(leaf)

        val result = CheckoutRemoteBranchGitAction(jgit, partialClone, sparse)(
            gitDir(leaf),
            remoteBranch(leaf, "origin/main"),
            fastForward = true,
        )

        assertEquals(Either.Err(CheckoutBranchError.CannotFastForward("main", "origin/main")), result)
        assertEquals(before, stateOf(leaf))
        assertEquals(stateOf(plain), stateOf(leaf))
    }

    @Test
    fun `creates a branch at a commit`(): Unit = runBlocking {
        val (leaf, plain) = twins()

        val result = CreateBranchGitAction(jgit, sparse)(gitDir(leaf), "topic", commit(leaf, "feat"))
        git.run(plain, "checkout", "-b", "topic", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "topic")
    }

    @Test
    fun `refuses a branch name that is taken or not allowed, as without a sparse checkout`(): Unit = runBlocking {
        val (leaf, _) = twins()
        val action = CreateBranchGitAction(jgit, sparse)
        val before = stateOf(leaf)

        assertEquals(Either.Err(CreateBranchError.BranchAlreadyExists("feat")), action(gitDir(leaf), "feat", null))
        assertEquals(Either.Err(CreateBranchError.NameNotAllowed("a..b")), action(gitDir(leaf), "a..b", null))
        assertEquals(before, stateOf(leaf))
    }

    @Test
    fun `checks out a commit`(): Unit = runBlocking {
        val (leaf, plain) = twins()

        val result = CheckoutCommitGitAction(jgit, partialClone, sparse)(gitDir(leaf), commit(leaf, "feat"))
        git.run(plain, "checkout", "feat")
        git.run(plain, "checkout", "--detach")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "")
    }

    // The resets

    @Test
    fun `resets hard to a commit`(): Unit = runBlocking {
        val (leaf, plain) = twins { File(it, "keep/f.txt").writeText("my change\n") }

        val result = ResetToCommitGitAction(jgit, partialClone, sparse)(gitDir(leaf), commit(leaf, "feat"), ResetType.HARD)
        git.run(plain, "reset", "--hard", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main")
    }

    @Test
    fun `resets mixed to a commit, which doesn't make the skipped files look deleted`(): Unit = runBlocking {
        val (leaf, plain) = twins()

        val result = ResetToCommitGitAction(jgit, partialClone, sparse)(gitDir(leaf), commit(leaf, "feat"), ResetType.MIXED)
        git.run(plain, "reset", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertEquals(stateOf(plain), stateOf(leaf))
        // The files that feat adds under skip/ are skipped, so git says nothing of them, nor does Leaf
        assertFalse(git.run(leaf, "status", "--short").contains("skip/"))
        assertTrue(skipWorktreeOf(leaf).isNotEmpty())
    }

    @Test
    fun `resets soft to a commit, which leaves the index alone`(): Unit = runBlocking {
        val (leaf, plain) = twins()

        val result = ResetToCommitGitAction(jgit, partialClone, sparse)(gitDir(leaf), commit(leaf, "feat"), ResetType.SOFT)
        git.run(plain, "reset", "--soft", "feat")

        assertEquals(Either.Ok(Unit), result)
        assertEquals(stateOf(plain), stateOf(leaf))
        assertEquals(skipped, skipWorktreeOf(leaf))
    }

    @Test
    fun `resets the state that a merge left, which is how aborting it works`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            File(it, "keep/f.txt").writeText("main's change\n")
            git.run(it, "commit", "--all", "-m", "Change keep")
            // feat changes the same file: a conflict in a kept file, while it also changes skipped ones
            git.runFailing(it, "merge", "feat~1")
        }
        assertTrue(git.run(leaf, "status", "--short").contains("UU keep/f.txt"))

        val result = ResetRepositoryStateGitAction(jgit, sparse)(gitDir(leaf))
        git.run(plain, "merge", "--abort")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "main's change")
        assertEquals(skipped, skipWorktreeOf(leaf))
    }

    // The stashes

    @Test
    fun `stashes the changes and keeps the skipped files skipped`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            File(it, "keep/f.txt").writeText("my change\n")
            File(it, "keep/untracked.txt").writeText("new\n")
        }

        val result = StashChangesGitAction(jgit, GitIdentities(gitCli), sparse)(gitDir(leaf), "wip")
        git.run(plain, "stash", "push", "--include-untracked", "--message=wip")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "keep 1")
        assertEquals(stashes(plain), stashes(leaf))
        assertEquals(listOf("On main: wip"), stashes(leaf).map { it.message })
        assertEquals(setOf("M\tkeep/f.txt", "A\tkeep/untracked.txt"), stashChanges(leaf))
    }

    @Test
    fun `stashes without a message as git does`(): Unit = runBlocking {
        val (leaf, plain) = twins { File(it, "keep/f.txt").writeText("my change\n") }

        val result = StashChangesGitAction(jgit, GitIdentities(gitCli), sparse)(gitDir(leaf), null)
        git.run(plain, "stash", "push", "--include-untracked")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "keep 1")
        assertTrue(stashes(leaf).single().message.startsWith("WIP on main: "), stashes(leaf).toString())
        assertEquals(stashes(plain).map { it.message.substringBefore(":") }, stashes(leaf).map { it.message.substringBefore(":") })
    }

    @Test
    fun `says that there is nothing to stash`(): Unit = runBlocking {
        val (leaf, _) = twins()
        val before = stateOf(leaf)

        val result = StashChangesGitAction(jgit, GitIdentities(gitCli), sparse)(gitDir(leaf), "wip")

        assertEquals(Either.Err(StashChangesError.NoDataToStash), result)
        assertEquals(before, stateOf(leaf))
        assertEquals(emptyList<Stash>(), stashes(leaf))
    }

    @Test
    fun `applies a stash with its staged changes, which keeps the skipped files skipped`(): Unit = runBlocking {
        val (leaf, plain) = twins { stashWithStagedChange(it) }

        val result = ApplyStashGitAction(jgit, sparse)(gitDir(leaf), commit(leaf, "stash@{0}"))
        git.run(plain, "stash", "apply", "--index")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "edited again")
        // Staged, and changed again after
        assertEquals("MM keep/f.txt", git.run(leaf, "status", "--short").trim())
        assertEquals(1, stashes(leaf).size)
    }

    @Test
    fun `pops a stash, which applies it and drops it`(): Unit = runBlocking {
        val (leaf, plain) = twins { stashWithStagedChange(it) }
        val pop = PopStashGitAction(ApplyStashGitAction(jgit, sparse), DeleteStashGitAction(jgit), jgit)

        val result = pop(gitDir(leaf), commit(leaf, "stash@{0}"))
        git.run(plain, "stash", "pop", "--index")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "edited again")
        assertEquals(emptyList<Stash>(), stashes(leaf))
    }

    // The index

    @Test
    fun `unstages everything without making the skipped files look deleted`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            File(it, "keep/f.txt").writeText("my change\n")
            File(it, "mixed/top.txt").writeText("my change\n")
            git.run(it, "add", "--all")
        }

        val result = UnstageAllGitAction(jgit, sparse)(gitDir(leaf), null)
        git.run(plain, "reset")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "my change")
        assertEquals(" M keep/f.txt\n M mixed/top.txt", git.run(leaf, "status", "--short").trimEnd())
    }

    @Test
    fun `unstages everything for an empty list too, which JGit takes for the whole index`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            File(it, "keep/f.txt").writeText("my change\n")
            git.run(it, "add", "--all")
        }

        val result = UnstageAllGitAction(jgit, sparse)(gitDir(leaf), emptyList())
        git.run(plain, "reset")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "keep/f.txt" to "my change")
        assertEquals(" M keep/f.txt", git.run(leaf, "status", "--short").trimEnd())
    }

    @Test
    fun `unstages a folder that a sparse checkout partly skips`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            File(it, "mixed/top.txt").writeText("my change\n")
            File(it, "keep/f.txt").writeText("my change\n")
            git.run(it, "add", "--all")
        }

        val result = UnstageByDirectoryGitAction(jgit, sparse)(gitDir(leaf), "mixed")
        git.run(plain, "reset", "--", "mixed")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "mixed/top.txt" to "my change")
        assertEquals("M  keep/f.txt\n M mixed/top.txt", git.run(leaf, "status", "--short").trimEnd())
    }

    @Test
    fun `stages a folder that a sparse checkout partly skips, without staging the deletion of the skipped files`(): Unit = runBlocking {
        val (leaf, plain) = twins {
            File(it, "mixed/top.txt").writeText("my change\n")
            File(it, "keep/f.txt").writeText("my change\n")
        }

        val result = StageByDirectoryGitAction(jgit, sparse)(gitDir(leaf), "mixed")
        git.run(plain, "add", "mixed")

        assertEquals(Either.Ok(Unit), result)
        assertKeptSkipped(leaf, plain, branch = "main", file = "mixed/top.txt" to "my change")
        // Only the folder is staged
        assertEquals(" M keep/f.txt\nM  mixed/top.txt", git.run(leaf, "status", "--short").trimEnd())
    }

    // Which repositories

    @Test
    fun `finds a sparse checkout in the config of the repository or of the worktree`(): Unit = runBlocking {
        val sparseMain = sparseRepository("sparse")
        val plainMain = File(tempDir, "plain").also { git.initRepository(it) }
        val oldStyle = File(tempDir, "old-style").also { git.initRepository(it) }
        // How git wrote it before it had a config for each worktree
        git.run(oldStyle, "config", "core.sparseCheckout", "true")
        git.run(sparseMain, "worktree", "add", "../linked", "-b", "linked")
        val linked = File(tempDir, "linked")
        val unsparse = File(tempDir, "unsparse")
        git.run(sparseMain, "worktree", "add", "../unsparse", "-b", "unsparse")
        git.run(unsparse, "sparse-checkout", "disable")

        assertTrue(usesSparseCheckout(sparseMain))
        assertTrue(usesSparseCheckout(linked))
        assertTrue(usesSparseCheckout(oldStyle))
        assertFalse(usesSparseCheckout(plainMain))
        // The main worktree's setting is its own, and so is the linked worktree's
        assertFalse(usesSparseCheckout(unsparse))
        assertTrue(usesSparseCheckout(sparseMain))
    }

    @Test
    fun `leaves a repository that isn't sparse to JGit, and so does a missing git`(): Unit = runBlocking {
        val plainRepository = File(tempDir, "plain").also { git.initRepository(it) }
        val sparseMain = sparseRepository("sparse")

        Git.open(plainRepository).use { assertFalse(sparse.applies(it.repository)) }
        Git.open(sparseMain).use { assertTrue(sparse.applies(it.repository)) }

        val withoutGit = testSparseCheckoutGit(jgit, gitCli, gitExecutablePath = File(tempDir, "no-git").path)
        Git.open(sparseMain).use { assertFalse(withoutGit.applies(it.repository)) }
    }

    // What the tests build

    private val skipped = listOf("mixed/b/y.txt", "skip/f.txt", "skip/g.txt")

    private data class Stash(val message: String, val changes: Set<String>)

    private data class RepositoryState(
        val branch: String,
        val files: Map<String, String>,
        val skipWorktree: List<String>,
        val index: String,
        val status: String,
        val branchConfig: List<String>,
    )

    /** Two repositories alike, [setUp] made on both: Leaf's action runs in the first, and git in the second. */
    private fun twins(setUp: (File) -> Unit = {}): Pair<File, File> {
        val leaf = sparseRepository("leaf-${repositories++}").also(setUp)
        val plain = sparseRepository("git-${repositories++}").also(setUp)

        // What the test changed is the same in both, and the sets that the checkout skips are what the tests say
        assertEquals(stateOf(plain), stateOf(leaf))

        return leaf to plain
    }

    private fun sparseRepository(name: String): File {
        val repository = File(tempDir, name).also { it.mkdirs() }

        git.run(repository, "init")
        write(repository, "keep/f.txt", "keep 1")
        write(repository, "skip/f.txt", "skip 1")
        write(repository, "skip/g.txt", "g 1")
        write(repository, "mixed/top.txt", "top 1")
        write(repository, "mixed/a/x.txt", "x 1")
        write(repository, "mixed/b/y.txt", "y 1")
        write(repository, "root.txt", "root")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")

        git.run(repository, "switch", "--create", "feat")
        write(repository, "keep/f.txt", "keep 2")
        write(repository, "skip/f.txt", "skip 2")
        write(repository, "skip/new.txt", "new")
        File(repository, "skip/g.txt").delete()
        git.run(repository, "add", "--all")
        git.run(repository, "commit", "-m", "First on feat")
        write(repository, "mixed/top.txt", "top 2")
        write(repository, "mixed/a/x.txt", "x 2")
        write(repository, "mixed/b/y.txt", "y 2")
        git.run(repository, "commit", "--all", "-m", "Second on feat")
        git.run(repository, "switch", "main")

        git.run(repository, "sparse-checkout", "set", "keep", "mixed/a")

        return repository
    }

    private fun write(repository: File, path: String, text: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeText("$text\n")
    }

    /** A remote that has main and feat, fetched: `origin/main` and `origin/feat`. */
    private fun File.withOrigin() {
        val origin = File(tempDir, "origin-$name.git")

        git.run(tempDir, "init", "--bare", origin.path)
        git.run(this, "remote", "add", "origin", origin.path)
        git.run(this, "push", "origin", "main", "feat")
        git.run(this, "fetch", "origin")
        // The local feat is gone, as when the branch was only ever on the remote
        git.run(this, "branch", "--delete", "--force", "feat")
    }

    private fun File.withLinkedWorktree() {
        git.run(this, "worktree", "add", "../$name-linked", "-b", "linked")
    }

    private fun linkedWorktree(repository: File) = File(tempDir, "${repository.name}-linked")

    private fun stashWithStagedChange(repository: File) {
        write(repository, "keep/f.txt", "staged")
        git.run(repository, "add", "keep/f.txt")
        write(repository, "keep/f.txt", "edited again")
        git.run(repository, "stash", "push", "--include-untracked", "--message=wip")
    }

    // What the tests read

    private fun gitDir(worktree: File) = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()

    private fun rev(repository: File, rev: String) = git.run(repository, "rev-parse", rev).trim()

    private fun localBranch(repository: File, name: String) =
        Branch(rev(repository, "refs/heads/$name"), "refs/heads/$name", isLocal = true)

    private fun remoteBranch(repository: File, name: String) =
        Branch(rev(repository, "refs/remotes/$name"), "refs/remotes/$name", isLocal = false)

    private fun commit(repository: File, rev: String): Commit = Git.open(repository).use { jgit ->
        RevWalk(jgit.repository).use { walk ->
            JGitCommitMapper(JGitIdentityMapper()).toDomain(walk.parseCommit(jgit.repository.resolve(rev)))
        }
    }

    private fun skipWorktreeOf(repository: File) = git.run(repository, "ls-files", "-t")
        .lines()
        .filter { it.startsWith("S ") }
        .map { it.removePrefix("S ") }
        .sorted()

    private fun stateOf(repository: File) = RepositoryState(
        branch = git.run(repository, "branch", "--show-current").trim(),
        files = repository.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile && it.name != ".git" }
            .associate { it.relativeTo(repository).path to it.readText().trim() }
            .toSortedMap(),
        skipWorktree = skipWorktreeOf(repository),
        index = git.run(repository, "ls-files", "--stage"),
        status = git.run(repository, "status", "--short"),
        branchConfig = git.run(repository, "config", "--local", "--list").lines().filter { it.startsWith("branch.") }.sorted(),
    )

    private fun stashes(repository: File): List<Stash> =
        git.run(repository, "stash", "list", "--format=%gd%x09%gs").lines().filter { it.isNotBlank() }.map { line ->
            val (ref, message) = line.split('\t', limit = 2)
            Stash(message, stashChangesOf(repository, ref))
        }

    private fun stashChanges(repository: File) = stashChangesOf(repository, "stash@{0}")

    private fun stashChangesOf(repository: File, ref: String): Set<String> =
        git.run(repository, "stash", "show", "--include-untracked", "--name-status", ref).lines().filter { it.isNotBlank() }.toSet()

    private fun usesSparseCheckout(worktree: File) = Git.open(worktree).use { it.repository.usesSparseCheckout() }

    /**
     * What [leaf] must be after a checkout, reset or stash: as [plain], which git did, whose skipped files are still not
     * on disk and still skipped. [file] is a kept file that has the version the action brought, or changed.
     */
    private fun assertKeptSkipped(leaf: File, plain: File, branch: String, file: Pair<String, String> = "keep/f.txt" to "keep 2") {
        val state = stateOf(leaf)

        assertEquals(stateOf(plain), state)
        assertEquals(branch, state.branch)
        assertEquals(file.second, state.files[file.first], state.toString())
        // The point: none of the skipped files is on disk, and each keeps its bit
        for (path in listOf("skip/f.txt", "skip/g.txt", "skip/new.txt", "mixed/b/y.txt")) {
            assertFalse(File(leaf, path).exists(), "$path was written")
        }
        assertEquals(skipWorktreeOf(plain), state.skipWorktree)
        assertTrue(state.skipWorktree.isNotEmpty())
    }
}
