// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Worktree

interface IGetWorktreesGitAction {
    /** Lists the worktrees of the repository whose git dir is [repositoryPath], the main one first. */
    suspend operator fun invoke(repositoryPath: String): Either<List<Worktree>, GitError>
}
