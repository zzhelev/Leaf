// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.log.MAX_SEARCH_DEPTH
import dev.app.leaf.domain.log.MAX_SEARCH_HITS
import dev.app.leaf.domain.models.FoundCommits

interface IFindCommitsGitAction {
    /**
     * Walks the history from [startingCommits], in the order that the log shows it, and returns the commits that match
     * [lowercaseTerm] (see `matchesLogSearch`), each with its place in the walk. The log leaves out the other parents of
     * the [stashes], and so does the walk. It stops at [maxHits] hits or after [maxCommits] commits. It stops within a
     * commit when the coroutine is cancelled: the result is then an error or nothing, which the caller ignores.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        startingCommits: List<String>,
        stashes: Set<String>,
        lowercaseTerm: String,
        maxHits: Int = MAX_SEARCH_HITS,
        maxCommits: Int = MAX_SEARCH_DEPTH,
    ): Either<FoundCommits, GitError>
}
