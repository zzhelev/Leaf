// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IRunGitCommandGitAction {
    /**
     * Runs `git <args>` in [folder] with no time limit, for the commands that a worktree action's dialog showed
     * (fork-only). Returns git's output, or [dev.app.leaf.domain.errors.GitCliError.CommandFailed] with its stderr.
     */
    suspend operator fun invoke(folder: String, args: List<String>): Either<String, GitError>
}
