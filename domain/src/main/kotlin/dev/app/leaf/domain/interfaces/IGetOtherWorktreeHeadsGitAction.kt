// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetOtherWorktreeHeadsGitAction {
    /**
     * The commits that the repository's other worktrees have checked out with a detached HEAD, which no ref points to,
     * so the log has to start from them. The worktree of [repositoryPath] itself is left out: JGit lists its HEAD as a
     * branch. So are worktrees on a branch, whose tip the log has, and those that are missing, unborn or unreadable.
     * A rebase or a bisect has a detached HEAD too. No commit twice.
     */
    suspend operator fun invoke(repositoryPath: String): Either<List<String>, GitError>
}
