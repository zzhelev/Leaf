// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetBranchComparisonGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchComparison
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.usecases.GetBranchComparisonUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.diff.DiffEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import javax.inject.Provider
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The review of a worktree's changes: when it opens and closes, what it shows, and when it reads the branches again. */
class WorktreeReviewTest {
    private val gitDir = "/dev/leaf/.git"
    private val tabScope = TabCoroutineScope()
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repositoryData = InMemoryRepositoryDataRepository()

    private val main = worktree("/dev/leaf", "refs/heads/main").copy(isMain = true, isCurrent = true)
    private val agent = worktree("/dev/leaf-agent1", "refs/heads/agent1")

    /** The comparisons that git was asked for, as base and branch. */
    private val compared = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    /** Holds back the comparisons after the first until it completes. */
    private val laterComparisons = CompletableDeferred<Unit>().apply { complete(Unit) }.let { MutableStateFlow(it) }

    private val gitAction = object : IGetBranchComparisonGitAction {
        override suspend fun invoke(repositoryPath: String, base: String, branch: String): Either<BranchComparison, GitError> {
            if (compared.isNotEmpty()) laterComparisons.value.await()

            compared += base to branch

            return Either.Ok(comparison(base, branch, subjects = List(compared.size) { "Commit ${it + 1}" }))
        }
    }

    private val selectedItem = MutableStateFlow<SelectedItem>(SelectedItem.UncommittedChanges)
    private val diffSelected = MutableStateFlow<DiffSelected?>(null)
    private val closeableViews = Collections.synchronizedList(mutableListOf<CloseableView>())

    private val review = WorktreeReviewViewModelExtender(
        getBranchComparisonUseCase = GetBranchComparisonUseCase(
            gitAction,
            UseCaseExecutor(repositoryData, InMemoryRepositoryStateRepository(), Provider { error("No refresh") }, tabScope),
        ),
        repositoryDataRepository = repositoryData,
        viewModelScope = viewModelScope,
        filesViewState = MutableStateFlow(FilesViewState()),
        selectedItem = selectedItem,
        onSelectItem = { selectedItem.value = it },
        onDiffSelected = { diffSelected.value = it },
        onViewStateChanged = {},
        addCloseableView = { closeableViews += it },
        removeCloseableView = { closeableViews -= it },
    )

    @BeforeEach
    fun openRepository(): Unit = runBlocking {
        repositoryData.setRepositorySelectionState(RepositorySelectionState.Open(gitDir))
        setBranches(agent1Hash = "a1")
        repositoryData.updateRemotes(gitDir) { Either.Ok(emptyList()) }
        repositoryData.updateWorktrees(gitDir) {
            Either.Ok(
                WorktreeList(
                    WorktreeBaseBranch(automatic = "refs/heads/main"),
                    listOf(
                        WorktreeInfo(main, status(), aheadBehindBase = null, lastCommitTime = null),
                        WorktreeInfo(agent, status(unstaged = 2, untracked = 1), aheadBehindBase = null, lastCommitTime = null),
                    ),
                )
            )
        }
    }

    @AfterEach
    fun tearDown() {
        viewModelScope.cancel()
        tabScope.cancel()
    }

