// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.lfs.GitLfsNotice

/**
 * What git outside Leaf lacks for the LFS files of the worktree whose git dir is [repositoryPath] (fork-only), or null:
 * the repository doesn't use LFS, has no working tree, git runs git-lfs there, or the user dismissed that notice.
 */
interface IGetGitLfsNoticeGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<GitLfsNotice?, GitError>
}

/** Stops showing [notice] for the repository of [repositoryPath], in all its worktrees (fork-only). */
interface IDismissGitLfsNoticeGitAction {
    suspend operator fun invoke(repositoryPath: String, notice: GitLfsNotice): Either<Unit, GitError>
}

/** Runs `git lfs install` in the working tree of [repositoryPath] (fork-only). */
interface ISetUpGitLfsGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<Unit, GitError>
}
