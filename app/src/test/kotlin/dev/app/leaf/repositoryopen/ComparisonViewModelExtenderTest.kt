// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetComparisonGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonMode.ALL
import dev.app.leaf.domain.models.ComparisonMode.SINCE_SPLIT
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.ComparisonRelation.A_IS_ANCESTOR
import dev.app.leaf.domain.models.ComparisonRelation.B_IS_ANCESTOR
import dev.app.leaf.domain.models.ComparisonRelation.DIVERGED
import dev.app.leaf.domain.models.ComparisonRelation.UNRELATED
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
import dev.app.leaf.domain.usecases.GetComparisonUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Provider
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Comparisons and the review of a worktree's changes: how they start, which side is A, which mode they show, what
 * ⌘-click, the swap, the full view and Esc do, and when they read the branches again (`docs/fork/compare.md`, 3.1 to
 * 3.9).
 */
class ComparisonViewModelExtenderTest {
    private val gitDir = "/dev/leaf/.git"
    private val tabScope = TabCoroutineScope()
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repositoryData = InMemoryRepositoryDataRepository()

    private val main = worktree("/dev/leaf", "refs/heads/main").copy(isMain = true, isCurrent = true)
    private val agent = worktree("/dev/leaf-agent1", "refs/heads/agent1")

    /** The comparisons that git was asked for: A, B and the mode. */
    private val compared = Collections.synchronizedList(mutableListOf<Triple<String, String, ComparisonMode>>())

    /** How git relates two sides, by their revisions. Diverged unless set. */
    private val relations = Collections.synchronizedMap(mutableMapOf<Pair<String, String>, ComparisonRelation>())

    /** Holds back the comparisons after the first until it completes. */
    private val laterComparisons = CompletableDeferred<Unit>().apply { complete(Unit) }.let { MutableStateFlow(it) }

    private val failNext = AtomicBoolean(false)

    private val gitAction = object : IGetComparisonGitAction {
        override suspend fun invoke(
            repositoryPath: String,
            a: String,
            b: String,
            mode: ComparisonMode,
        ): Either<Comparison, GitError> {
            if (compared.isNotEmpty()) laterComparisons.value.await()

            compared += Triple(a, b, mode)

            if (failNext.getAndSet(false)) return Either.Err(GenericError("Couldn't read $b."))

            return Either.Ok(comparison(a, b, mode, subjects = List(compared.size) { "Commit ${it + 1}" }))
        }
    }

    private val selectedItem = MutableStateFlow<SelectedItem>(SelectedItem.UncommittedChanges)
    private val diffSelected = MutableStateFlow<DiffSelected?>(null)
    private val closeableViews = Collections.synchronizedList(mutableListOf<CloseableView>())

