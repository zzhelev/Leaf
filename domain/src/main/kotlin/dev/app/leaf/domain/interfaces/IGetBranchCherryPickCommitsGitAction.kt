// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.cherrypick.CherryPickCandidate
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

/**
 * The commits of a branch that HEAD lacks, oldest first, with the ones that can't be picked marked (fork-only).
 *
 * @param commitCount how many there are. [candidates] is empty when they're more than the limit it was given.
 */
data class BranchCherryPickCommits(val candidates: List<CherryPickCandidate>, val commitCount: Int)

interface IGetBranchCherryPickCommitsGitAction {
    /** [branch]'s commits that HEAD lacks in the worktree whose git dir is [repositoryPath], [limit] at most. */
    suspend operator fun invoke(
        repositoryPath: String,
        branch: Branch,
        limit: Int,
    ): Either<BranchCherryPickCommits, GitError>
}
