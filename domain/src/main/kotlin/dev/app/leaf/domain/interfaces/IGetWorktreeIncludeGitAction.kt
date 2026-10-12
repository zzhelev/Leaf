// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.WorktreeInclude

interface IGetWorktreeIncludeGitAction {
    /**
     * Reads the `.worktreeinclude` of the main worktree in [mainWorktreePath], and lists the files it would copy: the
     * untracked files that its patterns (gitignore syntax) match and that git ignores. Null when there's no such file.
     */
    suspend operator fun invoke(mainWorktreePath: String): Either<WorktreeInclude?, GitError>
}
