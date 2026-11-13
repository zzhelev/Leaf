// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.discards

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IGetDiscardRecordsGitAction
import dev.app.leaf.domain.interfaces.IPlanDiscardRestoreGitAction
import dev.app.leaf.domain.interfaces.IRestoreDiscardGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetDiscardRecordsGitAction @Inject constructor(
    private val jgit: JGit,
    private val discardStore: DiscardStore,
) : IGetDiscardRecordsGitAction {
    override suspend fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            discardStore.records(repositoryPath, git.repository)
        }
    }
}

class PlanDiscardRestoreGitAction @Inject constructor(
    private val jgit: JGit,
    private val discardRestorer: DiscardRestorer,
) : IPlanDiscardRestoreGitAction {
    override suspend fun invoke(repositoryPath: String, recordId: String) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            with(discardRestorer) { plan(repositoryPath, git.repository, recordId) }
        }
    }
}

class RestoreDiscardGitAction @Inject constructor(
    private val jgit: JGit,
    private val discardRestorer: DiscardRestorer,
) : IRestoreDiscardGitAction {
    override suspend fun invoke(repositoryPath: String, recordId: String) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            with(discardRestorer) { restore(repositoryPath, git.repository, recordId) }
        }
    }
}
