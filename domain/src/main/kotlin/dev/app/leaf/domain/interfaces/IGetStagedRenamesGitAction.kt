// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.StatusEntry

interface IGetStagedRenamesGitAction {
    /**
     * [staged], Staged's entries from the status, with each file that the index moved as one
     * [dev.app.leaf.domain.models.StatusType.RENAMED] entry in place of its deletion and its addition, as `git status`
     * pairs them (`docs/fork/status-renames.md`). Only the status that the lists show asks: it reads blobs.
     */
    suspend operator fun invoke(repositoryPath: String, staged: List<StatusEntry>): Either<List<StatusEntry>, GitError>
}
