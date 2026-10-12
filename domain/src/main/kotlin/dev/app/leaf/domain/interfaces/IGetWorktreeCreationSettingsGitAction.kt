// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.WorktreeCreationSettings

interface IGetWorktreeCreationSettingsGitAction {
    /** How new worktrees of the repository are made, from its Leaf config file; the defaults when it has none. */
    suspend operator fun invoke(repositoryPath: String): Either<WorktreeCreationSettings, GitError>
}
