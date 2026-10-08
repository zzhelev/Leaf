// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

/**
 * Deletes a branch on its remote with `git push --delete`, then the remote-tracking branch [Branch] if git didn't
 * already. A branch that is already gone from the remote only loses its remote-tracking branch, as with JGit: git
 * deletes a full ref name that the remote doesn't have without complaint (the remote warns).
 */
class GitCliDeleteRemoteBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val deleteBranchGitAction: DeleteBranchGitAction,
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(repositoryPath: String, ref: Branch): Either<Unit, GitError> = either {
        // refs/remotes/<remote>/<branch>, like JGit's DeleteRemoteBranchGitAction (a remote name has no "/")
        val remoteAndBranch = ref.name.removePrefix(Constants.R_REMOTES)
        val remote = remoteAndBranch.substringBefore('/')
        val branch = remoteAndBranch.substringAfter('/')

        val directory = jgit.provide(repositoryPath) { git -> git.repository.commandDirectory() }.bind()

        val output = remoteCommand.run(
            directory,
            listOf("push", "--porcelain", "--progress", remote, "--delete", "${Constants.R_HEADS}$branch"),
            authenticated = ::pushReachedRemote,
        ).bind()

        if (output.exitCode != 0) {
            raiseError(pushFailure(output))
        }

        // git removes the remote-tracking branch only when the remote's fetch refspec maps the branch to it, which a
        // single-branch clone's doesn't
        val stillTracked = jgit.provide(repositoryPath) { git -> git.repository.exactRef(ref.name) != null }.bind()

        if (stillTracked) {
            // Like git branch -d -r, which skips the merge check for remote-tracking branches
            deleteBranchGitAction(repositoryPath, ref, force = true).bind()
        }

        Either.Ok(Unit)
    }
}
