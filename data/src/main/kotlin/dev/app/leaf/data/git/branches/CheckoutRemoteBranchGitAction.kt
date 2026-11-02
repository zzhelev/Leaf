// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.data.git.worktrees.refuseIfUsedByOtherWorktree
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
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
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException
import javax.inject.Inject

class CheckoutRemoteBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
    private val sparseCheckout: SparseCheckoutGit,
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
                // JGit can't download what a partial clone lacks, and would stop halfway
                partialClone.prefetchCheckout(repository, remoteBranch.name).bind()

                // Fork-only: JGit would write the files that a sparse checkout leaves out, and take them out of it
                if (sparseCheckout.applies(repository)) {
                    sparseCheckout.run(repository, *switchToNewTrackingArguments(remoteBranch)).bind()
                } else {
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(remoteBranch.simpleName)
                        .setStartPoint(remoteBranch.name)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                        .call()
                }
            }

            // Already checked out, so only the fast-forward has something to do
            repository.fullBranch == localName -> {
                if (fastForward) {
                    partialClone.prefetchCheckout(repository, remoteBranch.name).bind()
                }

                if (fastForward) {
                    val fastForwarded = if (sparseCheckout.applies(repository)) {
                        fastForwardCurrentBranchWithGit(repository, remoteRef()).bind()
                    } else {
                        git.fastForwardCurrentBranch(remoteRef())
                    }

                    if (!fastForwarded) {
                        cannotFastForward()
                    }
                }
            }

            else -> {
                // Before the fast-forward too, which would move the branch under the other worktree's files
                refuseIfUsedByOtherWorktree(repository, localName)
                partialClone.prefetchCheckout(repository, if (fastForward) remoteBranch.name else localName).bind()

                if (fastForward && !git.fastForwardBranch(localRef, remoteRef())) {
                    cannotFastForward()
                }

                // If the checkout fails, a fast-forward stays done, which loses nothing
                if (sparseCheckout.applies(repository)) {
                    sparseCheckout.run(repository, "switch", remoteBranch.simpleName).bind()
                } else {
                    git.checkout().setName(localName).call()
                }
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
     * [fastForwardCurrentBranch] with `git merge --ff-only` (fork-only), which keeps a sparse checkout's skipped files
     * skipped. The ancestry is read first, as JGit reports it: a branch that can't fast-forward is a `false`, not an
     * error, and nothing to move is a success.
     */
    private suspend fun fastForwardCurrentBranchWithGit(repository: Repository, remoteRef: Ref): Either<Boolean, GitError> {
        val head = repository.resolve(Constants.HEAD) ?: return Either.Ok(false)

        val relation = RevWalk(repository).use { walk ->
            val local = walk.parseCommit(head)
            val remote = walk.parseCommit(remoteRef.objectId)

            when {
                walk.isMergedInto(remote, local) -> FastForward.NOTHING_TO_MOVE
                walk.isMergedInto(local, remote) -> FastForward.POSSIBLE
                else -> FastForward.IMPOSSIBLE
            }
        }

        return when (relation) {
            FastForward.NOTHING_TO_MOVE -> Either.Ok(true)
            FastForward.IMPOSSIBLE -> Either.Ok(false)
            FastForward.POSSIBLE -> when (val merged = sparseCheckout.run(repository, "merge", "--ff-only", remoteRef.name)) {
                is Either.Err -> merged
                is Either.Ok -> Either.Ok(true)
            }
        }
    }

    private enum class FastForward { NOTHING_TO_MOVE, POSSIBLE, IMPOSSIBLE }

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
