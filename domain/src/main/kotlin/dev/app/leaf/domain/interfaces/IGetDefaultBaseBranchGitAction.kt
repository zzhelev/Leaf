// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetDefaultBaseBranchGitAction {
    /**
     * Returns the full name of the local branch that worktrees are compared to: the branch `origin/HEAD` points to,
     * then `main`, then `master`, whichever exists first. Null when none does.
     */
    suspend operator fun invoke(repositoryPath: String): Either<String?, GitError>
}
