// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetCommonGitDirGitAction {
    /**
     * The git dir that the worktrees of the repository whose git dir is [repositoryPath] share: [repositoryPath] itself
     * for the main worktree, `<common git dir>` for a linked one's `<common git dir>/worktrees/<name>`.
     */
    suspend operator fun invoke(repositoryPath: String): Either<String, GitError>
}
