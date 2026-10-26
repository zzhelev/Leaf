// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.compare.RefKind
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommitDiffEntriesGitAction
import dev.app.leaf.domain.interfaces.IGetCommitFromHashGitAction
import dev.app.leaf.domain.interfaces.IGetComparisonGitAction
import dev.app.leaf.domain.interfaces.IGetWorkingTreeComparisonGitAction
import dev.app.leaf.domain.interfaces.IResolveCommitGitAction
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
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.GetComparisonUseCase
import dev.app.leaf.domain.usecases.GetWorkingTreeComparisonUseCase
import dev.app.leaf.domain.usecases.ResolveCommitUseCase
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
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /** The commits that only B has, for the comparisons read from now on. One more per read unless set. */
    @Volatile
    private var commitsOnlyInB: List<Commit>? = null

    /** Each commit's own changes, by hash, and the commits whose changes were read. */
    private val commitFiles = ConcurrentHashMap<String, List<DiffEntry>>()
    private val commitReads = Collections.synchronizedList(mutableListOf<String>())

    /** Holds back the reads of a commit's changes until it completes. */
    private val heldCommitReads = MutableStateFlow(CompletableDeferred<Unit>().apply { complete(Unit) })

    private val failNextCommit = AtomicBoolean(false)

    /** The commits that typed revisions name, and the revisions that were asked for. */
    private val revisions = ConcurrentHashMap<String, Commit>()
    private val resolved = Collections.synchronizedList(mutableListOf<String>())

    /** The working tree comparisons that git was asked for: A and the mode. */
    private val comparedWithWorkingTree = Collections.synchronizedList(mutableListOf<Pair<String, ComparisonMode>>())

    /** How the working tree's HEAD relates to A, by A's revision. Diverged unless set. */
    private val workingTreeRelations = ConcurrentHashMap<String, ComparisonRelation>()

    private val workingTreeAction = object : IGetWorkingTreeComparisonGitAction {
        override suspend fun invoke(repositoryPath: String, a: String, mode: ComparisonMode): Either<Comparison, GitError> {
            comparedWithWorkingTree += a to mode
            val relation = workingTreeRelations[a] ?: DIVERGED

            return Either.Ok(
                comparison(a, "HEAD", mode, subjects = listOf("Feature work")).copy(
                    relation = relation,
                    changes = listOf(addedEntry("untracked.txt"), modifiedEntry("a.txt")),
                    untrackedPaths = setOf("untracked.txt"),
                )
            )
        }
    }

    private val resolveAction = object : IResolveCommitGitAction {
        override suspend fun invoke(repositoryPath: String, revision: String): Either<Commit?, GitError> {
            resolved += revision

            return Either.Ok(revisions[revision])
        }
    }

    private val commitDiffAction = object : IGetCommitDiffEntriesGitAction {
        override suspend fun invoke(repositoryPath: String, commit: Commit): Either<List<DiffEntry>, GitError> {
            heldCommitReads.value.await()
            commitReads += commit.hash

            if (failNextCommit.getAndSet(false)) return Either.Err(GenericError("Couldn't read ${commit.hash}."))

            return Either.Ok(commitFiles[commit.hash].orEmpty())
        }
    }

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
    private val diffsCleared = AtomicInteger()
    private val closeableViews = Collections.synchronizedList(mutableListOf<CloseableView>())
    private val repositoryState = InMemoryRepositoryStateRepository()
    private val useCaseExecutor = UseCaseExecutor(repositoryData, repositoryState, Provider { error("No refresh") }, tabScope)

    private val extender = ComparisonViewModelExtender(
        getComparisonUseCase = GetComparisonUseCase(gitAction, useCaseExecutor),
        getWorkingTreeComparisonUseCase = GetWorkingTreeComparisonUseCase(workingTreeAction, useCaseExecutor),
        repositoryStateRepository = repositoryState,
        getCommitDiffEntriesUseCase = GetCommitDiffEntriesUseCase(
            commitDiffAction,
            object : IGetCommitFromHashGitAction {
                override suspend fun invoke(repositoryPath: String, commitHash: String) = error("Not a stash")
            },
            useCaseExecutor,
        ),
        resolveCommitUseCase = ResolveCommitUseCase(resolveAction, useCaseExecutor),
        repositoryDataRepository = repositoryData,
        viewModelScope = viewModelScope,
        filesViewState = MutableStateFlow(FilesViewState()),
        selectedItem = selectedItem,
        onSelectItem = { selectedItem.value = it },
        diffSelected = diffSelected,
        onDiffSelected = { diffSelected.value = it },
        clearDiff = {
            diffsCleared.incrementAndGet()
            diffSelected.value = null
        },
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
        repositoryData.updateTags(gitDir) { Either.Ok(listOf(Tag("t1", "t1-object", "refs/tags/v1.0"))) }
        repositoryData.updateCurrentBranch(gitDir) { Either.Ok(Branch("a1", "refs/heads/agent1", isLocal = true)) }
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

    // A commit's own changes (7.4)

    private val c1 = commit("Read the refs", "c1")
    private val c2 = commit("List them", "c2")
    private val c3 = commit("Pick one", "c3")

    @Test
    fun `a commit's click lists its own changes, and a second click or All changes lists the comparison's again`(): Unit =
        runBlocking {
            commitsOnlyInB = listOf(c1, c2, c3)
            commitFiles["c2"] = listOf(modifiedEntry("src/Parser.kt"))
            extender.comparePicked(older, newer)
            val all = loaded().files

            extender.onAction(ComparisonAction.ShowCommit(c2))
            val shown = commitLoaded("c2")

            assertEquals(2, shown.shownCommit!!.position)
            assertEquals(3, shown.shownCommit!!.count)
            assertEquals(commitFiles["c2"], shown.files)
            assertEquals(listOf("src/Parser.kt"), shown.files.map { it.newPath })
            assertEquals(1, shown.rows.size)
            assertEquals(listOf(CloseableView.COMPARISON, CloseableView.COMPARISON_COMMIT), closeableViews.toList())

            // A second click on it
            extender.onAction(ComparisonAction.ShowCommit(c2))
            val back = withTimeout(5.seconds) { extender.state.first { it?.shownCommit == null } }!!

            assertEquals(all, back.files)
            assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())

            // All changes
            extender.onAction(ComparisonAction.ShowCommit(c3))
            commitLoaded("c3")
            extender.onAction(ComparisonAction.ShowAllChanges)

            assertEquals(all, withTimeout(5.seconds) { extender.state.first { it?.shownCommit == null } }!!.files)
            assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
        }

    @Test
    fun `the arrows step through the side's commits, oldest to newest, and stop at its ends`(): Unit = runBlocking {
        commitsOnlyInB = listOf(c1, c2, c3)
        extender.comparePicked(older, newer)
        loaded()

        extender.onAction(ComparisonAction.ShowCommit(c1))
        commitLoaded("c1")

        extender.onAction(ComparisonAction.StepCommit(-1))
        delay(100.milliseconds)
        assertEquals("c1", extender.state.value!!.shownCommit!!.commit.hash)

        extender.onAction(ComparisonAction.StepCommit(1))
        assertEquals(2, commitLoaded("c2").shownCommit!!.position)

        extender.onAction(ComparisonAction.StepCommit(1))
        assertEquals(3, commitLoaded("c3").shownCommit!!.position)

        extender.onAction(ComparisonAction.StepCommit(1))
        delay(100.milliseconds)
        assertEquals("c3", extender.state.value!!.shownCommit!!.commit.hash)
        assertEquals(listOf("c1", "c2", "c3"), commitReads.toList())
    }

    @Test
    fun `while a commit's changes load, the list keeps what it showed`(): Unit = runBlocking {
        commitsOnlyInB = listOf(c1, c2)
        commitFiles["c1"] = listOf(modifiedEntry("a.txt"))
        commitFiles["c2"] = listOf(modifiedEntry("b.txt"))
        extender.comparePicked(older, newer)
        val all = loaded().files

        heldCommitReads.value = CompletableDeferred()
        extender.onAction(ComparisonAction.ShowCommit(c1))
        val fromAll = withTimeout(5.seconds) { extender.state.first { it?.shownCommit?.isLoading == true } }!!

        assertEquals(all, fromAll.files)
        assertTrue(fromAll.isLoading)

        heldCommitReads.value.complete(Unit)
        commitLoaded("c1")

        heldCommitReads.value = CompletableDeferred()
        extender.onAction(ComparisonAction.StepCommit(1))
        val fromC1 = withTimeout(5.seconds) {
            extender.state.first { it?.shownCommit?.commit?.hash == "c2" && it.shownCommit!!.isLoading }
        }!!

        assertEquals(commitFiles["c1"], fromC1.files)

        heldCommitReads.value.complete(Unit)
        assertEquals(commitFiles["c2"], commitLoaded("c2").files)
    }

    @Test
    fun `the full view's diff follows the list to the commit's version of the file, or else to its first file`(): Unit =
        runBlocking {
            commitsOnlyInB = listOf(c1, c2)
            commitFiles["c1"] = listOf(addedEntry("new.txt"), modifiedEntry("retry.txt", id = 1))
            commitFiles["c2"] = listOf(addedEntry("other.txt"))
            extender.comparePicked(older, newer)
            val all = loaded().files

            extender.onAction(ComparisonAction.OpenFullView(all[0]))
            assertEquals(all[0], shownDiff())

            extender.onAction(ComparisonAction.ShowCommit(c1))
            commitLoaded("c1")
            withTimeout(5.seconds) { while (shownDiff() !== commitFiles["c1"]!![1]) delay(10.milliseconds) }

            extender.onAction(ComparisonAction.ShowCommit(c2))
            commitLoaded("c2")
            withTimeout(5.seconds) { while (shownDiff() !== commitFiles["c2"]!![0]) delay(10.milliseconds) }

            // Back to all changes, which don't have other.txt
            extender.onAction(ComparisonAction.ShowAllChanges)
            withTimeout(5.seconds) { while (shownDiff() !== all[0]) delay(10.milliseconds) }
            assertEquals(0, diffsCleared.get())
        }

    @Test
    fun `in the pane, a diff of a file that the commit didn't change closes`(): Unit = runBlocking {
        commitsOnlyInB = listOf(c1)
        commitFiles["c1"] = listOf(addedEntry("other.txt"))
        extender.comparePicked(older, newer)
        val all = loaded().files

        // The keys show a file's diff from the pane's list
        extender.onAction(ComparisonAction.SelectEntry(all[1]))
        extender.onAction(ComparisonAction.ShowCommit(c1))
        commitLoaded("c1")

        withTimeout(5.seconds) { while (diffsCleared.get() == 0) delay(10.milliseconds) }
        assertNull(diffSelected.value)
    }

    @Test
    fun `a diff whose file a new read has in the same versions stays as it is`(): Unit = runBlocking {
        extender.comparePicked(mainSide, agentSide)
        val first = loaded().files
        extender.onAction(ComparisonAction.OpenFullView(first[1]))

        // The agent committed: the comparison is read again, with new entries for the same versions
        setBranches(agent1Hash = "a2")
        withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.bCommit == "a2" && it.files !== first } }
        delay(200.milliseconds)

        assertSame(first[1], shownDiff())
    }

    @Test
    fun `a commit that a new read no longer lists stops being shown`(): Unit = runBlocking {
        commitsOnlyInB = listOf(c1, c2)
        extender.comparePicked(mainSide, agentSide)
        loaded()
        extender.onAction(ComparisonAction.ShowCommit(c2))
        commitLoaded("c2")

        // The agent's branch was rebased: c2 is gone
        commitsOnlyInB = listOf(c1, c3)
        setBranches(agent1Hash = "a2")

        // The shown commit and its closeable view go before the new read shows, so wait for the read too
        val after = withTimeout(5.seconds) {
            extender.state.first { it != null && it.bCommit == "a2" && it.shownCommit == null && !it.isLoading }
        }!!
        assertEquals(listOf("c1", "c3"), after.comparison!!.commitsOnlyInB.map { it.hash })
        assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
    }

    @Test
    fun `Esc leaves a commit's changes before the full view, and the full view before the comparison`(): Unit =
        runBlocking {
            commitsOnlyInB = listOf(c1)
            extender.comparePicked(older, newer)
            loaded()

            extender.onAction(ComparisonAction.OpenFullView())
            extender.onAction(ComparisonAction.ShowCommit(c1))

            assertEquals(
                listOf(CloseableView.COMPARISON, CloseableView.COMPARISON_FULL_VIEW, CloseableView.COMPARISON_COMMIT),
                closeableViews.toList(),
            )

            // A new comparison starts with all changes
            extender.pick(PickedSide(CompareSide.Commit("c9"), date = 500))
            val next = withTimeout(5.seconds) {
                extender.state.first { it?.target?.b == CompareSide.Commit("c9") && !it.isLoading }
            }!!

            assertNull(next.shownCommit)
            assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
        }

    @Test
    fun `a commit's read that failed shows its error until Try again`(): Unit = runBlocking {
        commitsOnlyInB = listOf(c1)
        commitFiles["c1"] = listOf(addedEntry("new.txt"))
        failNextCommit.set(true)
        extender.comparePicked(older, newer)
        loaded()

        extender.onAction(ComparisonAction.ShowCommit(c1))
        val failed = commitLoaded("c1")

        assertEquals(GenericError("Couldn't read c1."), failed.shownCommit!!.error)
        assertEquals(emptyList<DiffEntry>(), failed.files)

        extender.onAction(ComparisonAction.TryAgain)
        val read = withTimeout(5.seconds) {
            extender.state.first { it?.shownCommit?.isLoading == false && it.shownCommit!!.error == null && !it.isLoading }
        }!!

        assertEquals(commitFiles["c1"], read.files)
        assertNotNull(read.comparison)
    }

    // Any two refs (7.1)

    @Test
    fun `Compare with opens with A alone and B's list, reads nothing, and closing the list ends it`(): Unit =
        runBlocking {
            val before = selected("c0")
            selectedItem.value = before

            extender.compareWith(CompareSide.Branch("refs/heads/agent1", "a1"))
            val pending = withTimeout(5.seconds) { extender.state.first { it?.picker != null } }!!

            assertTrue(pending.isPending)
            assertFalse(pending.isLoading)
            assertEquals("agent1", pending.aName)
            assertEquals(SideToPick.B, pending.picker!!.side)
            assertEquals(SelectedItem.None, selectedItem.value)
            assertEquals(listOf(CloseableView.COMPARISON, CloseableView.COMPARISON_SIDE_PICKER), closeableViews.toList())
            delay(200.milliseconds)
            assertEquals(emptyList<Triple<String, String, ComparisonMode>>(), compared.toList())

            // Esc
            extender.closeSidePicker()

            withTimeout(5.seconds) { extender.state.first { it == null } }
            assertEquals(before, selectedItem.value)
            assertEquals(emptyList<CloseableView>(), closeableViews.toList())
        }

    @Test
    fun `a pick from B's list compares it with A, in the order the user put them`(): Unit = runBlocking {
        extender.compareWith(CompareSide.Branch("refs/heads/agent1", "a1"))
        withTimeout(5.seconds) { extender.state.first { it?.picker != null } }

        extender.onAction(ComparisonAction.PickSide(CompareSide.Branch("refs/heads/main", "m1")))
        val state = loaded()

        assertFalse(state.isPending)
        assertNull(state.picker)
        assertEquals("agent1", state.aName)
        assertEquals("main", state.bName)
        assertEquals(listOf(Triple("refs/heads/agent1", "refs/heads/main", SINCE_SPLIT)), compared.toList())
        assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
    }

    @Test
    fun `a running comparison changes one side from its list, and keeps the other`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        loaded()

        extender.onAction(ComparisonAction.OpenSidePicker(SideToPick.A))
        withTimeout(5.seconds) { extender.state.first { it?.picker?.side == SideToPick.A } }
        assertEquals(listOf(CloseableView.COMPARISON, CloseableView.COMPARISON_SIDE_PICKER), closeableViews.toList())

        extender.onAction(ComparisonAction.PickSide(CompareSide.Commit("t1", tag = "refs/tags/v1.0")))
        val state = withTimeout(5.seconds) { extender.state.first { it != null && !it.isLoading && it.aName == "v1.0" } }!!

        assertEquals("c2", state.bCommit)
        assertEquals(Triple("t1", "c2", ALL), compared.last())

        // Closing the list of a running comparison leaves it as it is
        extender.onAction(ComparisonAction.OpenSidePicker(SideToPick.B))
        extender.onAction(ComparisonAction.CloseSidePicker)
        val after = withTimeout(5.seconds) { extender.state.first { it != null && it.picker == null } }!!

        assertEquals("v1.0", after.aName)
        assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
    }

    @Test
    fun `the list holds the tab's refs, filtered by what is typed, and a commit for a revision that git resolves`(): Unit =
        runBlocking {
            revisions["HEAD~2"] = commit("Older work", "h2".padEnd(40, '0'))
            extender.comparePicked(older, newer)
            loaded()

            extender.onAction(ComparisonAction.OpenSidePicker(SideToPick.B))
            val all = withTimeout(5.seconds) { extender.state.first { it?.picker?.choices?.total == 3 } }!!.picker!!

            assertEquals(listOf("agent1", "main", "v1.0"), all.choices.rows.map { it.name })

            extender.onAction(ComparisonAction.SidePickerTextChanged("ma"))
            val filtered = withTimeout(5.seconds) { extender.state.first { it?.picker?.text == "ma" } }!!.picker!!

            assertEquals(listOf("main"), filtered.choices.rows.map { it.name })

            extender.onAction(ComparisonAction.SidePickerTextChanged("HEAD~2"))
            val revision = withTimeout(5.seconds) {
                extender.state.first { it?.picker?.choices?.groups?.firstOrNull()?.kind == RefKind.COMMIT }
            }!!.picker!!

            assertEquals("Older work", revision.choices.rows.single().detail)
            assertEquals(CompareSide.Commit("h2".padEnd(40, '0')), revision.choices.rows.single().side)
            assertTrue("HEAD~2" in resolved)
        }

    @Test
    fun `the review's sides are the worktree's, so they open no list`(): Unit = runBlocking {
        extender.openReview(agent, "refs/heads/main")
        reviewLoaded()

        extender.onAction(ComparisonAction.OpenSidePicker(SideToPick.B))
        delay(200.milliseconds)

        assertNull(extender.state.value!!.picker)
        assertEquals(listOf(CloseableView.COMPARISON), closeableViews.toList())
    }

    // The working tree (7.2)

    private val workingTree = CompareSide.WorkingTree("a1", branch = "refs/heads/agent1")

    @Test
    fun `a branch compared with the working tree reads it as B, since they split, with its untracked files`(): Unit =
        runBlocking {
            extender.compareWithWorkingTree(mainSide.side, workingTree)
            val state = loaded()

            assertEquals(listOf("refs/heads/main" to SINCE_SPLIT), comparedWithWorkingTree.toList())
            assertEquals(emptyList<Triple<String, String, ComparisonMode>>(), compared.toList())
            assertTrue(state.bIsWorkingTree)
            assertEquals("main", state.aName)
            assertEquals(
                mapOf("a.txt" to false, "untracked.txt" to true),
                state.rows.associate { (it as FileRow.File).file.path to it.file.isUntracked },
            )

            // Its files' diffs read the disk
            extender.onAction(ComparisonAction.SelectEntry(state.files[0]))
            assertTrue((diffSelected.value as DiffSelected.CommitedChanges).items.single().readsWorkingTree)
        }

    @Test
    fun `the working tree stays B, and with A ahead of HEAD both modes show`(): Unit = runBlocking {
        workingTreeRelations["refs/heads/main"] = B_IS_ANCESTOR
        extender.compareWithWorkingTree(mainSide.side, workingTree)
        val state = loaded()
        delay(200.milliseconds)

        // No turning around, no second read in the other mode, and the switch
        assertEquals(listOf("refs/heads/main" to SINCE_SPLIT), comparedWithWorkingTree.toList())
        assertEquals("main", state.aName)
        assertTrue(state.showsModeSwitch)

        extender.onAction(ComparisonAction.Swap)
        delay(200.milliseconds)
        assertTrue(extender.state.value!!.bIsWorkingTree)

        extender.onAction(ComparisonAction.SetMode(ALL))
        withTimeout(5.seconds) { extender.state.first { it?.mode == ALL && !it.isLoading } }
        assertEquals("refs/heads/main" to ALL, comparedWithWorkingTree.last())
    }

    @Test
    fun `a status refresh reads the working tree again, and other refreshes don't`(): Unit = runBlocking {
        extender.compareWithWorkingTree(older.side, workingTree)
        loaded()
        assertEquals(1, comparedWithWorkingTree.size)

        repositoryState.refreshTriggered(listOf(DataToRefresh.LOG))
        delay(200.milliseconds)
        assertEquals(1, comparedWithWorkingTree.size)

        repositoryState.refreshTriggered(listOf(DataToRefresh.STATUS))
        withTimeout(5.seconds) { while (comparedWithWorkingTree.size < 2) delay(10.milliseconds) }

        repositoryState.refreshTriggered(listOf(DataToRefresh.ALL))
        withTimeout(5.seconds) { while (comparedWithWorkingTree.size < 3) delay(10.milliseconds) }
    }

    @Test
    fun `cmd-click on the uncommitted changes makes the working tree B, and again drops it`(): Unit = runBlocking {
        extender.comparePicked(older, newer)
        loaded()

        extender.pickWorkingTree(workingTree)
        val withWorkingTree = withTimeout(5.seconds) { extender.state.first { it?.bIsWorkingTree == true && !it.isLoading } }!!
        assertEquals("c1", withWorkingTree.aCommit)

        // HEAD's row isn't B: cmd-click on it replaces B
        extender.pick(PickedSide(CompareSide.Commit("a1"), date = 500))
        withTimeout(5.seconds) { extender.state.first { it?.target?.b == CompareSide.Commit("a1") } }

        extender.pickWorkingTree(workingTree)
        withTimeout(5.seconds) { extender.state.first { it?.bIsWorkingTree == true } }
        extender.pickWorkingTree(workingTree)

        withTimeout(5.seconds) { extender.state.first { it == null } }
        assertEquals(selected("c1"), selectedItem.value)
    }

    @Test
    fun `dropping A of a comparison with the working tree selects the uncommitted changes`(): Unit = runBlocking {
        // Not what was selected before
        selectedItem.value = selected("c0")
        extender.compareWithWorkingTree(older.side, workingTree)
        loaded()

        extender.pick(PickedSide(older.side, older.date))

        withTimeout(5.seconds) { extender.state.first { it == null } }
        assertEquals(SelectedItem.UncommittedChanges, selectedItem.value)
    }

    private fun shownDiff(): DiffEntry? =
        (diffSelected.value as? DiffSelected.CommitedChanges)?.items?.singleOrNull()?.diffEntry

    /** The comparison once the changes of [hash] were read. */
    private suspend fun commitLoaded(hash: String): ComparisonState = withTimeout(5.seconds) {
        extender.state.first { it?.shownCommit?.commit?.hash == hash && !it.shownCommit!!.isLoading }!!
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
            commitsOnlyInB = commitsOnlyInB ?: subjects.mapIndexed { index, subject -> commit(subject, "c${index + 1}") },
            countOnlyInB = commitsOnlyInB?.size ?: subjects.size,
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

    /** A modified file, whose versions' ids are [id] and the next. */
    private fun modifiedEntry(path: String, id: Int = 0) = object : DiffEntry() {
        init {
            changeType = ChangeType.MODIFY
            oldPath = path
            newPath = path
            oldId = AbbreviatedObjectId.fromObjectId(ObjectId.fromString("%040x".format(id)))
            newId = AbbreviatedObjectId.fromObjectId(ObjectId.fromString("%040x".format(id + 1)))
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
