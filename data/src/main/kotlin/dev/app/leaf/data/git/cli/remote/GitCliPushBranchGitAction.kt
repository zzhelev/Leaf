// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TrackingBranch
import org.eclipse.jgit.lib.Constants
import java.io.File
import javax.inject.Inject

/**
 * Pushes the current branch with `git push`, as [dev.app.leaf.data.git.remote_operations.PushBranchGitAction] does
 * with JGit: to its upstream, or to [Branch] when one is given, or else to a branch of the same name on `origin`,
 * which then becomes its upstream (`-u`).
 */
class GitCliPushBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val getTrackingBranchGitAction: GetTrackingBranchGitAction,
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(
        repositoryPath: String,
        force: Boolean,
        pushTags: Boolean,
        pushWithLease: Boolean,
        specificBranch: Branch?,
    ): Either<Unit, GitError> = either {
        val push = jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val fullBranch = repository.fullBranch

            if (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS)) {
                raiseError(GenericError("HEAD isn't on a branch, so there is no branch to push."))
            }

            val tracking = if (specificBranch == null) {
                getTrackingBranchGitAction(repositoryPath, repository.branch).bind()
            } else {
                TrackingBranch(remote = specificBranch.remoteName, branch = specificBranch.simpleName)
            }

            val lease = if (force && pushWithLease && tracking != null) {
                // The lease is the remote branch as last fetched. Without one, it's a plain force push, as with JGit.
                repository.exactRef("${Constants.R_REMOTES}${tracking.remote}/${tracking.branch}")
                    ?.let { "${Constants.R_HEADS}${tracking.branch}:${it.objectId.name}" }
            } else {
                null
            }

            val args = buildList {
                addAll(listOf("push", "--porcelain", "--progress"))

                when {
                    lease != null -> add("--force-with-lease=$lease")
                    force -> add("--force")
                }

                if (pushTags) {
                    add("--tags")
                }

                if (tracking == null) {
                    addAll(listOf("--set-upstream", Constants.DEFAULT_REMOTE_NAME, fullBranch))
                } else {
                    addAll(listOf(tracking.remote, "$fullBranch:${Constants.R_HEADS}${tracking.branch}"))
                }
            }

            PushCommand(repository.commandDirectory(), args)
        }.bind()

        val output = remoteCommand.run(push.directory, push.args, authenticated = ::pushReachedRemote).bind()

        if (output.exitCode != 0) {
            raiseError(pushFailure(output))
        }

        Either.Ok(Unit)
    }

    private class PushCommand(val directory: File, val args: List<String>)
}

/** Where git commands for this repository run: its working tree, or the git dir of a bare repository. */
internal fun org.eclipse.jgit.lib.Repository.commandDirectory(): File = if (isBare) directory else workTree
