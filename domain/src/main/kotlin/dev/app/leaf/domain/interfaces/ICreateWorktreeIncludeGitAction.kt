// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICreateWorktreeIncludeGitAction {
    /**
     * Creates a `.worktreeinclude` in the main worktree in [mainWorktreePath], with comments that explain it and
     * examples, and returns its path. An existing file is kept as it is.
     */
    suspend operator fun invoke(mainWorktreePath: String): Either<String, GitError>
}
