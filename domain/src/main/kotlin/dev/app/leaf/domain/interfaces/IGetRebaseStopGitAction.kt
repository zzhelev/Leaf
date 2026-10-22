// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.rebase.RebaseStop

/** The commit that the rebase in the worktree whose git dir is [repositoryPath] stopped at, or null (fork-only). */
interface IGetRebaseStopGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<RebaseStop?, GitError>
}
