// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IRevertCommitGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

/**
 * Reverts [Commit] with `git revert --no-edit` (fork-only), with git's message (`Revert "<subject>"`). Conflicts are an
 * error, as with JGit's revert, and stay to be resolved.
 */
class GitCliRevertCommitGitAction @Inject constructor(
    private val operations: GitCliOperations,
) : IRevertCommitGitAction {
    override suspend fun invoke(repositoryPath: String, commit: Commit): Either<Unit, GitError> = either {
        val stoppedAtConflicts = operations.run(repositoryPath, listOf("revert", "--no-edit", commit.hash)).bind()

        if (stoppedAtConflicts) {
            raiseError(GenericError("Revert stopped with conflicts. Fix the conflicts and commit the desired changes."))
        }

        Either.Ok(Unit)
    }
}
