// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetDiffUseCase
import dev.app.leaf.domain.usecases.GetFileCommitsUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

class HistoryViewModelTest {
    private val diffTextViewType = MutableStateFlow<DiffTextViewType?>(DiffTextViewType.Unified)
    private val settings = AppSettingsService(
        mockk<AppSettingsRepository> { every { diffTextViewType } returns this@HistoryViewModelTest.diffTextViewType }
    )

    /** The tab, which stays open while file histories are opened and closed. */
    private val tabScope = TabCoroutineScope()

    private val useCaseExecutor = UseCaseExecutor(
        mockk(),
        InMemoryRepositoryStateRepository(),
        Provider { error("Nothing is refreshed") },
        tabScope,
    )

    @AfterEach
    fun tearDown() {
        tabScope.cancel()
    }

    @Test
    fun `a file history stops following the diff setting once it's closed`(): Unit = runBlocking {
        val viewModel = HistoryViewModel(
            GetCommitDiffEntriesUseCase(mockk(), mockk(), useCaseExecutor),
            mockk(),
            settings,
            GetFileCommitsUseCase(mockk(), useCaseExecutor),
            GetDiffUseCase(mockk(), mockk(), mockk()),
        )

        withTimeout(5.seconds) { diffTextViewType.subscriptionCount.first { it == 1 } }

        viewModel.onClear()

        // In the tab's scope, every file history opened kept following it until the tab closed
        withTimeout(5.seconds) { diffTextViewType.subscriptionCount.first { it == 0 } }
    }
}
