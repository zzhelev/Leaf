// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/**
 * Which of [commits] the history of [startingCommits] has (fork-only, upstream issue 207): the log shows a stash under a
 * branch filter when the commit it was made on is in the shown history. All are full hashes.
 */
interface IGetCommitsReachableGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        startingCommits: List<String>,
        commits: List<String>,
    ): Either<Set<String>, GitError>
}
