package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.data.git.worktrees.refuseIfUsedByOtherWorktree
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.ICheckoutBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

class CheckoutBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
    private val sparseCheckout: SparseCheckoutGit,
) : ICheckoutBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch) = jgit.provide(repositoryPath) { git ->
        // JGit would check out a branch that another worktree has, unlike git
        if (branch.name.startsWith(Constants.R_HEADS)) {
            refuseIfUsedByOtherWorktree(git.repository, branch.name)
        }

        // Fork-only: JGit can't download what a partial clone lacks, and would stop halfway
        partialClone.prefetchCheckout(git.repository, branch.name).bind()

        // Fork-only: JGit would write the files that a sparse checkout leaves out, and take them out of it
        if (sparseCheckout.applies(git.repository)) {
            switchArguments(branch)?.let { sparseCheckout.run(git.repository, *it).bind() }
            return@provide
        }

        git.checkout().apply {
            setName(branch.name)
            if (branch.name.startsWith("refs/remotes/")) {
                setCreateBranch(true)
                setName(branch.simpleName)
                setStartPoint(branch.name)
                setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
            }
            call()
        }

        Unit
    }
}

/**
 * The arguments of the `git switch` that does what JGit's checkout of [branch] does (fork-only): a local branch is
 * switched to, and a remote branch makes a local one that tracks it. Null for HEAD, where there's nothing to switch to.
 */
internal fun switchArguments(branch: Branch): Array<String>? = when {
    branch.name == Constants.HEAD -> null
    branch.name.startsWith("refs/remotes/") -> switchToNewTrackingArguments(branch)
    else -> arrayOf("switch", branch.simpleName)
}

/** The arguments of the `git switch` that makes a local branch named like [remoteBranch] and tracking it (fork-only). */
internal fun switchToNewTrackingArguments(remoteBranch: Branch): Array<String> =
    arrayOf("switch", "--track", "--create", remoteBranch.simpleName, remoteBranch.name)
