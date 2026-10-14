// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.MergedIntoBase

interface IGetMergedIntoBaseGitAction {
    /**
     * How the work of a worktree's [branch] reached [base] (full names), or null when it hasn't, or Leaf can't tell.
     * [commitsNotInBase] is how many commits [branch] has that [base] doesn't. [startCommit] is the commit the worktree
     * started from, as its tool recorded it, for a branch without a reflog.
     *
     * With commits of its own, the branch counts as merged when merging it would change nothing, as after a squash
     * merge. Without, only when it had work of its own that is now in the base: a fresh worktree is in the base too.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        base: String,
        branch: String,
        commitsNotInBase: Int,
        startCommit: String?,
    ): Either<MergedIntoBase?, GitError>
}
