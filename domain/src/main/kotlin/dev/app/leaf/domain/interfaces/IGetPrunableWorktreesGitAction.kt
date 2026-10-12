// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetPrunableWorktreesGitAction {
    /**
     * What `git worktree prune` would remove, as `git worktree prune --dry-run -v` says it, one line per worktree:
     * `Removing worktrees/agent1: gitdir file points to non-existent location`.
     */
    suspend operator fun invoke(repositoryPath: String): Either<List<String>, GitError>
}
