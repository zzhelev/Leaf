// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface ICheckoutRemoteBranchGitAction {
    /**
     * Checks out the local branch with [remoteBranch]'s name, creating it to track [remoteBranch] when there is none.
     * With [fastForward], an existing local branch first moves to [remoteBranch], which must not have diverged from it.
     * An existing local branch that another worktree uses is neither moved nor checked out.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        remoteBranch: Branch,
        fastForward: Boolean,
    ): Either<Unit, GitError>
}
