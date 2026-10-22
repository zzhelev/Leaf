// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.DeletionRemote
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetRemoteBranchDeletionInfoGitAction {
    /**
     * Reads what deleting [remote]'s [branches] on the server would do, for the "Delete branches" dialog, from the
     * remote-tracking branches (`refs/remotes/<remote>/x`) as last fetched: each one's tip and who made it, whether
     * it's merged into the remote's default branch, or into [base] (a full name, the worktrees' base) when the remote
     * has no `HEAD`, and how many commits only it has, counted against the refs that aren't among [branches]. The
     * remote's `HEAD` and default branch are refused. A branch that no longer exists is left out. Reads only, and asks
     * the server nothing. With [onlyMerged] ("Delete merged branches"), the branches that aren't merged aren't shown,
     * so their commits aren't counted, which takes long with many refs.
     *
     * It fails with [dev.app.leaf.domain.errors.RemoteBranchesNeedGitError] when git can't run, as only git deletes them.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        remote: String,
        base: String?,
        branches: List<String>,
        onlyMerged: Boolean = false,
    ): Either<RemoteBranchDeletionRead, GitError>
}

/** What [IGetRemoteBranchDeletionInfoGitAction] read: the [remote], the [base] that "merged" means, and the [infos]. */
data class RemoteBranchDeletionRead(
    val remote: DeletionRemote,
    val base: String?,
    val infos: List<BranchDeletionInfo>,
)
