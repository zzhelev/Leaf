// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.mapOk

interface IGetOtherWorktreeHeadsGitAction {
    /**
     * The commits that the repository's other worktrees have checked out with a detached HEAD, which no ref points to,
     * so the log has to start from them. The worktree of [repositoryPath] itself is left out: JGit lists its HEAD as a
     * branch. So are worktrees on a branch, whose tip the log has, and those that are missing, unborn or unreadable.
     * A rebase or a bisect has a detached HEAD too. No commit twice.
     */
    suspend operator fun invoke(repositoryPath: String): Either<List<String>, GitError> =
        withBranches(repositoryPath).mapOk { heads -> heads.map { it.commit } }

    /** The same commits, each with the branch that its worktree rebases or bisects, if any (fork-only, issue 207). */
    suspend fun withBranches(repositoryPath: String): Either<List<OtherWorktreeHead>, GitError>
}

/**
 * A commit that another worktree has checked out with a detached HEAD, and [branch], the branch (`refs/heads/x`) that the
 * worktree rebases or bisects from, or null when it does neither, or its git dir can't tell.
 */
data class OtherWorktreeHead(val commit: String, val branch: String? = null)
