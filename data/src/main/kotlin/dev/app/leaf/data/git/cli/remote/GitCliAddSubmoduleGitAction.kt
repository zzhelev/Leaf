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
 * Adds the repository at [uri] as a submodule at [path] with `git submodule add`, as JGit's
 * [dev.app.leaf.data.git.submodules.AddSubmoduleGitAction] does: git clones it, and stages `.gitmodules` and the
 * submodule. A relative [uri] (`../other.git`) is relative to the repository's remote, as in a terminal.
 */
class GitCliAddSubmoduleGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(
        repositoryPath: String,
        name: String,
        path: String,
        uri: String,
    ): Either<Unit, GitError> = either {
        val directory = jgit.provide(repositoryPath) { git -> git.repository.commandDirectory() }.bind()

        val output = remoteCommand.run(
            directory,
            listOf("submodule", "add", "--progress", "--name", name, "--", uri, path),
        ).bind()

        if (output.exitCode != 0) {
            raiseError(remoteOperationError(output.exitCode, output.stderr))
        }

        Either.Ok(Unit)
    }
}
