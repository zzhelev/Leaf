// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICherryPickCommitsGitAction {
    /**
     * Runs `git cherry-pick` with [commits] in the worktree whose git dir is [repositoryPath]
     * ([dev.app.leaf.domain.cherrypick.cherryPickArguments]), and returns whether it stopped at a commit's conflicts.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        commits: List<String>,
        noCommit: Boolean,
    ): Either<Boolean, GitError>
}
