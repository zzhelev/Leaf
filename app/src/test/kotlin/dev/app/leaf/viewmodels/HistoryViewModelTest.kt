// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IFormatDiffGitAction
import dev.app.leaf.domain.interfaces.IGetCommitDiffEntriesGitAction
import dev.app.leaf.domain.interfaces.IGetFileCommitsAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetDiffUseCase
import dev.app.leaf.domain.usecases.GetFileCommitsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
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
import org.junit.jupiter.api.Test
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

private const val FILE = "src/App.kt"

class HistoryViewModelTest {
    private val diffTextViewType = MutableStateFlow<DiffTextViewType?>(DiffTextViewType.Unified)
    private val settings = AppSettingsService(
        mockk<AppSettingsRepository> {
            every { diffTextViewType } returns this@HistoryViewModelTest.diffTextViewType
            every { diffDisplayFullFile } returns MutableStateFlow(false)
        }
    )

    /** The tab, which stays open while file histories are opened and closed. */
    private val tabScope = TabCoroutineScope()

    private val repositoryDataRepository = mockk<RepositoryDataRepository> {
        every { repositoryPath } returns "/repo/.git"
    }

    private val useCaseExecutor = UseCaseExecutor(
        repositoryDataRepository,
        InMemoryRepositoryStateRepository(),
        Provider { error("Nothing is refreshed") },
        tabScope,
    )

    /** The file's commits, newest first, as git log lists them. */
    private val commits = listOf("a", "b", "c").map { commit(it) }

    /** The commits that moved the file to another path, which the change lists show as a rename. */
    private val renamedIn = mutableSetOf<String>()

    /** How long loading a commit's changed files takes, by hash, to make one finish after another. */
    private val loadTimes = mutableMapOf<String, Long>()

    /** How long formatting a commit's diff takes, by hash. */
    private val formatTimes = mutableMapOf<String, Long>()

    private val getFileCommits = mockk<IGetFileCommitsAction> {
        coEvery { this@mockk(any(), FILE) } returns Either.Ok(commits)
    }

    private val getCommitDiffEntries = mockk<IGetCommitDiffEntriesGitAction> {
        coEvery { this@mockk(any(), any()) } coAnswers {
            val commit = secondArg<Commit>()
            loadTimes[commit.hash]?.let { delay(it) }

            Either.Ok(listOf(diffEntry(commit)))
        }
    }

    private val formatDiff = mockk<IFormatDiffGitAction> {
        coEvery { this@mockk(any(), any(), any()) } coAnswers {
            val diffType = secondArg<DiffType.CommitDiff>()
            formatTimes[diffType.diffEntry.newId.name()]?.let { delay(it) }

            Either.Ok(DiffResult.Text(diffType.diffEntry, emptyList()))
        }
    }

    private fun historyViewModel() = HistoryViewModel(
        GetCommitDiffEntriesUseCase(getCommitDiffEntries, mockk(), useCaseExecutor),
        mockk(),
        settings,
        GetFileCommitsUseCase(getFileCommits, useCaseExecutor),
        GetDiffUseCase(formatDiff, mockk(), repositoryDataRepository),
    )

    @AfterEach
    fun tearDown() {
        tabScope.cancel()
    }

    @Test
    fun `a file history stops following the diff setting once it's closed`(): Unit = runBlocking {
        val viewModel = historyViewModel()

        withTimeout(5.seconds) { diffTextViewType.subscriptionCount.first { it == 1 } }

        viewModel.onClear()

        // In the tab's scope, every file history opened kept following it until the tab closed
        withTimeout(5.seconds) { diffTextViewType.subscriptionCount.first { it == 0 } }
    }

    @Test
    fun `opens on the newest commit, with its diff`(): Unit = runBlocking {
        val viewModel = historyViewModel()

        viewModel.fileHistory(FILE)

        viewModel.awaitDiffOf(commits[0])
        assertEquals(commits[0], viewModel.selectedCommit.value)

        viewModel.onClear()
    }

    @Test
    fun `the commit that moved the file is in the history of the name that it had`(): Unit = runBlocking {
        renamedIn += hash("b")
        val viewModel = historyViewModel()
        viewModel.fileHistory(FILE)
        viewModel.awaitDiffOf(commits[0])

        viewModel.selectCommit(commits[1])

        // A rename lists the new path, where the deletion that it used to be listed the old one
        viewModel.awaitDiffOf(commits[1])
        assertEquals(hash("b"), viewModel.shownDiffId())

        viewModel.onClear()
    }

