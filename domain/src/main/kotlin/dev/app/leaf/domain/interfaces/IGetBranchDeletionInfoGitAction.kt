// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetBranchDeletionInfoGitAction {
    /**
     * Reads what deleting the local [branches] (full names, `refs/heads/x`) would do, for the "Delete branches" dialog:
     * each one's tip as it is now, the worktree that uses it, whether it's merged into [base] (a full name, null when
     * there's none), and how many commits only it has, counted against the refs that aren't among [branches].
     * A branch that no longer exists is left out. Reads only, and changes nothing.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        base: String?,
        branches: List<String>,
    ): Either<List<BranchDeletionInfo>, GitError>
}
