// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IFindCommitsGitAction
import dev.app.leaf.domain.models.FoundCommits
import javax.inject.Inject

/**
 * Searches the whole history for the log's search, not only the commits that the log has loaded (fork-only, upstream
 * issue 277). It starts from the commits that the log starts from, so the hits are the commits that the log can show,
 * and each hit's depth says how far the log has to load. A query: it records no task and refreshes nothing.
 */
class SearchLogUseCase @Inject constructor(
    private val getLogUseCase: GetLogUseCase,
    private val findCommitsGitAction: IFindCommitsGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(term: String) = useCaseExecutor.execute<FoundCommits> { repositoryPath ->
        val start = getLogUseCase.logStart(repositoryPath)

        Either.Ok(findCommitsGitAction(repositoryPath, start.commits, start.stashes.toSet(), term.lowercase()).bind())
    }
}
