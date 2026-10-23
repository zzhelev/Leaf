// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICheckRemoteBranchesGitAction {
    /**
     * After the user stopped git while it deleted [remote]'s [branches] (remote-tracking branches, each with the commit
     * that was to be deleted), asks the server once which ones it still has, as the server may have gone on without git:
     * one that's gone counts as deleted, and loses its remote-tracking branch while that still points to the commit;
     * one still at the commit stays ([dev.app.leaf.domain.branches.BranchKept.StillOnRemote]), one at another commit
     * too ([dev.app.leaf.domain.branches.BranchKept.ChangedOnRemote], with that commit). It deletes nothing on the server.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        remote: String,
        branches: List<BranchToDelete>,
    ): Either<List<BranchDeletionOutcome>, GitError>
}
