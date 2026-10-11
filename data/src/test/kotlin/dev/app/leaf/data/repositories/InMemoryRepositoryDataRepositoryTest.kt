// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.repositories.DataState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private const val MAIN_GIT_DIR = "/repo/.git"
private const val FEATURE_GIT_DIR = "/repo/.git/worktrees/feature"

private val mainBranch = Branch("1111111", "refs/heads/main", isLocal = true)
private val featureBranch = Branch("2222222", "refs/heads/feature", isLocal = true)

/** What a refresh writes once the tab switched to another worktree while it ran, or before it started. */
class InMemoryRepositoryDataRepositoryTest {
    private val repository = InMemoryRepositoryDataRepository()

    @Test
    fun `shows what a refresh loaded for the tab's git dir`(): Unit = runBlocking {
        repository.setRepositorySelectionState(RepositorySelectionState.Open(MAIN_GIT_DIR))

        repository.updateCurrentBranch(MAIN_GIT_DIR) { Either.Ok(mainBranch) }

        assertEquals(DataState.Loaded(mainBranch), repository.currentBranch.first())
    }

    @Test
    fun `drops what a refresh loaded for the worktree that the tab left meanwhile`(): Unit = runBlocking {
        repository.setRepositorySelectionState(RepositorySelectionState.Open(MAIN_GIT_DIR))
        repository.updateCurrentBranch(MAIN_GIT_DIR) { Either.Ok(mainBranch) }
        val gitStarted = CompletableDeferred<Unit>()
        val gitDone = CompletableDeferred<Unit>()

        val refresh = async(Dispatchers.Default) {
            repository.updateCurrentBranch(MAIN_GIT_DIR) {
                gitStarted.complete(Unit)
                gitDone.await()
                Either.Ok(mainBranch)
            }
        }

        withTimeout(5.seconds) { gitStarted.await() }
        repository.setRepositorySelectionState(RepositorySelectionState.Open(FEATURE_GIT_DIR))
        gitDone.complete(Unit)
        refresh.await()

        assertEquals(DataState.Loading, repository.currentBranch.first())

        repository.updateCurrentBranch(FEATURE_GIT_DIR) { Either.Ok(featureBranch) }

        assertEquals(DataState.Loaded(featureBranch), repository.currentBranch.first())
    }

    @Test
    fun `skips a refresh of the worktree that the tab already left`(): Unit = runBlocking {
        repository.setRepositorySelectionState(RepositorySelectionState.Open(FEATURE_GIT_DIR))
        repository.updateCurrentBranch(FEATURE_GIT_DIR) { Either.Ok(featureBranch) }
        var gitRan = false

        repository.updateCurrentBranch(MAIN_GIT_DIR) {
            gitRan = true
            Either.Ok(mainBranch)
        }

        assertFalse(gitRan)
        assertEquals(DataState.Loaded(featureBranch), repository.currentBranch.first())
    }
}
