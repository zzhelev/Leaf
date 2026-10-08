// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.AheadBehind

interface IGetAheadBehindGitAction {
    /** Counts the commits that [target] has and [base] doesn't, and the other way round. Both are refs or commit ids. */
    suspend operator fun invoke(repositoryPath: String, base: String, target: String): Either<AheadBehind, GitError>
}
