// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICheckoutRemoteBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException
import javax.inject.Inject

class CheckoutRemoteBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : ICheckoutRemoteBranchGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        remoteBranch: Branch,
        fastForward: Boolean,
    ) = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val localName = Constants.R_HEADS + remoteBranch.simpleName
        val localRef = repository.exactRef(localName)

        fun cannotFastForward(): Nothing = raiseError(
            CheckoutBranchError.CannotFastForward(
                localBranch = remoteBranch.simpleName,
                remoteBranch = remoteBranch.simpleNameWithRemote,
            )
        )

        fun remoteRef() = repository.exactRef(remoteBranch.name)
            ?: throw RefNotFoundException("Branch ${remoteBranch.simpleNameWithRemote} not found")

        when {
            localRef == null -> {
                git.checkout()
                    .setCreateBranch(true)
                    .setName(remoteBranch.simpleName)
                    .setStartPoint(remoteBranch.name)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                    .call()
            }

            // Already checked out, so only the fast-forward has something to do
            repository.fullBranch == localName -> {
                if (fastForward && !git.fastForwardCurrentBranch(remoteRef())) {
                    cannotFastForward()
                }
            }

            else -> {
                if (fastForward && !git.fastForwardBranch(localRef, remoteRef())) {
                    cannotFastForward()
                }

                // If the checkout fails, a fast-forward stays done, which loses nothing
                git.checkout().setName(localName).call()
            }
        }

        Unit
    }

    /** Merges [remoteRef] into the current branch if that's a fast-forward, as `git merge --ff-only` does. */
    private fun Git.fastForwardCurrentBranch(remoteRef: Ref): Boolean {
        val result = merge()
            .include(remoteRef)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .call()

        return result.mergeStatus != MergeResult.MergeStatus.ABORTED
    }

    /**
     * Moves [localRef], a branch that isn't checked out, to [remoteRef] if that's a fast-forward, as
     * `git fetch . <remote branch>:<local branch>` does.
     */
    private fun Git.fastForwardBranch(localRef: Ref, remoteRef: Ref): Boolean {
        RevWalk(repository).use { walk ->
            val local = walk.parseCommit(localRef.objectId)
            val remote = walk.parseCommit(remoteRef.objectId)

            // Nothing to move, which `git merge --ff-only` reports as "Already up to date"
            if (walk.isMergedInto(remote, local)) return true
            if (!walk.isMergedInto(local, remote)) return false

            val update = repository.updateRef(localRef.name).apply {
                setExpectedOldObjectId(local)
                setNewObjectId(remote)
                // What JGit's merge writes for the current branch
                setRefLogMessage("merge ${remoteRef.name}: ${MergeResult.MergeStatus.FAST_FORWARD}", false)
            }

            val result = update.update(walk)

            if (result != RefUpdate.Result.FAST_FORWARD) {
                throw IOException("Couldn't move ${localRef.name} to ${remoteRef.name}: $result")
            }

            return true
        }
    }
}
