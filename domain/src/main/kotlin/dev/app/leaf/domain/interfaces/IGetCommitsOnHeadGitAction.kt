// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/** Which of [commits] (full hashes) HEAD of the worktree whose git dir is [repositoryPath] contains (fork-only). */
interface IGetCommitsOnHeadGitAction {
    suspend operator fun invoke(repositoryPath: String, commits: List<String>): Either<Set<String>, GitError>
}
