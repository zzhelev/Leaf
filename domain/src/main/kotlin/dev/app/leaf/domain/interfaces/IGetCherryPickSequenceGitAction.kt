// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.cherrypick.CherryPickSequence
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/** The cherry-pick of several commits that git stopped in the worktree whose git dir is [repositoryPath], or null. */
interface IGetCherryPickSequenceGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<CherryPickSequence?, GitError>
}
