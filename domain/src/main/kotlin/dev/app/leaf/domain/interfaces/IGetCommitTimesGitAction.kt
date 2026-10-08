// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetCommitTimesGitAction {
    /** Returns the committer time of each commit, in milliseconds since the epoch. Missing commits are left out. */
    suspend operator fun invoke(repositoryPath: String, commitIds: Collection<String>): Either<Map<String, Long>, GitError>
}