    private val extender = ComparisonViewModelExtender(
        getComparisonUseCase = GetComparisonUseCase(
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
        addCloseableView = { view -> closeableViews.remove(view); closeableViews += view },
        removeCloseableView = { closeableViews -= it },
        selectionFor = { hash -> selected(hash) },
    )

    // Sides that the log gives: two branch tips, and two commits without a branch
    private val mainSide = PickedSide(CompareSide.Branch("refs/heads/main", "m1"), date = 200)
    private val agentSide = PickedSide(CompareSide.Branch("refs/heads/agent1", "a1"), date = 300)
    private val older = PickedSide(CompareSide.Commit("c1"), date = 100)
    private val newer = PickedSide(CompareSide.Commit("c2"), date = 400)

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

    // The review of a worktree's changes (F7)

    @Test
    fun `the review opens in place of the selection, with the comparison and the worktree's uncommitted changes`(): Unit =
        runBlocking {
            extender.openReview(agent, "refs/heads/main")

            val state = reviewLoaded()

            assertEquals(SelectedItem.None, selectedItem.value)
            assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
            assertEquals(listOf(Triple("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT)), compared.toList())
            assertEquals("agent1", state.bName)
            assertEquals("main", state.aName)
            assertEquals("leaf-agent1", state.review!!.name)
            assertEquals(3, state.review!!.uncommittedChanges)
            assertFalse(state.review!!.isTabWorktree)
            assertTrue(state.review!!.canSwitchTo)
            assertEquals(2, state.rows.size)

            val file = state.comparison!!.changes.first()
            extender.onAction(ComparisonAction.SelectEntry(file))

            assertEquals(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(file))), diffSelected.value)
        }

    @Test
    fun `the review asks what the branch would bring, even for a branch that the base already has`(): Unit =
        runBlocking {
            relations["refs/heads/main" to "refs/heads/agent1"] = B_IS_ANCESTOR

            extender.openReview(agent, "refs/heads/main")
            val state = reviewLoaded()
            delay(200.milliseconds)

            // No turning around, and no second read in the other mode
            assertEquals(listOf(Triple("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT)), compared.toList())
            assertEquals("main", state.aName)
            assertEquals(SINCE_SPLIT, state.mode)
        }

    @Test
    fun `selecting anything else ends it for good`(): Unit = runBlocking {
        extender.openReview(agent, "refs/heads/main")
        reviewLoaded()

        selectedItem.value = SelectedItem.CommitItem(commit("Fix", "c0"), isStash = false, scrollToItem = false)

        withTimeout(5.seconds) { extender.state.first { it == null } }
        withTimeout(5.seconds) { while (closeableViews.isNotEmpty()) delay(10.milliseconds) }

        // Nothing selected again doesn't bring it back
        selectedItem.value = SelectedItem.None
        delay(200.milliseconds)
        assertNull(extender.state.value)
    }

    @Test
    fun `ending the review selects again what was selected before it opened`(): Unit = runBlocking {
        val before = SelectedItem.CommitItem(commit("Fix", "c0"), isStash = false, scrollToItem = false)
        selectedItem.value = before
        extender.openReview(agent, "refs/heads/main")
        reviewLoaded()

        extender.onAction(ComparisonAction.End)

        assertEquals(before, selectedItem.value)
        assertEquals(emptyList<CloseableView>(), closeableViews.toList())
        withTimeout(5.seconds) { extender.state.first { it == null } }
    }

    @Test
    fun `showing the uncommitted changes ends it for the status`(): Unit = runBlocking {
        selectedItem.value = SelectedItem.CommitItem(commit("Fix", "c0"), isStash = false, scrollToItem = false)
        extender.openReview(agent, "refs/heads/main")
        reviewLoaded()

        extender.onAction(ComparisonAction.ShowUncommittedChanges)

        assertEquals(SelectedItem.UncommittedChanges, selectedItem.value)
    }

    @Test
    fun `reads the comparison again when a branch moves, and not on every refresh`(): Unit = runBlocking {
        extender.openReview(agent, "refs/heads/main")
        assertEquals(listOf("Commit 1"), reviewLoaded().comparison!!.commitsOnlyInB.map { it.shortMessage })

        // A refresh that finds the same commits
        setBranches(agent1Hash = "a1")
        delay(300.milliseconds)
        assertEquals(1, compared.size)

        // The agent committed. Meanwhile the last comparison stays
        laterComparisons.value = CompletableDeferred()
        setBranches(agent1Hash = "a2")

        val reloading = withTimeout(5.seconds) { extender.state.first { it != null && it.isLoading } }
        assertEquals(listOf("Commit 1"), reloading!!.comparison!!.commitsOnlyInB.map { it.shortMessage })
        assertEquals("a2", reloading.bCommit)
        laterComparisons.value.complete(Unit)

        val moved = withTimeout(5.seconds) {
            extender.state.first { it != null && !it.isLoading && it.comparison!!.commitsOnlyInB.size == 2 }
        }
        assertEquals(listOf("Commit 1", "Commit 2"), moved!!.comparison!!.commitsOnlyInB.map { it.shortMessage })
    }

    @Test
    fun `the review doesn't open for a worktree on the base, or without a base`(): Unit = runBlocking {
        extender.openReview(main, "refs/heads/main")
        extender.openReview(agent, null)

        assertEquals(SelectedItem.UncommittedChanges, selectedItem.value)
        assertEquals(emptyList<CloseableView>(), closeableViews.toList())
        delay(200.milliseconds)
        assertNull(extender.state.value)
        assertEquals(emptyList<Any>(), compared.toList())
    }

    @Test
    fun `the review offers the tab's own uncommitted changes, and none for a worktree that's gone`() {
        val list = WorktreeList(
            WorktreeBaseBranch(automatic = "refs/heads/main"),
            listOf(WorktreeInfo(agent.copy(isCurrent = true), status(staged = 1), null, null)),
        )

        val own = reviewedWorktree(agent.path, list)
        val gone = reviewedWorktree(agent.path, list.copy(worktrees = emptyList()))

        assertEquals(1, own.uncommittedChanges)
        assertTrue(own.isTabWorktree)
        assertFalse(own.canSwitchTo)
        assertNull(gone.uncommittedChanges)
        assertEquals("leaf-agent1", gone.name)
    }

    // Comparisons that the user picks (F1 to F6)

    @Test
    fun `two picked commits compare older first, in all differences`(): Unit = runBlocking {
        selectedItem.value = selected("c2")

        extender.comparePicked(newer, older)
        val state = loaded()

        assertEquals(SelectedItem.None, selectedItem.value)
        assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
        assertEquals(listOf(Triple("c1", "c2", ALL)), compared.toList())
        assertEquals("c1", state.aCommit)
        assertEquals("c2", state.bCommit)
        assertEquals(ALL, state.mode)
        assertTrue(state.showsModeSwitch)
        assertNull(state.review)
    }

    @Test
    fun `two picked branch tips compare since they split, and follow their branches`(): Unit = runBlocking {
        extender.comparePicked(agentSide, mainSide)
        val state = loaded()

        assertEquals(listOf(Triple("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT)), compared.toList())
        assertEquals("main", state.aName)
        assertEquals("agent1", state.bName)

        setBranches(agent1Hash = "a2")

        val moved = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.bCommit == "a2" } }
        assertEquals(2, compared.size)
        assertEquals("a2", moved!!.bCommit)
    }

    @Test
    fun `commits stay where they are when branches move`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        loaded()

        setBranches(agent1Hash = "a2")
        delay(300.milliseconds)

        assertEquals(1, compared.size)
    }

    @Test
    fun `an ancestor that ended up as B goes first, and only the turned comparison shows`(): Unit = runBlocking {
        // c1 has the older date, but git says that it descends from c2: a date set by hand
        relations["c1" to "c2"] = B_IS_ANCESTOR
        relations["c2" to "c1"] = A_IS_ANCESTOR
        val shown = Collections.synchronizedList(mutableListOf<String>())
        val watching = viewModelScope.launch {
            extender.state.collect { state ->
                val comparison = state?.comparison

                if (state != null && !state.isLoading && comparison != null) {
                    shown += "${comparison.a} to ${comparison.b}"
                }
            }
        }

        extender.comparePicked(older, newer)
        val state = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aCommit == "c2" } }!!
        withTimeout(5.seconds) { while (shown.isEmpty()) delay(10.milliseconds) }
        watching.cancel()

        assertEquals(listOf(Triple("c1", "c2", ALL), Triple("c2", "c1", ALL)), compared.toList())
        assertEquals("c1", state.bCommit)
        assertEquals(A_IS_ANCESTOR, state.comparison!!.relation)
        // The first read, the wrong way round, never showed
        assertEquals(setOf("c2 to c1"), shown.toSet())
    }

    @Test
    fun `since they split shows all differences when it would list nothing, and the switch hides`(): Unit = runBlocking {
        relations["refs/heads/main" to "refs/heads/agent1"] = UNRELATED

        extender.comparePicked(mainSide, agentSide)
        val state = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.mode == ALL } }!!

        assertEquals(
            listOf(
                Triple("refs/heads/main", "refs/heads/agent1", SINCE_SPLIT),
                Triple("refs/heads/main", "refs/heads/agent1", ALL),
            ),
            compared.toList(),
        )
        assertFalse(state.showsModeSwitch)
    }

    @Test
    fun `the switch reads the other mode`(): Unit = runBlocking {
        extender.comparePicked(mainSide, agentSide)
        assertEquals(SINCE_SPLIT, loaded().mode)

        extender.onAction(ComparisonAction.SetMode(ALL))
        val all = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.mode == ALL } }!!

