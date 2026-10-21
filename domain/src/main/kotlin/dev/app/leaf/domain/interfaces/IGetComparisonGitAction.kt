// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode

interface IGetComparisonGitAction {
    /**
     * Compares [a] with [b], each a full ref (`refs/heads/agent1`, `refs/remotes/origin/main`) or a commit id, and lists
     * the changes of [mode].
     */
    suspend operator fun invoke(
        repositoryPath: String,
        a: String,
        b: String,
        mode: ComparisonMode,
    ): Either<Comparison, GitError>
}
