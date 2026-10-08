// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.FetchRemotesError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteFetchFailure
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.Remote
import java.io.File
import javax.inject.Inject

/**
 * Fetches every remote, or [Remote] alone, with `git fetch --prune`, as
 * [dev.app.leaf.data.git.remote_operations.FetchAllRemotesGitAction] does with JGit. Each remote is fetched on its own,
 * so that one that fails doesn't keep the others from being fetched, and reports its own error.
 */
class GitCliFetchAllRemotesGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(repositoryPath: String, specificRemote: Remote?): Either<Unit, GitError> = either {
        val fetch = jgit.provide(repositoryPath) { git ->
            val remotes = git.remoteList().call().map { it.name }
            val chosen = specificRemote?.name?.takeIf { it in remotes }

            FetchPlan(git.repository.commandDirectory(), if (chosen != null) listOf(chosen) else remotes)
        }.bind()

        val failures = mutableListOf<RemoteFetchFailure>()

        for (remote in fetch.remotes) {
            val output = remoteCommand.run(fetch.directory, listOf("fetch", "--progress", "--prune", remote))

            val error = when (output) {
                is Either.Err -> output.error
                is Either.Ok -> if (output.value.exitCode == 0) {
                    null
                } else {
                    remoteOperationError(output.value.exitCode, output.value.stderr)
                }
            }

            when (error) {
                null -> {}
                // The user chose not to answer, as JGit's "Cancelled authentication", which isn't reported either
                is RemoteOperationError.PromptRefused -> {}
                is RemoteOperationError -> failures.add(RemoteFetchFailure(remote, error))
                // git couldn't run, which won't work better for the next remote
                else -> raiseError(error)
            }
        }

        if (failures.isNotEmpty()) {
            raiseError(FetchRemotesError(failures))
        }

        Either.Ok(Unit)
    }

    private class FetchPlan(val directory: File, val remotes: List<String>)
}
