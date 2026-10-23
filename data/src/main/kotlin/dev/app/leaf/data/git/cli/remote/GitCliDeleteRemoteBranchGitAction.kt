// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteBranchChangedError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

/**
 * Deletes a branch on its remote with `git push --delete`, then the remote-tracking branch [Branch] if git didn't
 * already.
 *
 * The push goes with `--force-with-lease=<branch>:<commit>`, the commit that Leaf showed, so a branch that someone
 * pushed to since the last fetch stays ([RemoteBranchChangedError]), as when deleting several. git refuses a branch that
 * is already gone the same way (`stale info`), so the server is asked once then: a branch it doesn't have only loses
 * its remote-tracking branch, as before the lease.
 */
class GitCliDeleteRemoteBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val deleteBranchGitAction: DeleteBranchGitAction,
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(repositoryPath: String, ref: Branch): Either<Unit, GitError> = either {
        // refs/remotes/<remote>/<branch>, like JGit's DeleteRemoteBranchGitAction (a remote name has no "/")
        val remote = ref.name.removePrefix(Constants.R_REMOTES).substringBefore('/')

        val (directory, serverRef, tip) = jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            // The commit that Leaf showed, or what the remote-tracking branch has when the caller didn't say
            val tip = ref.hash.ifBlank { repository.exactRef(ref.name)?.objectId?.name.orEmpty() }

            Triple(repository.commandDirectory(), repository.serverRefOf(remote, ref.name), tip)
        }.bind()

        val output = remoteCommand.run(
            directory,
            listOf("push", "--porcelain", "--progress", "--force-with-lease=$serverRef:$tip", remote, "--delete", serverRef),
            authenticated = ::pushReachedRemote,
        ).bind()

        if (output.exitCode != 0) {
            val stale = parsePushPorcelain(output.stdout).any { it.destination == serverRef && it.reason == "stale info" }

            if (!stale) raiseError(pushFailure(output))

            val now = remoteCommand.serverTips(directory, remote, listOf(serverRef)).bind()[serverRef]

            if (now != null) {
                raiseError(RemoteBranchChangedError("$remote/${ref.simpleName}", ref.name, now, was = tip))
            }
        }

        // git removes the remote-tracking branch only when the remote's fetch refspec maps the branch to it, which a
        // single-branch clone's doesn't, and leaves it when the branch was already gone
        val stillTracked = jgit.provide(repositoryPath) { git -> git.repository.exactRef(ref.name) != null }.bind()

        if (stillTracked) {
            // Like git branch -d -r, which skips the merge check for remote-tracking branches
            deleteBranchGitAction(repositoryPath, ref, force = true).bind()
        }

        Either.Ok(Unit)
    }
}
