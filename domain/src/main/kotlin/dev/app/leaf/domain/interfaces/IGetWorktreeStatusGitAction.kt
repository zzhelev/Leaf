// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.WorktreeStatus

interface IGetWorktreeStatusGitAction {
    /** Reads the changes in the worktree at [worktreePath], and how its branch compares to its upstream. */
    suspend operator fun invoke(worktreePath: String): Either<WorktreeStatus, GitError>
}
