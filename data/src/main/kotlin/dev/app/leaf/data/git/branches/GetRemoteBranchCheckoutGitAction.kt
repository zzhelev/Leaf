// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IGetRemoteBranchCheckoutGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RemoteBranchCheckout
import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevWalkUtils
import javax.inject.Inject

class GetRemoteBranchCheckoutGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetRemoteBranchCheckoutGitAction {
    override suspend operator fun invoke(repositoryPath: String, remoteBranch: Branch) =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val localName = Constants.R_HEADS + remoteBranch.simpleName
            val localRef = repository.exactRef(localName) ?: return@provide RemoteBranchCheckout.CreatesLocalBranch
            val remoteRef = repository.exactRef(remoteBranch.name)
                ?: throw RefNotFoundException("Branch ${remoteBranch.simpleNameWithRemote} not found")

            RevWalk(repository).use { walk ->
                val local = walk.parseCommit(localRef.objectId)
                val remote = walk.parseCommit(remoteRef.objectId)

                RemoteBranchCheckout.ChecksOutLocalBranch(
                    localBranch = remoteBranch.simpleName,
                    isCurrentBranch = repository.fullBranch == localName,
                    // Commits that one reaches and the other doesn't, as `git rev-list --left-right --count` counts
                    commitsAhead = RevWalkUtils.count(walk, local, remote),
                    commitsBehind = RevWalkUtils.count(walk, remote, local),
                )
            }
        }
}