    @Test
    fun `steps through the commits, and stays at either end`(): Unit = runBlocking {
        val viewModel = historyViewModel()
        viewModel.fileHistory(FILE)
        viewModel.awaitDiffOf(commits[0])

        val selected = listOf(1, 1, 1, -1, -1, -1).map { offset ->
            viewModel.selectAdjacentCommit(offset)
            viewModel.selectedCommit.value?.hash
        }

        assertEquals(listOf("b", "c", "c", "b", "a", "a").map { hash(it) }, selected)
        viewModel.awaitDiffOf(commits[0])

        viewModel.onClear()
    }

    @Test
    fun `shows the diff of the last commit selected, although an earlier one loads last`(): Unit = runBlocking {
        val viewModel = historyViewModel()
        viewModel.fileHistory(FILE)
        viewModel.awaitDiffOf(commits[0])

        loadTimes[hash("b")] = 1_000
        viewModel.selectCommit(commits[1])
        viewModel.selectCommit(commits[2])
        viewModel.awaitDiffOf(commits[2])

        // b's diff would have been done by now, and replaced c's
        delay(1_500)

        assertEquals(hash("c"), viewModel.shownDiffId())
        assertEquals(commits[2], viewModel.selectedCommit.value)

        viewModel.onClear()
    }

    @Test
    fun `doesn't blank the diff when another commit is selected while one is formatted`(): Unit = runBlocking {
        val viewModel = historyViewModel()
        viewModel.fileHistory(FILE)
        viewModel.awaitDiffOf(commits[0])
        val shown = mutableListOf<ViewDiffResult>()
        val collecting = launch(Dispatchers.Unconfined) { viewModel.viewDiffResult.collect { shown += it } }

        formatTimes[hash("b")] = 1_000
        loadTimes[hash("c")] = 300
        viewModel.selectCommit(commits[1])
        delay(100)
        viewModel.selectCommit(commits[2])
        viewModel.awaitDiffOf(commits[2])
        collecting.cancel()

        // GetDiffUseCase turns b's cancellation into DiffNotFound
        assertEquals(listOf(hash("a"), hash("c")), shown.map { it.shownId() })

        viewModel.onClear()
    }

    @Test
    fun `another commit's diff starts at its top`(): Unit = runBlocking {
        val viewModel = historyViewModel()
        viewModel.fileHistory(FILE)
        viewModel.awaitDiffOf(commits[0])
        viewModel.lazyListState.value = LazyListState(firstVisibleItemIndex = 40)

        viewModel.selectAdjacentCommit(1)
        viewModel.awaitDiffOf(commits[1])

        assertEquals(0, viewModel.lazyListState.value.firstVisibleItemIndex)

        viewModel.onClear()
    }

    private suspend fun HistoryViewModel.awaitDiffOf(commit: Commit) {
        withTimeout(5.seconds) { viewDiffResult.first { shownDiffId() == commit.hash } }
    }

    private fun HistoryViewModel.shownDiffId(): String? = viewDiffResult.value.shownId()

    private fun ViewDiffResult.shownId(): String = when (this) {
        is ViewDiffResult.Loaded -> diffResult.diffEntry.newId.name()
        else -> this::class.simpleName.orEmpty()
    }

    /** The file's change in [commit], told apart from the other commits' by the file's id. */
    private fun diffEntry(commit: Commit) = mockk<DiffEntry> {
        val isRename = commit.hash in renamedIn

        every { changeType } returns if (isRename) DiffEntry.ChangeType.RENAME else DiffEntry.ChangeType.MODIFY
        every { oldPath } returns FILE
        every { newPath } returns if (isRename) "src/Moved.kt" else FILE
        every { score } returns 100
        every { newId } returns AbbreviatedObjectId.fromObjectId(ObjectId.fromString(commit.hash))
    }

    private fun hash(letter: String) = letter.repeat(40)

    private fun commit(letter: String) = Commit(
        hash = hash(letter),
        message = "Commit $letter",
        committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        date = 1_760_000_000,
        parentsHashes = emptyList(),
    )
}
