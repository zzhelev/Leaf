// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ISaveWorktreeBaseBranchGitAction {
    /**
     * Chooses [branch], local or remote (`refs/heads/develop`, `refs/remotes/origin/main`), as the branch that the
     * repository's worktrees are compared to. Null goes back to the branch that Leaf picks by itself.
     */
    suspend operator fun invoke(repositoryPath: String, branch: String?): Either<Unit, GitError>
}
