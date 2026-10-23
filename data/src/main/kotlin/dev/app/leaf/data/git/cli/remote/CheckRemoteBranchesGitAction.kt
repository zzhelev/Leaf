// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICheckRemoteBranchesGitAction
import javax.inject.Inject

private const val TAG = "CheckRemoteBranchesGitAction"

/**
 * What a stopped delete left on the server (fork-only, follows upstream issue 116): `git ls-remote` of the branches.
 * Stopping kills git, but a server that already had the push's commands deletes the branches whatever git does next, as
 * an ssh or https server runs on its own (probed with git 2.54 and `git daemon`). Leaf's remote-tracking branches then
 * stay, and nothing here changes for the file watcher to see, so this is how the dialog finds out.
 */
class CheckRemoteBranchesGitAction @Inject constructor(
    private val jgit: JGit,
    private val backend: RemoteOperationsBackend,
    private val remoteCommand: GitCliRemoteCommand,
) : ICheckRemoteBranchesGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        remote: String,
        branches: List<BranchToDelete>,
    ): Either<List<BranchDeletionOutcome>, GitError> = either {
        backend.whyNotGitCli()?.let { raiseError(RemoteBranchesNeedGitError(it)) }

        val unique = branches.distinctBy { it.name }
        val (directory, serverRefs) = jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            repository.commandDirectory() to unique.associate { it.name to repository.serverRefOf(remote, it.name) }
        }.bind()

        // The dialog shows that it's asking, not the tab's processing screen, as no task runs
        val tips = remoteCommand.serverTips(directory, remote, serverRefs.values, onProgress = {}).bind()

        val outcomes = unique.map { branch ->
            when (val now = tips[serverRefs.getValue(branch.name)]) {
                null -> BranchDeletionOutcome.Deleted(branch)
                branch.leaseTip -> BranchDeletionOutcome.Kept(branch, BranchKept.StillOnRemote)
                else -> BranchDeletionOutcome.Kept(branch, BranchKept.ChangedOnRemote(now))
            }
        }

        val deleted = outcomes.filterIsInstance<BranchDeletionOutcome.Deleted>().map { it.branch }

        jgit.provide(repositoryPath) { git -> removeTrackingBranches(git.repository, deleted) }
            .onErr { printError(TAG, "Could not remove the remote-tracking branches: $it") }

        Either.Ok(outcomes)
    }
}
