// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.rewrite.BranchLines

/**
 * Every local branch's own commits, from the worktrees' base branch (fork-only), for the menus that squash and reword
 * them. See [BranchLines].
 */
interface IGetBranchLinesGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<BranchLines, GitError>
}
