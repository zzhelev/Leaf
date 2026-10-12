// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICleanUpAfterCommitGitAction {
    /**
     * Removes from the git dir [repositoryPath] what git's own commit removes and JGit's leaves, once a commit is made:
     * `SQUASH_MSG`, `MERGE_MSG`, `MERGE_MODE` and `AUTO_MERGE`. A `MERGE_MSG` left there would be the message of the
     * next commit made in a terminal. Nothing is removed while a rebase runs, which keeps files of its own there.
     */
    suspend operator fun invoke(repositoryPath: String): Either<Unit, GitError>
}
