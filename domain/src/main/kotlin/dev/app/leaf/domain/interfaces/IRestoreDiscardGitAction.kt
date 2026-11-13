// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.discards.DiscardRestore
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IRestoreDiscardGitAction {
    /**
     * Brings back the files of the discard [recordId] that can be ([DiscardRestorePlan][dev.app.leaf.domain.discards.DiscardRestorePlan]),
     * and leaves those that changed since in ways that Leaf can't merge. Fails with
     * [DiscardRecordError.ChangedSince][dev.app.leaf.domain.errors.DiscardRecordError.ChangedSince] when none can be.
     * The files brought back leave the record, which goes once none is left.
     */
    suspend operator fun invoke(repositoryPath: String, recordId: String): Either<DiscardRestore, GitError>
}
