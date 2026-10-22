// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IDeleteRemoteBranchesGitAction {
    /**
     * Deletes [remote]'s [branches] (their remote-tracking branches, `refs/remotes/<remote>/x`) on the server, each one
     * only if the server still has the commit it was read at, then their remote-tracking branches. One that stays,
     * because the server has another commit for it, refused, or its remote-tracking branch moved or went, never stops
     * the others: see [dev.app.leaf.domain.branches.BranchKept].
     *
     * The result is an error when git can't run ([dev.app.leaf.domain.errors.RemoteBranchesNeedGitError]), when the
     * repository couldn't be read, and when git didn't get as far as the server, which then deleted nothing, such as
     * when it couldn't connect or authenticate. Cancelling stops git, and the server may then have deleted some.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        remote: String,
        branches: List<BranchToDelete>,
    ): Either<BranchesDeletion, GitError>
}
