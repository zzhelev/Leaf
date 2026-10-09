// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import javax.inject.Inject

/**
 * Updates the submodule at [path] with `git submodule update --init --recursive`: clones it if it isn't yet, and checks
 * out the commit that the repository records, then does the same for the submodules inside it. JGit's
 * [dev.app.leaf.data.git.submodules.UpdateSubmoduleGitAction] doesn't go into nested submodules.
 *
 * git checks the files out, with git-lfs when it's installed, as in a terminal.
 */
class GitCliUpdateSubmoduleGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(repositoryPath: String, path: String): Either<Unit, GitError> = either {
        val directory = jgit.provide(repositoryPath) { git -> git.repository.commandDirectory() }.bind()

        val output = remoteCommand.run(
            directory,
            listOf("submodule", "update", "--init", "--recursive", "--progress", "--", path),
        ).bind()

        if (output.exitCode != 0) {
            raiseError(remoteOperationError(output.exitCode, output.stderr))
        }

        Either.Ok(Unit)
    }
}
