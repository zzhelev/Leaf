// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetDiscardRecordsGitAction {
    /** The discards kept in the worktree of [repositoryPath], newest first. Those older than Leaf keeps them go. */
    suspend operator fun invoke(repositoryPath: String): Either<List<DiscardRecord>, GitError>
}