    @Test
    fun `opens in place of the selection, with the comparison and the worktree's uncommitted changes`(): Unit =
        runBlocking {
            review.open(agent, "refs/heads/main")

            val state = loaded()

            assertEquals(SelectedItem.None, selectedItem.value)
            assertEquals(listOf(CloseableView.WORKTREE_REVIEW), closeableViews.toList())
            assertEquals(listOf("refs/heads/main" to "refs/heads/agent1"), compared.toList())
            assertEquals("agent1", state.branchName)
            assertEquals("main", state.baseName)
            assertEquals("leaf-agent1", state.worktreeName)
            assertEquals(3, state.uncommittedChanges)
            assertFalse(state.isTabWorktree)
            assertTrue(state.canSwitchTo)
            assertEquals(2, state.rows.size)

            val file = state.comparison!!.changes.first()
            review.onAction(WorktreeReviewAction.SelectEntry(file))

            assertEquals(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(file))), diffSelected.value)
        }

    @Test
    fun `selecting anything else closes it for good`(): Unit = runBlocking {
        review.open(agent, "refs/heads/main")
        loaded()

        selectedItem.value = SelectedItem.CommitItem(commit("Fix", "c0"), isStash = false, scrollToItem = false)

        withTimeout(5.seconds) { review.reviewState.first { it == null } }
        withTimeout(5.seconds) { while (closeableViews.isNotEmpty()) delay(10.milliseconds) }

        // Nothing selected again doesn't bring it back
        selectedItem.value = SelectedItem.None
        delay(200.milliseconds)
        assertNull(review.reviewState.value)
    }

    @Test
    fun `closing selects again what was selected before it opened`(): Unit = runBlocking {
        val before = SelectedItem.CommitItem(commit("Fix", "c0"), isStash = false, scrollToItem = false)
        selectedItem.value = before
        review.open(agent, "refs/heads/main")
        loaded()

        review.onAction(WorktreeReviewAction.Close)

        assertEquals(before, selectedItem.value)
        assertEquals(emptyList<CloseableView>(), closeableViews.toList())
        withTimeout(5.seconds) { review.reviewState.first { it == null } }
    }

    @Test
    fun `showing the uncommitted changes closes it for the status`(): Unit = runBlocking {
        selectedItem.value = SelectedItem.CommitItem(commit("Fix", "c0"), isStash = false, scrollToItem = false)
        review.open(agent, "refs/heads/main")
        loaded()

        review.onAction(WorktreeReviewAction.ShowUncommittedChanges)

        assertEquals(SelectedItem.UncommittedChanges, selectedItem.value)
    }

    @Test
    fun `reads the comparison again when the branch moves, and not on every refresh`(): Unit = runBlocking {
        review.open(agent, "refs/heads/main")
        assertEquals(listOf("Commit 1"), loaded().comparison!!.commits.map { it.shortMessage })

        // A refresh that finds the same commits
        setBranches(agent1Hash = "a1")
        delay(300.milliseconds)
        assertEquals(1, compared.size)

        // The agent committed. Meanwhile the last comparison stays
        laterComparisons.value = CompletableDeferred()
        setBranches(agent1Hash = "a2")

        val reloading = withTimeout(5.seconds) { review.reviewState.first { it != null && it.isLoading } }
        assertEquals(listOf("Commit 1"), reloading!!.comparison!!.commits.map { it.shortMessage })
        laterComparisons.value.complete(Unit)

        val moved = withTimeout(5.seconds) {
            review.reviewState.first { it != null && !it.isLoading && it.comparison!!.commits.size == 2 }
        }
        assertEquals(listOf("Commit 1", "Commit 2"), moved!!.comparison!!.commits.map { it.shortMessage })
    }

    @Test
    fun `doesn't open for a worktree on the base, or without a base`(): Unit = runBlocking {
        review.open(main, "refs/heads/main")
        review.open(agent, null)

        assertEquals(SelectedItem.UncommittedChanges, selectedItem.value)
        assertEquals(emptyList<CloseableView>(), closeableViews.toList())
        delay(200.milliseconds)
        assertNull(review.reviewState.value)
        assertEquals(emptyList<Pair<String, String>>(), compared.toList())
    }

    @Test
    fun `offers the tab's own uncommitted changes, and none for a worktree that's gone`() {
        val target = WorktreeReviewTarget(agent.path, "refs/heads/agent1", "refs/heads/main")
        val list = WorktreeList(
            WorktreeBaseBranch(automatic = "refs/heads/main"),
            listOf(WorktreeInfo(agent.copy(isCurrent = true), status(staged = 1), null, null)),
        )

        val own = worktreeReviewState(target, false, null, list, FilesViewState(), emptySet())
        val gone = worktreeReviewState(target, false, null, list.copy(worktrees = emptyList()), FilesViewState(), emptySet())

        assertEquals(1, own.uncommittedChanges)
        assertTrue(own.isTabWorktree)
        assertFalse(own.canSwitchTo)
        assertNull(gone.uncommittedChanges)
        assertEquals("leaf-agent1", gone.worktreeName)
    }

    /**
     * The review once the comparison loaded and the worktree list is in it. The state can show the comparison first, as
     * the list comes from a flow of its own, which a full test run's load can hold back.
     */
    private suspend fun loaded(): WorktreeReviewState = withTimeout(5.seconds) {
        review.reviewState.first { it != null && !it.isLoading && it.uncommittedChanges != null }!!
    }

    /** Refreshes the local branches as the tab does: Loading while git reads them, which takes a moment. */
    private suspend fun setBranches(agent1Hash: String) {
        repositoryData.updateLocalBranches(gitDir) {
            delay(50.milliseconds)

            Either.Ok(listOf(Branch("m1", "refs/heads/main", isLocal = true), Branch(agent1Hash, "refs/heads/agent1", isLocal = true)))
        }
    }

    private fun comparison(base: String, branch: String, subjects: List<String>) = BranchComparison(
        base = base,
        branch = branch,
        mergeBase = "m0",
        commits = subjects.mapIndexed { index, subject -> commit(subject, "c${index + 1}") },
        ahead = subjects.size,
        behind = 0,
        changes = listOf(addedEntry("retry.txt"), addedEntry("test/retry_test.txt")),
    )

    private fun addedEntry(path: String) = object : DiffEntry() {
        init {
            changeType = ChangeType.ADD
            oldPath = DEV_NULL
            newPath = path
        }
    }

    private fun commit(subject: String, hash: String): Commit {
        val identity = Identity("Agent", "agent@example.invalid")

        return Commit(hash, subject, identity, identity, 0, emptyList())
    }

    private fun status(staged: Int = 0, unstaged: Int = 0, untracked: Int = 0) =
        WorktreeStatus(staged, unstaged, untracked, conflicted = 0, upstream = null, upstreamAheadBehind = null)

    private fun worktree(path: String, branch: String) = Worktree(
        path = path,
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = branch,
        isMain = false,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )
}
