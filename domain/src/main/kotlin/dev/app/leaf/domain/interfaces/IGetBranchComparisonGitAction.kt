// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.BranchComparison

interface IGetBranchComparisonGitAction {
    /** Compares [branch] with [base], both full refs (`refs/heads/agent1`, `refs/remotes/origin/main`). */
    suspend operator fun invoke(
        repositoryPath: String,
        base: String,
        branch: String,
    ): Either<BranchComparison, GitError>
}
