// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.WorktreeBaseBranch

interface IGetWorktreeBaseBranchGitAction {
    /**
     * Returns the branch that worktrees are compared to: the one chosen for the repository, if any, and the local
     * branch that Leaf picks by itself, the branch `origin/HEAD` points to, then `main`, then `master`.
     */
    suspend operator fun invoke(repositoryPath: String): Either<WorktreeBaseBranch, GitError>
}
