// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.cherrypick.BranchesAhead
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/**
 * How many commits each local and remote branch has that the HEAD of the worktree whose git dir is [repositoryPath]
 * lacks, for the branch menus' cherry-pick item (fork-only).
 */
interface IGetBranchesAheadGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<BranchesAhead, GitError>
}
