// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.SeveralPushUrlsError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.HandOffOutcome
import dev.app.leaf.domain.interfaces.ICheckHandOffGitAction
import dev.app.leaf.domain.models.ServerHandOff
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

private const val TAG = "CheckHandOffGitAction"

/**
 * What the server did with a push or a delete after the user stopped waiting for it (fork-only): `git ls-remote` of the
 * branch, on the server that the remote pushes to ([serverTips]). git, killed, set nothing here, so once the server has
 * done it, this does what git does after a push:
 * - a push moves the remote-tracking branch to the pushed commit, while it's still where it was before, and sets the
 *   upstream that `--set-upstream` sets, unless the branch has one by now;
 * - a delete removes the remote-tracking branch, while it's still at the commit deleted.
 *
 * A failure there is logged: the server has done it, which is what the tab must say.
 */
class CheckHandOffGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) : ICheckHandOffGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        handOff: ServerHandOff,
    ): Either<HandOffOutcome, GitError> = either {
        val directory = jgit.provide(repositoryPath) { git -> git.repository.commandDirectory() }.bind()

        // The tab shows that it's asking, not the processing screen, as no task runs
        val tips = when (val asked = remoteCommand.serverTips(directory, handOff.remote, listOf(handOff.serverRef), onProgress = {})) {
            is Either.Err -> if (asked.error is SeveralPushUrlsError) return@either Either.Ok(HandOffOutcome.CantTell) else raiseError(asked.error)
            is Either.Ok -> asked.value
        }

        val now = tips[handOff.serverRef]
        val done = if (handOff.isDelete) now == null else now == handOff.pushed

        if (!done) {
            return@either Either.Ok(HandOffOutcome.NotDone(now))
        }

        jgit.provide(repositoryPath) { git -> doWhatGitWould(git.repository, handOff) }
            .onErr { printError(TAG, "Could not set ${handOff.trackingRef} as git would have: $it") }

        Either.Ok(HandOffOutcome.Done)
    }

    private fun doWhatGitWould(repository: Repository, handOff: ServerHandOff) {
        val trackingRef = handOff.trackingRef
        val pushed = handOff.pushed
        val before = handOff.before

        if (pushed == null) {
            if (trackingRef != null && before != null) {
                removeTrackingBranches(repository, listOf(BranchToDelete(trackingRef, before)))
            }

            return
        }

        if (trackingRef != null) {
            val update = repository.updateRef(trackingRef)

            update.setNewObjectId(ObjectId.fromString(pushed))
            // A fetch since may have moved it, which then says more than this
            update.setExpectedOldObjectId(before?.let { ObjectId.fromString(it) } ?: ObjectId.zeroId())
            update.isForceUpdate = true
            update.setRefLogMessage("update by push", false)

            when (val result = update.update()) {
                RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE -> Unit
                else -> printError(TAG, "Could not move $trackingRef to $pushed: $result")
            }
        }

        val upstreamOf = handOff.upstreamOf ?: return
        val branch = Repository.shortenRefName(upstreamOf)
        val config = repository.config

        if (config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE) == null) {
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE, handOff.remote)
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE, handOff.serverRef)
            config.save()
        }
    }
}
