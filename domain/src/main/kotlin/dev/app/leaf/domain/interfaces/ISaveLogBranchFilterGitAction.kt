// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.log.LogBranchFilter

/** Saves the branches that the repository's log shows, for every worktree (fork-only, upstream issue 207). */
interface ISaveLogBranchFilterGitAction {
    suspend operator fun invoke(repositoryPath: String, filter: LogBranchFilter): Either<Unit, GitError>
}
