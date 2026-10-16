// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/**
 * Whether HEAD is still at the commit [commitHash], and no merge, rebase, cherry-pick or revert is in progress
 * (fork-only): an operation that failed changed nothing.
 */
interface IIsUnchangedSinceGitAction {
    suspend operator fun invoke(repositoryPath: String, commitHash: String): Either<Boolean, GitError>
}
