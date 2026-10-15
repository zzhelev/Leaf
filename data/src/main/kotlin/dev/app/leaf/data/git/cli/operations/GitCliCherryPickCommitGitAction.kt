// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICherryPickCommitGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

/**
 * Cherry-picks [Commit] with `git cherry-pick` (fork-only). Conflicts are an error, as with JGit's cherry-pick, and
 * stay to be resolved. A commit whose changes are already there stops too, as in a terminal: git keeps
 * `CHERRY_PICK_HEAD`, and its message says so.
 */
class GitCliCherryPickCommitGitAction @Inject constructor(
    private val operations: GitCliOperations,
) : ICherryPickCommitGitAction {
    override suspend fun invoke(repositoryPath: String, commit: Commit): Either<Unit, GitError> = either {
        val stoppedAtConflicts = operations.run(repositoryPath, listOf("cherry-pick", commit.hash)).bind()

        if (stoppedAtConflicts) {
            raiseError(
                GenericError("Cherry-pick stopped with conflicts. Fix the conflicts and commit the desired changes.")
            )
        }

        Either.Ok(Unit)
    }
}