        assertEquals(ALL, all.comparison!!.mode)
        assertEquals(Triple("refs/heads/main", "refs/heads/agent1", ALL), compared.last())
    }

    @Test
    fun `a swap turns the sides around and keeps the mode`(): Unit = runBlocking {
        extender.comparePicked(mainSide, agentSide)
        loaded()

        extender.onAction(ComparisonAction.Swap)
        val swapped = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aName == "agent1" } }!!

        assertEquals("main", swapped.bName)
        assertEquals(SINCE_SPLIT, swapped.mode)
        assertEquals(Triple("refs/heads/agent1", "refs/heads/main", SINCE_SPLIT), compared.last())
        assertEquals(2, compared.size)
    }

    @Test
    fun `swapped onto its ancestor, a comparison stays the way the user put it, in all differences`(): Unit = runBlocking {
        relations["refs/heads/main" to "refs/heads/agent1"] = A_IS_ANCESTOR
        relations["refs/heads/agent1" to "refs/heads/main"] = B_IS_ANCESTOR
        extender.comparePicked(mainSide, agentSide)
        loaded()

        extender.onAction(ComparisonAction.Swap)
        val swapped = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aName == "agent1" } }!!
        delay(200.milliseconds)

        assertEquals(ALL, swapped.mode)
        assertEquals(listOf(Triple("refs/heads/agent1", "refs/heads/main", ALL)), compared.drop(1))
        assertEquals("agent1", extender.state.value!!.aName)
    }

    @Test
    fun `the current branch goes first, unless it is the newer of a branch and its ancestor`(): Unit = runBlocking {
        val tag = CompareSide.Commit("t1", tag = "refs/tags/v1")
        relations["refs/heads/main" to "t1"] = B_IS_ANCESTOR
        relations["t1" to "refs/heads/main"] = A_IS_ANCESTOR

        extender.compareWithCurrent(current = mainSide.side, other = tag)
        val state = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aName == "v1" } }!!

        assertEquals(listOf(Triple("refs/heads/main", "t1", ALL), Triple("t1", "refs/heads/main", ALL)), compared.toList())
        assertEquals("main", state.bName)

        extender.compareWithCurrent(current = mainSide.side, other = agentSide.side)
        val branches = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.bName == "agent1" } }!!

        assertEquals("main", branches.aName)
        assertEquals(SINCE_SPLIT, branches.mode)
    }

    @Test
    fun `the same commit twice compares nothing`(): Unit = runBlocking {
        extender.comparePicked(older, older.copy(side = CompareSide.Branch("refs/heads/old", "c1")))
        extender.compareWithCurrent(older.side, older.side)

        delay(200.milliseconds)
        assertNull(extender.state.value)
        assertEquals(emptyList<Any>(), compared.toList())
    }

    @Test
    fun `cmd-click on A or B drops it, and the other stays selected`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        loaded()

        extender.pick(newer)

        assertEquals(selected("c1"), selectedItem.value)
        assertEquals(emptyList<CloseableView>(), closeableViews.toList())

        extender.comparePicked(older, newer)
        loaded()

        extender.pick(older)

        assertEquals(selected("c2"), selectedItem.value)
    }

    @Test
    fun `cmd-click on another commit replaces B`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        loaded()

        extender.pick(PickedSide(CompareSide.Commit("c3"), date = 50))
        val replaced = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.bCommit == "c3" } }!!

        assertEquals("c1", replaced.aCommit)
        assertEquals(Triple("c1", "c3", ALL), compared.last())
    }

    @Test
    fun `ending selects A, and a branch side by the commit it is at now`(): Unit = runBlocking {
        extender.comparePicked(mainSide, agentSide)
        loaded()

        extender.onAction(ComparisonAction.End)

        assertEquals(selected("m1"), selectedItem.value)

        extender.comparePicked(agentSide, mainSide)
        loaded()
        extender.onAction(ComparisonAction.Swap)
        withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aName == "agent1" } }
        setBranches(agent1Hash = "a2")
        withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aCommit == "a2" } }

        extender.end()

        assertEquals(selected("a2"), selectedItem.value)
    }

    @Test
    fun `the full view opens at a file, Esc leaves it first, and then ends the comparison`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        val state = loaded()
        val second = state.comparison!!.changes[1]

        // At the file shown
        extender.onAction(ComparisonAction.SelectEntry(second))
        extender.onAction(ComparisonAction.OpenFullView())

        assertTrue(withTimeout(5.seconds) { extender.state.first { it?.isFullView == true } }!!.isFullView)
        assertEquals(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(second))), diffSelected.value)
        assertEquals(listOf(CloseableView.COMPARISON, CloseableView.COMPARISON_FULL_VIEW), closeableViews.toList())

        // Esc closes the last view: the full view, then the comparison
        extender.leaveFullView()

        assertFalse(withTimeout(5.seconds) { extender.state.first { it?.isFullView == false } }!!.isFullView)
        assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())

        extender.end()

        assertEquals(selected("c1"), selectedItem.value)
        assertEquals(emptyList<CloseableView>(), closeableViews.toList())
    }

    @Test
    fun `the full view opens at the first file when none is shown, and at the file clicked`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        val files = loaded().comparison!!.changes

        extender.onAction(ComparisonAction.OpenFullView())
        assertEquals(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(files[0]))), diffSelected.value)

        extender.onAction(ComparisonAction.OpenFullView(files[1]))
        assertEquals(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(files[1]))), diffSelected.value)
    }

    @Test
    fun `a failed read shows its error until Try again reads it again`(): Unit = runBlocking {
        failNext.set(true)

        extender.comparePicked(older, newer)
        val failed = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading } }!!

        assertEquals(GenericError("Couldn't read c2."), failed.error)
        assertNull(failed.comparison)

        extender.onAction(ComparisonAction.TryAgain)
        val read = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.comparison != null } }!!

        assertNull(read.error)
        assertEquals(2, compared.size)
    }

    /**
     * The review once the comparison loaded and the worktree list is in it. The state can show the comparison first, as
     * the list comes from a flow of its own, which a full test run's load can hold back.
     */
    private suspend fun reviewLoaded(): ComparisonState = withTimeout(5.seconds) {
        extender.state.first { it != null && !it.isLoading && it.review?.uncommittedChanges != null }!!
    }

    private suspend fun loaded(): ComparisonState = withTimeout(5.seconds) {
        extender.state.first { it != null && !it.isLoading && it.comparison != null }!!
    }

    private suspend fun setBranches(agent1Hash: String) {
        repositoryData.updateLocalBranches(gitDir) {
            delay(50.milliseconds)

            Either.Ok(listOf(Branch("m1", "refs/heads/main", isLocal = true), Branch(agent1Hash, "refs/heads/agent1", isLocal = true)))
        }
    }

    private fun selected(hash: String): SelectedItem = SelectedItem.CommitItem(commit("Picked", hash), isStash = false, scrollToItem = false)

    private fun comparison(a: String, b: String, mode: ComparisonMode, subjects: List<String>): Comparison {
        val relation = relations[a to b] ?: DIVERGED

        return Comparison(
            a = a,
            b = b,
            mode = mode,
            relation = relation,
            mergeBase = if (relation == UNRELATED) null else "m0",
            commitsOnlyInB = subjects.mapIndexed { index, subject -> commit(subject, "c${index + 1}") },
            countOnlyInB = subjects.size,
            commitsOnlyInA = emptyList(),
            countOnlyInA = 0,
            changes = listOf(addedEntry("retry.txt"), addedEntry("test/retry_test.txt")),
        )
    }

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
